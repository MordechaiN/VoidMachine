/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.db;

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.core.Outcome;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Server-wide lifetime statistics — total sacrifices, per-outcome counts,
 * items consumed, and most-offered material.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>In-memory atomic counters — O(1) increment, no locking on hot path.</li>
 *   <li>Backed by {@code plugins/VoidMachine/global_stats.yml} — loaded once on
 *       startup, saved asynchronously after each completed transaction.</li>
 *   <li>Safe to read from any thread via {@link #snapshot()}.</li>
 * </ul>
 */
public final class GlobalStats {

    private static final String FILE_NAME = "global_stats.yml";

    private final File   dataFile;
    private final Logger logger;
    private final VoidMachinePlugin plugin;

    // ── Outcome counters ──────────────────────────────────────────────────────

    private final AtomicLong totalSacrifices = new AtomicLong();
    private final AtomicLong totalDestroyed  = new AtomicLong();
    private final AtomicLong totalReturned   = new AtomicLong();
    private final AtomicLong totalDoubled    = new AtomicLong();
    private final AtomicLong totalTripled    = new AtomicLong();
    private final AtomicLong totalJackpots   = new AtomicLong();

    /** Total raw input items that entered the machine across all outcomes. */
    private final AtomicLong itemsConsumed   = new AtomicLong();

    /**
     * Per-material item count (minecraft:diamond → 340).
     * Used to determine the most-offered item.
     */
    private final ConcurrentHashMap<String, AtomicLong> itemCounts = new ConcurrentHashMap<>();

    // =========================================================================
    //  Lifecycle
    // =========================================================================

    public GlobalStats(@NotNull VoidMachinePlugin plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), FILE_NAME);
        this.logger   = plugin.getLogger();
        load();
    }

    // =========================================================================
    //  Record
    // =========================================================================

    /**
     * Record a completed transaction. Call from the main thread after outcome delivery.
     * Schedules an async YAML save after updating counters.
     *
     * @param materialKey the Minecraft material key (e.g. {@code "minecraft:diamond"})
     * @param inputAmount number of items the player sacrificed
     * @param outcome     the rolled outcome
     */
    public void record(@NotNull String materialKey, int inputAmount, @NotNull Outcome outcome) {
        totalSacrifices.incrementAndGet();
        switch (outcome) {
            case DESTROYED  -> totalDestroyed.incrementAndGet();
            case RETURNED   -> totalReturned.incrementAndGet();
            case DOUBLED    -> totalDoubled.incrementAndGet();
            case TRIPLED    -> totalTripled.incrementAndGet();
            case JACKPOT_X5 -> totalJackpots.incrementAndGet();
        }
        itemsConsumed.addAndGet(inputAmount);
        itemCounts.computeIfAbsent(materialKey, k -> new AtomicLong()).addAndGet(inputAmount);

        // Async save — global_stats.yml is tiny; write immediately after each transaction.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    // =========================================================================
    //  Snapshot (thread-safe read)
    // =========================================================================

    /**
     * Returns an immutable snapshot of current global statistics.
     * Thread-safe; may be called from any thread.
     */
    @NotNull
    public Snapshot snapshot() {
        // Find the most-offered material by total input count.
        String topItem = "(none)";
        long   topCount = 0;
        for (Map.Entry<String, AtomicLong> e : itemCounts.entrySet()) {
            long v = e.getValue().get();
            if (v > topCount) {
                topCount = v;
                topItem  = e.getKey() + " ×" + String.format("%,d", v);
            }
        }
        return new Snapshot(
                totalSacrifices.get(),
                totalDestroyed.get(),
                totalReturned.get(),
                totalDoubled.get(),
                totalTripled.get(),
                totalJackpots.get(),
                itemsConsumed.get(),
                topItem
        );
    }

    /**
     * Immutable global-stats snapshot.
     * All counts are lifetime totals since the plugin was first installed.
     */
    public record Snapshot(
            long total,
            long destroyed,
            long returned,
            long doubled,
            long tripled,
            long jackpots,
            long itemsConsumed,
            String topItem
    ) {}

    // =========================================================================
    //  Persistence
    // =========================================================================

    /**
     * Flush current counters to {@code global_stats.yml}.
     * Called asynchronously after each transaction and synchronously on shutdown.
     */
    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("total-sacrifices", totalSacrifices.get());
        yml.set("destroyed",        totalDestroyed.get());
        yml.set("returned",         totalReturned.get());
        yml.set("doubled",          totalDoubled.get());
        yml.set("tripled",          totalTripled.get());
        yml.set("jackpots",         totalJackpots.get());
        yml.set("items-consumed",   itemsConsumed.get());
        // Per-material counts — colons are legal in YAML map keys only when quoted,
        // so we replace ':' with '.' in the key name (reversed on load).
        for (Map.Entry<String, AtomicLong> e : itemCounts.entrySet()) {
            yml.set("top-items." + e.getKey().replace(':', '.'), e.getValue().get());
        }
        try {
            yml.save(dataFile);
        } catch (IOException ex) {
            logger.warning("[GlobalStats] Save failed: " + ex.getMessage());
        }
    }

    /** Load persisted counters from disk. Called once on construction. */
    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        totalSacrifices.set(yml.getLong("total-sacrifices", 0));
        totalDestroyed.set(yml.getLong("destroyed",         0));
        totalReturned.set(yml.getLong("returned",           0));
        totalDoubled.set(yml.getLong("doubled",             0));
        totalTripled.set(yml.getLong("tripled",             0));
        totalJackpots.set(yml.getLong("jackpots",           0));
        itemsConsumed.set(yml.getLong("items-consumed",     0));

        var sec = yml.getConfigurationSection("top-items");
        if (sec != null) {
            for (String dotKey : sec.getKeys(false)) {
                // Reverse the ':' → '.' substitution applied during save.
                String materialKey = dotKey.replace('.', ':');
                itemCounts.put(materialKey, new AtomicLong(sec.getLong(dotKey, 0)));
            }
        }
    }
}
