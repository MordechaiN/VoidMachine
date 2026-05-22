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
import com.voidmachine.transaction.TransactionRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Hard-aborts any VoidMachine state when a player dies.
 *
 * <h3>Handled scenarios</h3>
 * <ul>
 *   <li><b>Open staging GUI</b>: The item in slot 22 is a custom-inventory item —
 *       Minecraft will NOT automatically drop it with the player's death loot.
 *       This listener extracts it and adds it directly to {@link PlayerDeathEvent#getDrops()}
 *       (or returns it to the player's inventory if {@code keepInventory} is active).</li>
 *
 *   <li><b>Active transaction</b>: The item has already been captured and a WAL
 *       checkpoint written. {@link ItemCaptureService#abortTransaction} cancels the
 *       animation, unlocks the machine, and calls {@code giveOrDrop} to return the item.
 *       At {@link EventPriority#MONITOR} the player's inventory is not yet cleared, so
 *       the refund goes there; if {@code keepInventory=false} it will appear in the
 *       final drops. If the item is lost (edge-case timing), the checkpoint on disk
 *       allows admin review via the audit log.</li>
 * </ul>
 *
 * <h3>Priority</h3>
 * {@link EventPriority#MONITOR} — runs last, after keep-inventory and other plugins.
 * This ensures {@link PlayerDeathEvent#getKeepInventory()} reflects the final value
 * chosen by all other plugins.
 */
public final class PlayerDeathListener implements Listener {

    private final StagingGui stagingGui;
    private final TransactionRegistry txRegistry;
    private final ItemCaptureService captureService;

    public PlayerDeathListener(@NotNull StagingGui stagingGui,
                               @NotNull TransactionRegistry txRegistry,
                               @NotNull ItemCaptureService captureService) {
        this.stagingGui     = stagingGui;
        this.txRegistry     = txRegistry;
        this.captureService = captureService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID   uuid   = player.getUniqueId();

        // ── Staging GUI ───────────────────────────────────────────────────────
        // Item is in a custom inventory — Minecraft will NOT drop it automatically.
        // Extract it and inject into death drops (or give back if keepInventory).

        ItemStack stagingItem = stagingGui.takeInputItem(uuid);
        if (stagingItem != null) {
            stagingGui.closeSessionSilently(uuid);
            if (event.getKeepInventory()) {
                // keepInventory: return directly — player keeps it.
                player.getInventory().addItem(stagingItem);
            } else {
                // Normal death: add to drops list so it falls at death location.
                event.getDrops().add(stagingItem);
            }
        }

        // ── Active transaction ────────────────────────────────────────────────
        // abortTransaction cancels the animation (despawns display, hides boss bar,
        // closes cinematic GUI), unlocks the machine, and attempts giveOrDrop for
        // the refund. Checkpoint is deleted — no phantom re-delivery on restart.

        if (txRegistry.hasActive(uuid)) {
            captureService.abortTransaction(uuid, "player_death");
        }
    }
}
