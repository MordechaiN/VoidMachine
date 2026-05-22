/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.audit;

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.core.Outcome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;

/**
 * Append-only audit logger. Writes to {@code plugins/VoidMachine/audit.log}.
 *
 * <h3>Format (CSV, one line per event)</h3>
 * <pre>
 *   timestamp,event,player_uuid,player_name,machine,item_type,item_amount,outcome,flags
 * </pre>
 *
 * <h3>Two-tier write strategy</h3>
 * <ul>
 *   <li><strong>Critical events</strong> ({@code TX_CAPTURED}, {@code TX_COMPLETED},
 *       {@code TX_ABORTED}, {@code TX_RECOVERED}, {@code TX_DELIVERING_STATE_ON_STARTUP})
 *       are written <em>synchronously and directly</em> to the file on the calling thread.
 *       This ensures they are on disk even if the server crashes immediately after.</li>
 *   <li><strong>Low-priority events</strong> (animation state changes, machine
 *       registration, watchdog fires) are enqueued into an in-memory
 *       {@link BlockingQueue} and flushed to disk every {@value #FLUSH_PERIOD_TICKS}
 *       ticks by a background task.</li>
 * </ul>
 *
 * <h3>Durability note</h3>
 * Critical direct writes are flushed ({@code BufferedWriter.flush()}) but not
 * {@code fsync}'d — the audit log's purpose is observability, not item-safe crash
 * recovery. The checkpoint system is the crash-safe recovery mechanism.
 */
public final class AuditLogger {

    private static final String FILE_NAME        = "audit.log";
    private static final int    QUEUE_CAP        = 2048;
    private static final long   FLUSH_PERIOD_TICKS = 200L; // 10 seconds

    private static final String HEADER =
            "timestamp,event,player_uuid,player_name,machine,"
                    + "item_type,item_amount,outcome,flags\n";

    // =========================================================================
    //  Event types
    // =========================================================================

    public enum Event {
        // Critical (direct write, immediate)
        TX_CAPTURED,
        TX_COMPLETED,
        TX_ABORTED,
        TX_RECOVERED,
        TX_DELIVERING_STATE_ON_STARTUP,

        // Low priority (queued, flushed every 10s)
        TX_ANIMATING,
        TX_WATCHDOG_FIRED,
        MACHINE_REGISTERED,
        MACHINE_DEREGISTERED,
        MACHINE_BROKEN_DURING_TX,
        SPAM_FLAGGED;

        /** Returns {@code true} for events that must be written synchronously. */
        public boolean isCritical() {
            return switch (this) {
                case TX_CAPTURED,
                     TX_COMPLETED,
                     TX_ABORTED,
                     TX_RECOVERED,
                     TX_DELIVERING_STATE_ON_STARTUP -> true;
                default -> false;
            };
        }
    }

    // =========================================================================

