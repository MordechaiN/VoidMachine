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

import com.voidmachine.core.Outcome;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence contract for the plugin. All methods are intended to be called from
 * an executor thread — implementations are responsible for their own thread safety
 * and should never call into Bukkit's main-thread-only API.
 */
public interface Storage {

    void start();

    void shutdown();

    /** Persist a single completed sacrifice and bump aggregate counters. */
    void recordTransaction(HistoryEntry entry);

    /** Load (or create) per-player statistics. */
    PlayerStats loadStats(UUID playerId, String fallbackName);

    /** Increment the daily-usage counter for the given player. */
    void incrementDailyUsage(UUID playerId, String today);

    /** Read the daily-usage counter for the given player on the given date. */
    int dailyUsage(UUID playerId, String today);

    /** Most recent N entries across all players, newest first. */
    List<HistoryEntry> recentHistory(int limit);

    /** Per-outcome leaderboard of (playerName → value). */
    Map<String, Long> topByOutcome(Outcome outcome, int limit);

    /** Total sacrifice leaderboard (playerName → total sacrifices). */
    Map<String, Long> topBySacrifices(int limit);

    /** A diagnostic 'is this backend healthy?' check. */
    @Nullable
    String selfTest();
}
