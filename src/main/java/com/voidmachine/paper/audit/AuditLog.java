package com.voidmachine.paper.audit;

import com.google.gson.JsonObject;
import com.voidmachine.core.health.HealthMonitor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Append-only structured audit trail: one JSON object per line in {@code audit/<date>.jsonl}.
 *
 * <p>Observability, not safety: the journal is what protects items. Lines are written by a background
 * thread from a bounded queue; if the queue is full (disk stalled) entries are counted as dropped
 * rather than blocking the server. Item NBT is never logged — only material keys and amounts.</p>
 */
public final class AuditLog {

    public enum Event {
        MACHINE_CREATED, MACHINE_REMOVED, MACHINE_UPDATED,
        RITUAL_STARTED, CHECKPOINT_WRITTEN, ITEM_CAPTURED, OUTCOME_ROLLED, MACHINE_LOCKED, ANIMATION_STARTED,
        FAKEOUT, REVEAL, REWARD_DELIVERED, REWARD_HELD, TRANSACTION_COMMITTED, TRANSACTION_ABORTED, MACHINE_UNLOCKED,
        RECOVERY_STARTED, RECOVERY_APPLIED, RECOVERY_COMPLETED, RECOVERY_FAILED, RECORD_QUARANTINED,
        ADMIN_ACTION, CONFIG_RELOADED, HEALTH_CHANGED, MIGRATION
    }

    private static final String STOP = "\u0000stop";

    private final Path dir;
    private final Logger logger;
    private final HealthMonitor health;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(10_000);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private volatile boolean enabled;
    private Thread writer;

    public AuditLog(Path dir, Logger logger, HealthMonitor health) {
        this.dir = dir;
        this.logger = logger;
        this.health = health;
    }

    public void start(boolean enabled, int retentionDays) {
        this.enabled = enabled;
        if (!enabled) return;
        prune(retentionDays);
        writer = Thread.ofPlatform().name("VoidMachine-Audit").daemon(true).start(this::run);
    }

    public void log(Event event, Map<String, ?> fields) {
        if (!enabled) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", Instant.now().toString());
        o.addProperty("event", event.name());
        fields.forEach((k, v) -> {
            if (v == null) return;
            if (v instanceof Number n) o.addProperty(k, n);
            else if (v instanceof Boolean b) o.addProperty(k, b);
            else o.addProperty(k, String.valueOf(v));
        });
        if (!queue.offer(o.toString())) dropped.incrementAndGet();
    }

    /** Stops the writer after draining the queue (bounded wait). */
    public void stop() {
        if (writer == null) return;
        try {
            queue.offer(STOP, 2, TimeUnit.SECONDS);
            writer.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writer = null;
    }

    public long dropped() {
        return dropped.get();
    }

    public long written() {
        return written.get();
    }

    private void run() {
        BufferedWriter out = null;
        LocalDate openDay = null;
        try {
            while (true) {
                String line = queue.take();
                if (STOP.equals(line)) break;
                List<String> batch = new ArrayList<>();
                batch.add(line);
                queue.drainTo(batch, 500);
                boolean stop = batch.remove(STOP);
                try {
                    LocalDate today = LocalDate.now(ZoneId.systemDefault());
                    if (out == null || !today.equals(openDay)) {
                        if (out != null) out.close();
                        Files.createDirectories(dir);
                        out = Files.newBufferedWriter(dir.resolve(today + ".jsonl"), StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        openDay = today;
                    }
                    for (String l : batch) {
                        out.write(l);
                        out.newLine();
                    }
                    out.flush();
                    written.addAndGet(batch.size());
                    health.clear(HealthMonitor.Source.AUDIT);
                } catch (IOException e) {
                    dropped.addAndGet(batch.size());
                    health.raise(HealthMonitor.Source.AUDIT, "audit log cannot be written: " + e.getMessage());
                    if (out != null) {
                        try {
                            out.close();
                        } catch (IOException ignored) {
                            // already failing; reopened on the next batch
                        }
                    }
                    out = null;
                }
                if (stop) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    logger.warning("Audit log close failed: " + e.getMessage());
                }
            }
        }
    }

    private void prune(int retentionDays) {
        if (retentionDays <= 0 || !Files.isDirectory(dir)) return;
        LocalDate cutoff = LocalDate.now(ZoneId.systemDefault()).minusDays(retentionDays);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.jsonl")) {
            for (Path f : files) {
                String name = f.getFileName().toString().replace(".jsonl", "");
                try {
                    if (LocalDate.parse(name).isBefore(cutoff)) Files.deleteIfExists(f);
                } catch (java.time.format.DateTimeParseException ignored) {
                    // not ours; leave it alone
                }
            }
        } catch (IOException e) {
            logger.warning("Could not prune old audit files: " + e.getMessage());
        }
    }
}
