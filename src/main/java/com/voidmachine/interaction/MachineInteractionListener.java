/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.interaction;

import com.voidmachine.animation.StagingGui;
import com.voidmachine.audit.AuditLogger;
import com.voidmachine.config.PluginConfig;
import com.voidmachine.machine.MachineBlock;
import com.voidmachine.machine.MachineRegistry;
import com.voidmachine.transaction.TransactionRegistry;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts right-click events on registered machine blocks and gates
 * whether the interaction should proceed to {@link ItemCaptureService}.
 *
 * <h3>Event priority</h3>
 * {@link EventPriority#HIGH} — runs after most protection plugins (NORMAL priority),
 * so they get first refusal. If the event is already cancelled by a protection
 * plugin, we skip. After our own gate checks pass, we cancel the event to prevent
 * default block interactions (e.g. charging a respawn anchor).
 *
 * <h3>Gate checks (in order)</h3>
 * <ol>
 *   <li>Action must be {@link Action#RIGHT_CLICK_BLOCK}.</li>
 *   <li>Hand must be {@link EquipmentSlot#HAND} — prevents double-fire from off-hand.</li>
 *   <li>Clicked block must be the configured core block material.</li>
 *   <li>A machine must be registered at that location.</li>
 *   <li>Player game-mode (creative/spectator) check per config.</li>
 *   <li>Vanished metadata check per config.</li>
 *   <li>Anti-spam: minimum gap between right-clicks from the same player.</li>
 *   <li>Player must not already have an active transaction.</li>
 *   <li>Machine must not already be locked (quick pre-check; real lock is CAS in captureService).</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * Event handlers run on the main thread. {@link #lastClickMs} uses
 * {@link ConcurrentHashMap} for safe access from any thread (e.g. admin reloads
 * that clear it).
 */
public final class MachineInteractionListener implements Listener {

    private final PluginConfig config;
    private final MachineRegistry machineRegistry;
    private final TransactionRegistry txRegistry;
    private final StagingGui stagingGui;
    private final AuditLogger audit;

    /**
     * Anti-spam: per-player timestamp of their most recent right-click attempt
     * on any registered machine block. Epoch millis.
     */
    private final ConcurrentHashMap<UUID, Long> lastClickMs = new ConcurrentHashMap<>();

    public MachineInteractionListener(@NotNull PluginConfig config,
                                      @NotNull MachineRegistry machineRegistry,
                                      @NotNull TransactionRegistry txRegistry,
                                      @NotNull StagingGui stagingGui,
                                      @NotNull AuditLogger audit) {
        this.config          = config;
        this.machineRegistry = machineRegistry;
        this.txRegistry      = txRegistry;
        this.stagingGui      = stagingGui;
        this.audit           = audit;
    }

    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {

        // Only right-click a block.
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        // Prevent double-fire: Bukkit fires PlayerInteractEvent for both hands.
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        if (block == null) return;

        // Must be the configured core block material.
        if (block.getType() != config.machineCoreBlock()) return;

        // Machine must be registered at this location.
        MachineBlock machine = machineRegistry.atLocation(block.getLocation());
        if (machine == null) return;

        // Cancel default block behavior (prevent respawn-anchor charging, etc.).
        event.setCancelled(true);

        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        // ── Game-mode gates ─────────────────────────────────────────────────

        GameMode gm = player.getGameMode();

        if (config.machineBlockCreative() && gm == GameMode.CREATIVE) {
            player.sendMessage("§7[VoidMachine] §cCreative players cannot activate the machine.");
            return;
        }

        if (gm == GameMode.SPECTATOR) {
            // Silently ignore — spectators pass through blocks, shouldn't see
            // messages intended for participants.
            return;
        }

        // ── Vanished check (check for "vanished" metadata key) ───────────────

        if (config.machineBlockVanished() && player.hasMetadata("vanished")) {
            // Silently ignore — vanished players are invisible to the game world.
            return;
        }

        // ── Anti-spam ────────────────────────────────────────────────────────

        long gapMs = config.machineAntiSpamGapMs();
        if (gapMs > 0) {
            long now  = System.currentTimeMillis();
            Long last = lastClickMs.get(uuid);
            if (last != null && (now - last) < gapMs) {
                // Log spam attempt; do NOT send a message (prevents feedback loop).
                audit.logSpam(uuid, player.getName(), now - last);
                return;
            }
            lastClickMs.put(uuid, now);
        }

        // ── Active transaction gate ──────────────────────────────────────────

        if (txRegistry.hasActive(uuid)) {
            player.sendMessage("§7[VoidMachine] §eA ritual is already underway for you.");
            return;
        }

        // ── Staging GUI already open for this player ─────────────────────────
        // Silently ignore — the GUI is already showing; re-opening would duplicate it.

        if (stagingGui.hasOpenSession(uuid)) {
            return;
        }

        // ── Open staging GUI ─────────────────────────────────────────────────
        // Item capture, WAL checkpoint, and machine lock happen later when
        // the player clicks the START button inside the staging GUI.

        stagingGui.open(player, machine);
    }

    /**
     * Clear the anti-spam timestamp for a specific player.
     * Call on player quit to avoid stale entries accumulating.
     */
    public void clearSpamEntry(@NotNull UUID playerUuid) {
        lastClickMs.remove(playerUuid);
    }
}
