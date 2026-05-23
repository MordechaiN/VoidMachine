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

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Enforces the ritual lock state managed by {@link RitualLockService}.
 *
 * <h3>Movement</h3>
 * Positional movement (XYZ) is blocked by redirecting the {@link PlayerMoveEvent}
 * destination back to the player's origin while preserving the yaw/pitch from the
 * incoming event.  This allows camera rotation (looking around) while preventing
 * walking away.
 *
 * <p>This approach avoids teleporting the player, which causes rubber-banding and
 * packet pressure on both Java and Bedrock clients through Geyser.  Setting
 * {@link PlayerMoveEvent#setTo(Location)} to the corrected position is the
 * Bedrock-safe pattern — Geyser translates Bedrock movement packets through
 * the same event pipeline without special handling required here.</p>
 *
 * <h3>Blocked interactions</h3>
 * <ul>
 *   <li>Inventory open — prevents opening chests, workbenches, etc.</li>
 *   <li>Inventory click — prevents interacting with the player's own inventory.</li>
 *   <li>Item drop — Q key.</li>
 *   <li>Swap hands — F key.</li>
 * </ul>
 *
 * <p>All handlers run at {@link EventPriority#HIGH} so other plugins see the
 * corrected state before MONITOR handlers fire.</p>
 */
public final class RitualLockListener implements Listener {

    private final RitualLockService lockService;

    public RitualLockListener(@NotNull RitualLockService lockService) {
        this.lockService = lockService;
    }

    // =========================================================================
    //  Movement
    // =========================================================================

    /**
     * Block XYZ movement; allow yaw/pitch rotation.
     *
     * <p>If the player's position changed (even by a fraction of a block),
     * the event destination is corrected to the origin position with the new
     * look direction preserved.  Pure look rotation (same XYZ) passes through
     * unchanged so the player can still aim their camera freely.</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(@NotNull PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!lockService.isLocked(player.getUniqueId())) return;

        Location from = event.getFrom();
        Location to   = event.getTo();

        // Pure look rotation — no positional delta, allow it through.
        if (from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ()) {
            return;
        }

        // Redirect to origin, preserving the new look direction.
        // No teleport — just override the event destination.
        Location corrected = from.clone();
        corrected.setYaw(to.getYaw());
        corrected.setPitch(to.getPitch());
        event.setTo(corrected);
    }

    // =========================================================================
    //  Inventory
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(@NotNull InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        if (!lockService.isLocked(id)) return;
        // Consume a one-time permit — allows the plugin to open CinematicGui for a
        // locked player without this handler cancelling that open.
        if (lockService.consumePermittedOpen(id)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!lockService.isLocked(player.getUniqueId())) return;
        event.setCancelled(true);
    }

    // =========================================================================
    //  Item actions
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDrop(@NotNull PlayerDropItemEvent event) {
        if (!lockService.isLocked(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(@NotNull PlayerSwapHandItemsEvent event) {
        if (!lockService.isLocked(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
    }
}
