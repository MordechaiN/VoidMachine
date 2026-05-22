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

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Prevents all player inventory interaction inside the VoidMachine cinematic GUI.
 *
 * <p>Cancels {@link InventoryClickEvent} and {@link InventoryDragEvent} for any
 * inventory identified as an active VM GUI by {@link CinematicGui#isVmInventory}.
 * This prevents item theft, shift-click abuse, or accidental drops during the
 * ritual animation.</p>
 *
 * <p>Players CAN close the inventory freely — the animation continues in the
 * background and the boss bar remains visible. Closing early degrades the
 * cinematic experience but does not affect transaction correctness.</p>
 */
public final class CinematicGuiListener implements Listener {

    private final CinematicGui gui;

    public CinematicGuiListener(@NotNull CinematicGui gui) {
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        // event.getInventory() returns the top inventory of the open view.
        // This catches clicks in the VM GUI AND shift-clicks from the player
        // inventory that would push items INTO the VM GUI.
        if (gui.isVmInventory(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (gui.isVmInventory(event.getInventory())) {
            event.setCancelled(true);
        }
    }
}
