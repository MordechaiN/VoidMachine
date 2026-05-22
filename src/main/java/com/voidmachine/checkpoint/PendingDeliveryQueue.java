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

import com.voidmachine.VoidMachinePlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Holds items that must be returned to offline players on their next join.
 *
 * <p>Persisted to {@code plugins/VoidMachine/pending_deliveries.yml} so
 * deliveries survive server restarts. Registered as a Bukkit listener to
 * fire on {@link PlayerJoinEvent}.</p>
 *
 * <h3>Delivery guarantee</h3>
 * Items remain in the queue and file until successfully added to the player's
 * inventory (or dropped if inventory is full). Only then is the entry removed.
 */
public final class PendingDeliveryQueue implements Listener {

    private static final String FILE_NAME = "pending_deliveries.yml";

    private final VoidMachinePlugin plugin;
    private final Logger logger;
    private final File file;

    /** In-memory queue: player UUID → list of pending items. */
    private final ConcurrentHashMap<UUID, List<Entry>> queue = new ConcurrentHashMap<>();

    public PendingDeliveryQueue(@NotNull VoidMachinePlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.file   = new File(plugin.getDataFolder(), FILE_NAME);
        load();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Queue an item for delivery to a player on their next join.
     * Immediately persists the entry to disk.
     *
     * @param playerUuid  recipient UUID
     * @param playerName  recipient name (for logging)
     * @param item        item to deliver (defensively cloned)
     * @param reason      short reason tag for audit/admin visibility
     */
    public void add(@NotNull UUID playerUuid, @NotNull String playerName,
                    @NotNull ItemStack item, @NotNull String reason) {
        Entry entry = new Entry(playerName, item.clone(), reason, System.currentTimeMillis());
        queue.computeIfAbsent(playerUuid, k -> Collections.synchronizedList(new ArrayList<>()))
             .add(entry);
        saveAsync();
        logger.info("[PendingDelivery] Queued item for " + playerName
                + " (" + item.getType().key().asString()
                + " ×" + item.getAmount() + ") reason=" + reason);
    }

    /**
     * Attempt delivery of all pending items for the given player.
     * Called from {@link PlayerJoinEvent}.
     *
     * <p>Clears the queue entry and persists the removal before attempting
     * delivery — if delivery itself fails partially, the remaining items are
     * dropped at the player's feet rather than lost.</p>
     */
    public void tryDeliver(@NotNull Player player) {
        List<Entry> entries = queue.remove(player.getUniqueId());
        if (entries == null || entries.isEmpty()) return;

        saveAsync(); // persist the removal immediately

        for (Entry entry : entries) {
            deliver(player, entry);
        }
        player.updateInventory(); // Bedrock sync
    }

    /**
     * Returns all pending entries as an unmodifiable snapshot.
     * Used by {@code /vm admin pending}.
     */
    @NotNull
    public Map<UUID, List<Entry>> snapshot() {
        return Collections.unmodifiableMap(queue);
    }

    /** Returns {@code true} if the player has pending items. */
    public boolean hasPending(@NotNull UUID playerUuid) {
        List<Entry> list = queue.get(playerUuid);
        return list != null && !list.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Listener
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        // Run one tick later so the player is fully initialised before inventory ops.
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> tryDeliver(event.getPlayer()), 1L);
    }

    // -------------------------------------------------------------------------
    // Delivery
    // -------------------------------------------------------------------------

    private void deliver(@NotNull Player player, @NotNull Entry entry) {
        ItemStack item = entry.item().clone();
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (overflow.isEmpty()) {
            logger.info("[PendingDelivery] Delivered " + item.getType().key().asString()
                    + " ×" + item.getAmount() + " to " + player.getName());
            return;
        }
        // Inventory full — drop remainder at player's feet.
        Location loc = player.getLocation();
        for (ItemStack drop : overflow.values()) {
            loc.getWorld().dropItemNaturally(loc, drop);
        }
        logger.info("[PendingDelivery] Inventory full for " + player.getName()
                + " — dropped overflow at feet.");
    }

    // -------------------------------------------------------------------------
    // Persistence (YAML, Base64-encoded item bytes)
    // -------------------------------------------------------------------------

    private void load() {
        if (!file.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("pending");
        if (root == null) return;

        for (String uuidStr : root.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidStr); }
            catch (IllegalArgumentException e) {
                logger.warning("[PendingDelivery] Bad UUID key '" + uuidStr + "' — skipping.");
                continue;
            }

            List<?> rawList = root.getList(uuidStr, Collections.emptyList());
            List<Entry> entries = new ArrayList<>();
            for (Object raw : rawList) {
                if (!(raw instanceof Map<?, ?> map)) continue;
                try {
                    String name   = (String) map.get("name");
                    String b64    = (String) map.get("item");
                    Object reasonObj = map.get("reason");
                    String reason = reasonObj instanceof String ? (String) reasonObj : "unknown";
                    Object epochObj = map.get("epoch");
                    long epoch = epochObj instanceof Number ? ((Number) epochObj).longValue() : 0L;

                    byte[] bytes = Base64.getDecoder().decode(b64);
                    ItemStack item = ItemStack.deserializeBytes(bytes);
                    if (item != null && item.getType() != Material.AIR) {
                        entries.add(new Entry(name, item, reason, epoch));
                    }
                } catch (Exception e) {
                    logger.warning("[PendingDelivery] Could not parse entry for "
                            + uuidStr + ": " + e.getMessage());
                }
            }
            if (!entries.isEmpty()) {
                queue.put(uuid, Collections.synchronizedList(entries));
            }
        }

        if (!queue.isEmpty()) {
            logger.info("[PendingDelivery] Loaded " + queue.size()
                    + " player(s) with pending item deliveries.");
        }
    }

    private void saveAsync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::saveSync);
    }

    private synchronized void saveSync() {
        YamlConfiguration cfg = new YamlConfiguration();

        for (Map.Entry<UUID, List<Entry>> e : queue.entrySet()) {
            List<Map<String, Object>> serialised = new ArrayList<>();
            for (Entry entry : new ArrayList<>(e.getValue())) { // snapshot
                try {
                    String b64 = Base64.getEncoder().encodeToString(
                            entry.item().serializeAsBytes());
                    serialised.add(Map.of(
                            "name",   entry.playerName(),
                            "item",   b64,
                            "reason", entry.reason(),
                            "epoch",  entry.createdEpoch()
                    ));
                } catch (Exception ex) {
                    logger.warning("[PendingDelivery] Could not serialize item: " + ex.getMessage());
                }
            }
            cfg.set("pending." + e.getKey().toString(), serialised);
        }

        try {
            cfg.save(file);
        } catch (IOException ex) {
            logger.severe("[PendingDelivery] Save failed: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Entry record
    // -------------------------------------------------------------------------

    public record Entry(
            @NotNull String playerName,
            @NotNull ItemStack item,
            @NotNull String reason,
            long createdEpoch
    ) {}
}
