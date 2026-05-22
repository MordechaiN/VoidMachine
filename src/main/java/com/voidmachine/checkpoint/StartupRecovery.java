/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.checkpoint;

import com.voidmachine.audit.AuditLogger;
import com.voidmachine.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Processes orphaned checkpoint files on plugin enable.
 *
 * <p>Called synchronously from {@code VoidMachinePlugin.onEnable()} before the
 * server accepts player connections (or immediately if it's a hot-reload).
 * Runs on the main thread — any player/inventory operations are safe.</p>
 *
 * <h3>Recovery rules</h3>
 * <ul>
 *   <li>{@code CAPTURED} / {@code ANIMATING} — item captured but never delivered.
 *       Return it: deliver to online player, or enqueue for offline player.</li>
 *   <li>{@code DELIVERING} — crash occurred between marking delivery and deleting
 *       the checkpoint. Item <em>may</em> already have been partially added to the
 *       inventory. Log a warning and require admin review via
 *       {@code /vm admin pending} unless
 *       {@code checkpoint.redeliver-on-delivering-state=true}.</li>
 * </ul>
 */
public final class StartupRecovery {

    private final CheckpointStore checkpoints;
    private final PendingDeliveryQueue deliveryQueue;
    private final AuditLogger audit;
    private final PluginConfig config;
    private final Logger logger;

    public StartupRecovery(@NotNull CheckpointStore checkpoints,
                           @NotNull PendingDeliveryQueue deliveryQueue,
                           @NotNull AuditLogger audit,
                           @NotNull PluginConfig config,
                           @NotNull Logger logger) {
        this.checkpoints   = checkpoints;
        this.deliveryQueue = deliveryQueue;
        this.audit         = audit;
        this.config        = config;
        this.logger        = logger;
    }

    /**
     * Run recovery. Call from {@code onEnable()}, before registering listeners.
     */
    public void run() {
        List<CheckpointStore.CheckpointEntry> entries = checkpoints.readAll();

        if (entries.isEmpty()) {
            logger.info("[Recovery] No interrupted transactions found — clean start.");
            return;
        }

        logger.warning("[Recovery] Found " + entries.size()
                + " interrupted transaction(s) from a previous session.");

        for (CheckpointStore.CheckpointEntry entry : entries) {
            try {
                handle(entry);
            } catch (Exception ex) {
                logger.severe("[Recovery] Unhandled error processing checkpoint for "
                        + entry.playerName() + ": " + ex.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------

    private void handle(@NotNull CheckpointStore.CheckpointEntry entry) {
        switch (entry.state()) {
            case CAPTURED, ANIMATING -> recoverItem(entry, "transaction_interrupted");
            case DELIVERING          -> handleDelivering(entry);
        }
    }

    private void recoverItem(@NotNull CheckpointStore.CheckpointEntry entry,
                             @NotNull String reason) {
        ItemStack item = entry.sacrifice().clone();
        Player player  = Bukkit.getPlayer(entry.playerUuid());

        if (player != null && player.isOnline()) {
            giveOrDrop(player, item);
            player.updateInventory(); // Bedrock sync
            logger.info("[Recovery] Returned " + itemDesc(entry)
                    + " to online player " + entry.playerName());
            player.sendMessage("§7[VoidMachine] §fThe machine returns what it took.");
        } else {
            deliveryQueue.add(entry.playerUuid(), entry.playerName(), item, reason);
            logger.info("[Recovery] Queued " + itemDesc(entry)
                    + " for offline player " + entry.playerName());
        }

        audit.logRecovered(
                entry.playerUuid(), entry.playerName(),
                entry.machineLocationKey(),
                entry.sacrifice().getType().key().asString(),
                entry.sacrifice().getAmount(),
                reason);

        checkpoints.deleteAsync(entry.playerUuid());
    }

    private void handleDelivering(@NotNull CheckpointStore.CheckpointEntry entry) {
        // Log for admin visibility regardless of redeliver setting.
        String outcomeStr = entry.outcome() != null ? entry.outcome().name() : "UNKNOWN";
        logger.warning("[Recovery] DELIVERING state for " + entry.playerName()
                + " — outcome was " + outcomeStr
                + ", output=" + entry.outputAmount()
                + ". Item may have been partially granted.");

        audit.logDeliveringStateOnStartup(
                entry.playerUuid(), entry.playerName(),
                outcomeStr, entry.outputAmount());

        if (config.checkpointRedeliverOnDelivering()) {
            logger.warning("[Recovery] redeliver-on-delivering-state=true — attempting re-delivery. "
                    + "Possible duplicate if item was already granted.");
            recoverItem(entry, "delivering_state_redeliver");
        } else {
            // Put into pending queue with a special reason so admin can see it.
            logger.warning("[Recovery] Item held for admin review. "
                    + "Use /vm admin pending to inspect. "
                    + "Player: " + entry.playerName()
                    + " | Item: " + itemDesc(entry));
            deliveryQueue.add(entry.playerUuid(), entry.playerName(),
                    entry.sacrifice().clone(),
                    "DELIVERING_STATE_ADMIN_REVIEW|outcome=" + outcomeStr
                            + "|output=" + entry.outputAmount());
            checkpoints.deleteAsync(entry.playerUuid());
        }
    }

    // -------------------------------------------------------------------------

    private void giveOrDrop(@NotNull Player player, @NotNull ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (overflow.isEmpty()) return;
        Location loc = player.getLocation();
        for (ItemStack drop : overflow.values()) {
            loc.getWorld().dropItemNaturally(loc, drop);
        }
    }

    @NotNull
    private static String itemDesc(@NotNull CheckpointStore.CheckpointEntry e) {
        ItemStack s = e.sacrifice();
        return s.getAmount() + "×" + s.getType().key().asString();
    }
}
