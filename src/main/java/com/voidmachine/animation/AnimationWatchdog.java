/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.animation;

import com.voidmachine.VoidMachinePlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Hard-timeout safety net for active transactions.
 *
 * <p>Every transaction is assigned a watchdog when it enters {@link
 * com.voidmachine.core.Transaction.State#CAPTURED} and disarmed when it
 * reaches {@link com.voidmachine.core.Transaction.State#COMPLETED} or
 * {@link com.voidmachine.core.Transaction.State#FAILED}.</p>
 *
 * <p>If the animation task leaks, the server hangs, or an edge case prevents
 * normal termination, the watchdog fires after {@code max-duration-ticks} and
 * forces an abort via the provided callback. This prevents machine locks from
 * being held indefinitely.</p>
 *
 * <h3>Callback contract</h3>
 * The {@code onFire} {@link Runnable} passed to {@link #arm} is executed on the
 * main thread (it is scheduled via {@code BukkitScheduler.runTaskLater}). It
 * must abort the transaction and return the item to the player.
 *
 * <h3>Thread safety</h3>
 * {@link ConcurrentHashMap} protects concurrent arm/disarm. Both operations are
 * safe from the main thread (where they are always expected to be called).
 */
public final class AnimationWatchdog {

    private final VoidMachinePlugin plugin;
    private final Logger logger;
    private final int maxDurationTicks;

    /** Keyed by player UUID — one watchdog per active transaction. */
    private final ConcurrentHashMap<UUID, BukkitTask> watchdogs = new ConcurrentHashMap<>();

    /**
     * @param plugin          plugin instance (needed for scheduler access)
     * @param maxDurationTicks ticks before an unresolved transaction is force-aborted;
     *                        read from {@code world-animation.max-duration-ticks}
     */
    public AnimationWatchdog(@NotNull VoidMachinePlugin plugin, int maxDurationTicks) {
        this.plugin           = plugin;
        this.logger           = plugin.getLogger();
        this.maxDurationTicks = maxDurationTicks;
    }

    // -------------------------------------------------------------------------

    /**
     * Arm a watchdog for the given player's transaction.
     *
     * <p>If a watchdog is already armed for this player (e.g. from an earlier
     * transaction that was not properly disarmed), the existing task is cancelled
     * before the new one is registered. This is a defensive guard — it should not
     * occur in normal operation.</p>
     *
     * @param playerUuid player UUID of the transaction owner
     * @param onFire     callback executed on the main thread if the watchdog fires;
     *                   must abort the transaction and return the item
     */
    public void arm(@NotNull UUID playerUuid, @NotNull Runnable onFire) {
        // Cancel any stale watchdog for this player.
        BukkitTask stale = watchdogs.remove(playerUuid);
        if (stale != null) {
            stale.cancel();
            logger.warning("[Watchdog] Stale watchdog found for " + playerUuid
                    + " on arm — cancelled and replaced.");
        }

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            watchdogs.remove(playerUuid);
            logger.warning("[Watchdog] Fired for player " + playerUuid
                    + " after " + maxDurationTicks + " ticks — forcing abort.");
            onFire.run();
        }, maxDurationTicks);

        watchdogs.put(playerUuid, task);
    }

    /**
     * Disarm and cancel the watchdog for the given player.
     * No-op if no watchdog is armed for this player.
     *
     * <p>Must be called in every code path that resolves a transaction
     * (both COMPLETED and FAILED paths), before or immediately after
     * the terminal state transition.</p>
     */
    public void disarm(@NotNull UUID playerUuid) {
        BukkitTask task = watchdogs.remove(playerUuid);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Returns {@code true} if a watchdog is currently armed for this player.
     * Used for defensive assertions in tests and admin commands.
     */
    public boolean isArmed(@NotNull UUID playerUuid) {
        return watchdogs.containsKey(playerUuid);
    }

    /**
     * Cancel all active watchdogs. Call from {@code VoidMachinePlugin.onDisable()}.
     * Note: this cancels the tasks but does NOT fire the abort callbacks.
     * The plugin's shutdown sequence handles aborting active transactions directly.
     */
    public void shutdown() {
        for (BukkitTask task : watchdogs.values()) {
            task.cancel();
        }
        watchdogs.clear();
    }
}