    private final File logFile;
    private final Logger logger;
    private final VoidMachinePlugin plugin;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAP);

    private int flushTaskId = -1;

    public AuditLogger(@NotNull VoidMachinePlugin plugin) {
        this.plugin  = plugin;
        this.logger  = plugin.getLogger();
        this.logFile = new File(plugin.getDataFolder(), FILE_NAME);
        ensureHeader();
    }

    /**
     * Start the background flush task. Call from {@code bootstrap()} after construction.
     */
    public void start() {
        flushTaskId = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::flushQueue,
                        FLUSH_PERIOD_TICKS, FLUSH_PERIOD_TICKS)
                .getTaskId();
    }

    /**
     * Flush all queued entries and stop the background task.
     * Call from {@code VoidMachinePlugin.onDisable()}.
     */
    public void shutdown() {
        if (flushTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(flushTaskId);
            flushTaskId = -1;
        }
        flushQueue(); // synchronous final drain
    }

    // =========================================================================
    //  Convenience logging methods
    // =========================================================================

    /** TX_CAPTURED — critical. */
    public void logCapture(@NotNull UUID uuid, @NotNull String name,
                           @NotNull String machine, @NotNull String itemType, int amount) {
        write(Event.TX_CAPTURED, uuid, name, machine, itemType, amount, null, "");
    }

    /** TX_COMPLETED — critical. */
    public void logCompleted(@NotNull UUID uuid, @NotNull String name,
                             @NotNull String machine, @NotNull String itemType,
                             int inputAmount, @NotNull Outcome outcome, int outputAmount) {
        write(Event.TX_COMPLETED, uuid, name, machine, itemType, inputAmount,
                outcome, "output=" + outputAmount);
    }

    /** TX_ABORTED — critical. */
    public void logAborted(@NotNull UUID uuid, @NotNull String name,
                           @NotNull String machine, @NotNull String itemType,
                           int amount, @NotNull String reason) {
        write(Event.TX_ABORTED, uuid, name, machine, itemType, amount, null, reason);
    }

    /** TX_RECOVERED — critical. */
    public void logRecovered(@NotNull UUID uuid, @NotNull String name,
                             @NotNull String machine, @NotNull String itemType,
                             int amount, @NotNull String notes) {
        write(Event.TX_RECOVERED, uuid, name, machine, itemType, amount, null, notes);
    }

    /** TX_DELIVERING_STATE_ON_STARTUP — critical. */
    public void logDeliveringStateOnStartup(@NotNull UUID uuid, @NotNull String name,
                                            @Nullable String outcome, int outputAmount) {
        String flags = "admin_review_required"
                + (outcome != null ? ";outcome=" + outcome : "")
                + ";output=" + outputAmount;
        write(Event.TX_DELIVERING_STATE_ON_STARTUP, uuid, name, "", "", 0, null, flags);
    }

    /** TX_ANIMATING — low priority. */
    public void logAnimating(@NotNull UUID uuid, @NotNull String name, @NotNull String machine) {
        write(Event.TX_ANIMATING, uuid, name, machine, "", 0, null, "");
    }

    /** TX_WATCHDOG_FIRED — low priority. */
    public void logWatchdog(@NotNull UUID uuid, @NotNull String name, @NotNull String machine) {
        write(Event.TX_WATCHDOG_FIRED, uuid, name, machine, "", 0, null, "watchdog_timeout");
    }

    /** MACHINE_REGISTERED — low priority. */
    public void logMachineRegistered(@NotNull String adminName, @NotNull String machineName,
                                     @NotNull String profile) {
        write(Event.MACHINE_REGISTERED, null, adminName, machineName, "", 0, null,
                "profile=" + profile);
    }

    /** MACHINE_DEREGISTERED — low priority. */
    public void logMachineDeregistered(@NotNull String adminName,
                                       @NotNull String machineName,
                                       @NotNull String reason) {
        write(Event.MACHINE_DEREGISTERED, null, adminName, machineName, "", 0, null, reason);
    }

    /** MACHINE_BROKEN_DURING_TX — low priority. */
    public void logMachineBrokenDuringTx(@NotNull String machineName,
                                         @NotNull String breakerName,
                                         @NotNull UUID txOwner) {
        write(Event.MACHINE_BROKEN_DURING_TX, txOwner, breakerName,
                machineName, "", 0, null, "broken_during_active_tx");
    }

    /** SPAM_FLAGGED — low priority. */
    public void logSpam(@NotNull UUID uuid, @NotNull String name, long gapMs) {
        write(Event.SPAM_FLAGGED, uuid, name, "", "", 0, null, "gap_ms=" + gapMs);
    }

    // =========================================================================
    //  Core write dispatch
    // =========================================================================

    private void write(@NotNull Event event,
                       @Nullable UUID uuid,
                       @NotNull String playerName,
                       @NotNull String machine,
                       @NotNull String itemType,
                       int itemAmount,
                       @Nullable Outcome outcome,
                       @NotNull String flags) {
        String line = Instant.now()
                + "," + event.name()
                + "," + (uuid != null ? uuid.toString() : "")
                + "," + escape(playerName)
                + "," + escape(machine)
                + "," + escape(itemType)
                + "," + itemAmount
                + "," + (outcome != null ? outcome.name() : "")
                + "," + escape(flags)
                + "\n";

        if (event.isCritical()) {
            writeDirectSync(line);
        } else {
            if (!queue.offer(line)) {
                logger.warning("[AuditLogger] Queue full; dropping low-priority entry: "
                        + line.trim());
            }
        }
    }

    // =========================================================================
    //  I/O helpers
    // =========================================================================

    /**
     * Write a single line directly and synchronously to the log file.
     * Used for critical events. Opens, writes, flushes, and closes the file.
     *
     * <p>Falls back to the queue on I/O failure so the entry is not lost entirely.</p>
     */
    private void writeDirectSync(@NotNull String line) {
        try (FileWriter fw = new FileWriter(logFile, StandardCharsets.UTF_8, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write(line);
            bw.flush();
        } catch (IOException e) {
            logger.severe("[AuditLogger] Direct write failed: " + e.getMessage()
                    + " — falling back to queue.");
            if (!queue.offer(line)) {
                logger.warning("[AuditLogger] Queue also full; critical audit entry dropped: "
                        + line.trim());
            }
        }
    }

    /**
     * Drain the buffered queue to disk. Called by the background scheduler
     * and synchronously on shutdown.
     */
    private void flushQueue() {
        if (queue.isEmpty()) return;

        try (FileWriter fw = new FileWriter(logFile, StandardCharsets.UTF_8, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            String line;
            while ((line = queue.poll()) != null) {
                bw.write(line);
            }
            bw.flush();
        } catch (IOException e) {
            logger.severe("[AuditLogger] Queue flush failed: " + e.getMessage());
        }
    }

    /** Write the CSV header if the file does not yet exist. */
    private void ensureHeader() {
        if (logFile.exists()) return;
        try (FileWriter fw = new FileWriter(logFile, StandardCharsets.UTF_8, false)) {
            fw.write(HEADER);
        } catch (IOException e) {
            logger.severe("[AuditLogger] Could not write header: " + e.getMessage());
        }
    }

    /**
     * CSV-escape a field. Wraps in double-quotes and escapes embedded double-quotes
     * if the value contains a comma, double-quote, or newline.
     */
    @NotNull
    private static String escape(@Nullable String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
