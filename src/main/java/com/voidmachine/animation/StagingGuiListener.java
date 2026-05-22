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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Handles player inventory interactions inside the VoidMachine staging GUI.
 *
 * <h3>Interaction model</h3>
 *
 * <b>Pre-commit</b> (START not yet pressed):
 * <ul>
 *   <li>Input slot (22): fully open — any click, pick-up, drop, or shift-click works.</li>
 *   <li>Player's own inventory: fully open — rearranging, shift-clicking out, etc.</li>
 *   <li>Shift-click from player inventory: allowed; Bukkit routes the item naturally to
 *       slot 22 (the only non-pane slot in the top inventory).</li>
 *   <li>Pane / border / START slots (all other top-inventory slots): cancelled to prevent
 *       item theft (picking up a pane) and ghost-cursor from pane-swap interactions.</li>
 *   <li>Drags: allowed if ALL touched top-inventory slots are slot 22; cancelled otherwise.</li>
 *   <li>START button (slot 40): click cancelled (prevented from swapping with pane),
 *       {@link StagingGui#triggerStart} called instead.</li>
 * </ul>
 *
 * <b>Post-commit</b> (START pressed, capture pending):
 * <ul>
 *   <li>ALL interactions cancelled — hard lock.</li>
 * </ul>
 *
 * <h3>Bedrock / controller / touch compatibility</h3>
 * No shift-click routing or Java-specific inventory habits are relied upon.
 * Normal inventory mechanics work identically on all platforms:
 * tap/click to place, tap/click to pick up, natural stack management.
 *
 * <h3>Inventory close</h3>
 * {@link EventPriority#MONITOR} — runs last, after all other plugins.
 * When capture succeeds, the staging GUI is deregistered from
 * {@link StagingGui#activeInventories} before {@code player.closeInventory()} is
 * called — so {@link StagingGui#isStagingInventory} returns {@code false} here and
 * the {@code returnAndClose} path is skipped automatically.
 */
public final class StagingGuiListener implements Listener {

    private final StagingGui gui;
    private final VoidMachinePlugin plugin;

    public StagingGuiListener(@NotNull StagingGui gui,
                              @NotNull VoidMachinePlugin plugin) {
        this.gui    = gui;
        this.plugin = plugin;
    }

    // ── Click ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!gui.isStagingInventory(event.getInventory())) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        UUID uuid = player.getUniqueId();

        // ── POST-COMMIT: hard lock ────────────────────────────────────────────
        if (gui.isCommitted(uuid)) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getInventory().getSize(); // 27 for a 3-row chest

        // ── START button ──────────────────────────────────────────────────────
        // Cancel the underlying click (prevents pane-swap ghost), then trigger.
        if (rawSlot == StagingGui.SLOT_START) {
            event.setCancelled(true);
            gui.triggerStart(player);
            return;
        }

        // ── Pane/border slots (top inventory, not the input slot) ─────────────
        // Cancelled to prevent: picking up a pane, swapping cursor item into a border
        // slot, or any other interaction that would cause a ghost cursor or item loss.
        if (rawSlot < topSize && rawSlot != StagingGui.SLOT_INPUT) {
            event.setCancelled(true);
            return;
        }

        // ── Input slot (22) or player's own inventory: allow freely ───────────
        // Shift-click from player inventory (rawSlot >= topSize, MOVE_TO_OTHER_INVENTORY)
        // is also handled here — Bukkit routes it to slot 22 since that is the only
        // available (non-filled) slot in the top inventory.
        // Schedule a START button refresh for the next tick so the button reflects
        // the new slot 22 state after Bukkit resolves the click.
        plugin.getServer().getScheduler().runTask(plugin,
                () -> gui.refreshStartButton(uuid));
    }

    // ── Drag ──────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (!gui.isStagingInventory(event.getInventory())) return;
        UUID uuid = event.getWhoClicked().getUniqueId();

        // POST-COMMIT: hard lock.
        if (gui.isCommitted(uuid)) {
            event.setCancelled(true);
            return;
        }

        int topSize = event.getInventory().getSize(); // 27

        // Cancel any drag that touches a top-inventory slot other than the input slot.
        // Drags entirely within the player's inventory (rawSlot >= topSize) are allowed.
        for (int slot : event.getRawSlots()) {
            if (slot < topSize && slot != StagingGui.SLOT_INPUT) {
                event.setCancelled(true);
                return;
            }
        }

        // Drag entirely on slot 22 (or entirely in player inventory): allow.
        // Refresh button next tick to update START button state.
        plugin.getServer().getScheduler().runTask(plugin,
                () -> gui.refreshStartButton(uuid));
    }

    // ── Close ─────────────────────────────────────────────────────────────────

    /**
     * Return the item in slot 22 when the player closes the staging GUI (ESC).
     *
     * <p>MONITOR priority — runs last. When capture succeeds, the staging GUI is
     * deregistered before {@code player.closeInventory()} fires, so
     * {@link StagingGui#isStagingInventory} returns {@code false} here and the call
     * to {@link StagingGui#returnAndClose} is skipped entirely.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (!gui.isStagingInventory(event.getInventory())) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        gui.returnAndClose(player.getUniqueId());
    }
}
