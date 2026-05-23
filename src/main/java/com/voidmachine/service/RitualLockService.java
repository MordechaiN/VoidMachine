/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.service;

import com.voidmachine.VoidMachinePlugin;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks players currently claimed by an active ritual.
 *
 * <p>A locked player cannot move their position (camera rotation is allowed),
 * open inventories, drop items, or swap hand items.  Visual feedback: faint
 * portal particles orbit the player's body while locked, signalling to nearby
 * spectators that a ritual is in progress.</p>
 *
 * <h3>Lifecycle</h3>
 * Lock is applied by {@link com.voidmachine.animation.StagingGui#triggerStart}
 * immediately after the player confirms the ritual (START press).  Unlock happens
 * on every exit path without exception:
 * <ul>
 *   <li>Animation complete — {@link com.voidmachine.animation.AnimationPipeline#cleanupAnimation}</li>
 *   <li>Animation abort — {@link com.voidmachine.animation.AnimationPipeline#cancelForPlayer}</li>
 *   <li>Server shutdown — {@link com.voidmachine.animation.AnimationPipeline#shutdownAll}
 *       + final {@link #unlockAll()} in {@code VoidMachinePlugin.onDisable()}</li>
 *   <li>Capture failure (same tick, staging GUI restored) — {@code StagingGui.triggerStart}</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * {@link #isLocked(UUID)} is safe from any thread (reads a
 * {@link ConcurrentHashMap}-backed set).  All other public methods must be called
 * on the main thread.
 */
public final class RitualLockService {

    /** Locked player UUIDs — ConcurrentHashSet for thread-safe {@link #isLocked}. */
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();

    /**
     * One-time permits for the next {@link org.bukkit.event.inventory.InventoryOpenEvent}
     * per player.  Used so the plugin can open the {@code CinematicGui} for a locked
     * player without the lock listener cancelling that open.
     *
     * <p>Call {@link #permitNextGuiOpen(UUID)} immediately before the plugin-initiated
     * {@code player.openInventory()} call.  The permit is consumed atomically by
     * {@link #consumePermittedOpen(UUID)} in the event handler.</p>
     */
    private final Set<UUID> permittedOpens = ConcurrentHashMap.newKeySet();

    /** Repeating particle-halo tasks, one per locked player. */
    private final ConcurrentHashMap<UUID, BukkitTask> particleTasks = new ConcurrentHashMap<>();

    private final VoidMachinePlugin plugin;

    public RitualLockService(@NotNull VoidMachinePlugin plugin) {
        this.plugin = plugin;
    }

    // =========================================================================
    //  Public API (main thread)
    // =========================================================================

    /**
     * Apply the ritual lock to {@code player}.
     *
     * <p>Movement events will be intercepted by {@link RitualLockListener}.
     * A faint portal-particle halo is started at the player's chest level and
     * fires every 10 ticks (0.5 s) while locked.</p>
     *
     * <p>Idempotent — re-locking an already-locked player is a no-op.</p>
     */
    public void lock(@NotNull Player player) {
        UUID id = player.getUniqueId();
        if (locked.contains(id)) return;

        locked.add(id);

        // Subtle portal-particle halo at chest level — 5 particles, tight spread.
        // Gives spectators a visual cue without overwhelming the cinematic GUI.
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null || !p.isOnline()) {
                // Player went offline inside the task — unlock defensively.
                unlock(id);
                return;
            }
            p.getWorld().spawnParticle(
                    Particle.PORTAL,
                    p.getLocation().add(0, 1.0, 0),
                    5, 0.25, 0.40, 0.25, 0.0);
        }, 2L, 10L);

        particleTasks.put(id, task);
    }

    /**
     * Release the ritual lock for {@code playerId}.
     * Safe to call even if the player is not currently locked.
     */
    public void unlock(@NotNull UUID playerId) {
        locked.remove(playerId);

        BukkitTask task = particleTasks.remove(playerId);
        if (task != null) task.cancel();
    }

    /**
     * Release all active locks.
     * Called during plugin shutdown to guarantee no player is left locked.
     */
    public void unlockAll() {
        // Copy first — unlock() modifies the set.
        for (UUID id : new HashSet<>(locked)) {
            unlock(id);
        }
    }

    /**
     * Returns {@code true} if the player is currently claimed by a ritual.
     * Safe to call from any thread.
     */
    public boolean isLocked(@NotNull UUID playerId) {
        return locked.contains(playerId);
    }

    /**
     * Grant a one-time permit for the next inventory open for this player.
     *
     * <p>Call this immediately before any plugin-initiated {@code player.openInventory()}
     * call while the player is locked — specifically before {@code CinematicGui.open}.
     * The permit is consumed atomically by {@link #consumePermittedOpen(UUID)} in the
     * {@link RitualLockListener} and is only valid for the very next open event.</p>
     */
    public void permitNextGuiOpen(@NotNull UUID playerId) {
        permittedOpens.add(playerId);
    }

    /**
     * Consume a pending permit for {@code playerId}.
     * Returns {@code true} (and removes the permit) if one was present; otherwise
     * {@code false} (no permit — the open should be blocked).
     */
    public boolean consumePermittedOpen(@NotNull UUID playerId) {
        return permittedOpens.remove(playerId);
    }
}
