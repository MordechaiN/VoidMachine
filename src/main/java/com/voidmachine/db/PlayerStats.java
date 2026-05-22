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

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable, server-thread-confined per-player statistics aggregate.
 * Use {@link #snapshot()} to obtain an immutable copy safe to pass between threads.
 */
public final class PlayerStats {

    private final UUID playerId;
    private String playerName;
    private final EnumMap<Outcome, Long> totals = new EnumMap<>(Outcome.class);
    private final EnumMap<Outcome, Long> itemTotals = new EnumMap<>(Outcome.class);
    private long sacrifices;
    private long lastUpdateMs;

    public PlayerStats(UUID playerId, String playerName) {
        this.playerId = playerId;
        this.playerName = playerName;
        for (Outcome o : Outcome.values()) {
            totals.put(o, 0L);
            itemTotals.put(o, 0L);
        }
    }

    public void record(Outcome outcome, int inputAmount, int outputAmount) {
        sacrifices++;
        totals.merge(outcome, 1L, Long::sum);
        long delta = switch (outcome) {
            case DESTROYED -> inputAmount;
            case RETURNED -> outputAmount;
            default -> outputAmount;
        };
        itemTotals.merge(outcome, delta, Long::sum);
        this.lastUpdateMs = System.currentTimeMillis();
    }

    public UUID playerId() { return playerId; }
    public String playerName() { return playerName; }
    public void setPlayerName(String s) { this.playerName = s; }
    public long sacrifices() { return sacrifices; }
    public long count(Outcome o) { return totals.getOrDefault(o, 0L); }
    public long items(Outcome o) { return itemTotals.getOrDefault(o, 0L); }
    public long lastUpdateMs() { return lastUpdateMs; }

    public void seed(long sacrifices, Map<Outcome, Long> counts, Map<Outcome, Long> items, long lastUpdateMs) {
        this.sacrifices = sacrifices;
        this.totals.putAll(counts);
        this.itemTotals.putAll(items);
        this.lastUpdateMs = lastUpdateMs;
    }

    public Snapshot snapshot() {
        return new Snapshot(playerId, playerName, sacrifices, new EnumMap<>(totals), new EnumMap<>(itemTotals));
    }

    /** Immutable snapshot of player statistics. */
    public record Snapshot(
            UUID playerId,
            String playerName,
            long sacrifices,
            EnumMap<Outcome, Long> counts,
            EnumMap<Outcome, Long> items
    ) {
        public long count(Outcome o) { return counts.getOrDefault(o, 0L); }
        public long items(Outcome o) { return items.getOrDefault(o, 0L); }
    }
}
