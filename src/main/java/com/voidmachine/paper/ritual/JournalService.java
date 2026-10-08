package com.voidmachine.paper.ritual;

import com.voidmachine.core.health.HealthMonitor;
import com.voidmachine.core.journal.FileJournal;
import com.voidmachine.core.journal.Journal;
import com.voidmachine.core.journal.JournalException;
import com.voidmachine.core.journal.JournalRecord;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Runs journal I/O (fsync) on one dedicated thread so the server tick never waits for the disk, and
 * keeps an index of known records per player. Callbacks always run on the server main thread; if the
 * plugin is disabling, they do not run at all — which is safe, because nothing irreversible happens
 * until a callback confirms durability.
 *
 * <p>Any write failure raises {@link HealthMonitor.Source#STORAGE}: new offerings are refused until a
 * probe succeeds again.</p>
 */
public final class JournalService {

    private final Plugin plugin;
    private final FileJournal journal;
    private final HealthMonitor health;
    private final Logger logger;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoidMachine-Journal");
        t.setDaemon(true);
        return t;
    });

    // Main-thread confined index.
    private final Map<UUID, JournalRecord> records = new HashMap<>();
    private final Map<UUID, Set<UUID>> byPlayer = new HashMap<>();

    public JournalService(Plugin plugin, FileJournal journal, HealthMonitor health, Logger logger) {
        this.plugin = plugin;
        this.journal = journal;
        this.health = health;
        this.logger = logger;
    }

    /** Startup only (main thread): probe the storage and load every record into the index. */
    public Journal.LoadResult loadSync() throws JournalException {
        journal.probe();
        Journal.LoadResult result = journal.loadAll();
        records.clear();
        byPlayer.clear();
        for (JournalRecord r : result.records()) index(r);
        return result;
    }

    public FileJournal journal() {
        return journal;
    }

    /** Writes durably, then calls back on the main thread with {@code true} on success. */
    public void create(JournalRecord record, Consumer<Boolean> onMain) {
        io.execute(() -> {
            boolean ok;
            try {
                journal.create(record);
                ok = true;
            } catch (JournalException e) {
                ok = false;
                fail("write", record.ritualId(), e);
            }
            boolean result = ok;
            toMain(() -> {
                if (result) index(record);
                onMain.accept(result);
            });
        });
    }

    /** Replaces a record (admin refund) durably; index updated on success. */
    public void replace(JournalRecord record, Consumer<Boolean> onMain) {
        io.execute(() -> {
            boolean ok;
            try {
                journal.replace(record);
                ok = true;
            } catch (JournalException e) {
                ok = false;
                fail("replace", record.ritualId(), e);
            }
            boolean result = ok;
            toMain(() -> {
                if (result) index(record);
                onMain.accept(result);
            });
        });
    }

    /**
     * Deletes a record. The index entry is removed immediately so the record is never processed twice;
     * if the deletion fails the file stays on disk and is simply reconciled again after a restart.
     *
     * @param onMainAfter runs on the main thread only if the file is really gone. Callers use it to
     *                    forget the player's ledger entry; forgetting it while the record survives on
     *                    disk would make a settled claim look unpaid after the restart.
     */
    public void delete(UUID ritualId, Runnable onMainAfter) {
        unindex(ritualId);
        io.execute(() -> {
            try {
                journal.delete(ritualId);
            } catch (JournalException e) {
                fail("delete", ritualId, e);
                return;
            }
            if (onMainAfter != null) toMain(onMainAfter);
        });
    }

    /** Synchronous delete, only for shutdown (main thread, after {@link #drain}). */
    public void deleteNow(UUID ritualId) {
        unindex(ritualId);
        try {
            journal.delete(ritualId);
        } catch (JournalException e) {
            logger.warning("Could not delete journal record " + ritualId + " during shutdown: " + e.getMessage()
                    + " (harmless: it is reconciled at the next start)");
        }
    }

    /** Re-probes storage off-thread; clears the storage problem on success. */
    public void probe(Consumer<Boolean> onMain) {
        io.execute(() -> {
            boolean ok;
            try {
                journal.probe();
                ok = true;
            } catch (JournalException e) {
                ok = false;
                health.raise(HealthMonitor.Source.STORAGE, e.getMessage());
            }
            boolean result = ok;
            toMain(() -> {
                if (result) health.clear(HealthMonitor.Source.STORAGE);
                onMain.accept(result);
            });
        });
    }

    public JournalRecord get(UUID ritualId) {
        return records.get(ritualId);
    }

    public List<JournalRecord> forPlayer(UUID player) {
        Set<UUID> ids = byPlayer.get(player);
        if (ids == null) return List.of();
        List<JournalRecord> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            JournalRecord r = records.get(id);
            if (r != null) out.add(r);
        }
        return out;
    }

    public Collection<JournalRecord> all() {
        return List.copyOf(records.values());
    }

    public Set<UUID> playersWithRecords() {
        return Set.copyOf(byPlayer.keySet());
    }

    public int size() {
        return records.size();
    }

    /** Lets queued I/O finish (shutdown). */
    public void drain(long timeoutMillis) {
        io.shutdown();
        try {
            if (!io.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                logger.warning("Journal I/O did not finish within " + timeoutMillis + " ms; pending records are reconciled at the next start.");
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void index(JournalRecord r) {
        records.put(r.ritualId(), r);
        byPlayer.computeIfAbsent(r.playerId(), k -> new LinkedHashSet<>()).add(r.ritualId());
    }

    private void unindex(UUID ritualId) {
        JournalRecord r = records.remove(ritualId);
        if (r == null) return;
        Set<UUID> ids = byPlayer.get(r.playerId());
        if (ids != null) {
            ids.remove(ritualId);
            if (ids.isEmpty()) byPlayer.remove(r.playerId());
        }
    }

    private void fail(String op, UUID id, JournalException e) {
        logger.severe("Journal " + op + " failed for " + id + ": " + e.getMessage()
                + " — new offerings are refused until storage works again (/vm admin health recheck).");
        health.raise(HealthMonitor.Source.STORAGE, "journal " + op + " failed: " + e.getMessage());
    }

    private void toMain(Runnable r) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, r);
        } catch (RuntimeException e) {
            // IllegalPluginAccessException while disabling: the callback is dropped on purpose (see class comment).
        }
    }
}
