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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Cleans up an active session if the player disconnects mid-ritual. The actual
 * item return is delegated to {@link GuiManager#handleQuit(org.bukkit.entity.Player)}.
 */
public final class PlayerQuitListener implements Listener {

    private final GuiManager gui;

    public PlayerQuitListener(GuiManager gui) {
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        gui.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        gui.handleQuit(event.getPlayer());
    }
}
