/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.listener;

import com.voidmachine.gui.GuiManager;
import com.voidmachine.gui.MachineGui;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Routes inventory events into the {@link GuiManager}. The listener does no
 * gameplay decision-making of its own.
 */
public final class InventoryListener implements Listener {

    private final GuiManager gui;

    public InventoryListener(GuiManager gui) {
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MachineGui)) return;
        gui.handleClick(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof MachineGui)) return;
        gui.handleDrag(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MachineGui)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        gui.handleClose(player, event.getInventory());
    }
}
