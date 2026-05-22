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

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.core.Outcome;
import com.voidmachine.db.DatabaseManager;
import com.voidmachine.db.HistoryEntry;
import com.voidmachine.db.PlayerStats;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Thin orchestration layer between gameplay events and the {@link DatabaseManager}.
 *
 * <p>This class deliberately does not cache stats locally — the database layer
 * already does, and double-caching invites stale reads after admin DB edits.</p>
 */
public final class StatsService {

    private final VoidMachinePlugin plugin;
    private final DatabaseManager database;

    public StatsService(VoidMachinePlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
    }

    public CompletableFuture<Void> record(HistoryEntry entry) {
        return database.recordTransaction(entry).exceptionally(ex -> {
            plugin.getLogger().warning("Failed to record stats: " + ex);
            return null;
        });
    }

    public CompletableFuture<PlayerStats.Snapshot> snapshot(Player player) {
        return database.loadStats(player.getUniqueId(), player.getName())
                .thenApply(PlayerStats::snapshot);
    }

    public CompletableFuture<PlayerStats.Snapshot> snapshot(UUID uuid, String fallback) {
        return database.loadStats(uuid, fallback).thenApply(PlayerStats::snapshot);
    }

    public CompletableFuture<List<HistoryEntry>> history(int limit) {
        return database.recentHistory(limit);
    }

    public CompletableFuture<Map<String, Long>> topByOutcome(Outcome outcome, int limit) {
        return database.topByOutcome(outcome, limit);
    }

    public CompletableFuture<Map<String, Long>> topBySacrifices(int limit) {
        return database.topBySacrifices(limit);
    }
}
