/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.core;

import com.voidmachine.config.PluginConfig;

import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.Map;

/**
 * Picks an {@link Outcome} for a sacrifice using configurable weights.
 *
 * <p>This class uses {@link SecureRandom} so that rolls cannot be predicted from
 * tick timing, which matters because every Java {@code Random} on a Minecraft server
 * is potentially observable by players who can correlate world events to the seed.</p>
 *
 * <p>The roller also exposes the resolved per-outcome multipliers so callers do not
 * need to reach into config themselves.</p>
 */
public final class OutcomeRoller {

    private final SecureRandom random = new SecureRandom();
    private final PluginConfig config;

    private final EnumMap<Outcome, Double> weights = new EnumMap<>(Outcome.class);
    private final EnumMap<Outcome, Integer> multipliers = new EnumMap<>(Outcome.class);
    private volatile double totalWeight;

    public OutcomeRoller(PluginConfig config) {
        this.config = config;
        reload();
    }

    public synchronized void reload() {
        weights.clear();
        multipliers.clear();
        double sum = 0.0;
        for (Outcome o : Outcome.values()) {
            double w = config.outcomeWeight(o);
            if (w < 0) {
                throw new IllegalStateException("Outcome weight for " + o + " cannot be negative");
            }
            weights.put(o, w);
            multipliers.put(o, config.outcomeMultiplier(o));
            sum += w;
        }
        if (sum <= 0) {
            throw new IllegalStateException("Sum of outcome weights must be > 0");
        }
        this.totalWeight = sum;
    }

    /**
     * Roll a single outcome. Thread-safe; safe to call from any thread.
     */
    public Outcome roll() {
        double r = random.nextDouble() * totalWeight;
        double cursor = 0.0;
        Outcome last = Outcome.DESTROYED;
        for (Map.Entry<Outcome, Double> entry : weights.entrySet()) {
            cursor += entry.getValue();
            last = entry.getKey();
            if (r < cursor) {
                return entry.getKey();
            }
        }
        return last;
    }

    /**
     * Multiplier for the given outcome (1, 2, 3, 5, ...). 0 for DESTROYED.
     */
    public int multiplierFor(Outcome outcome) {
        return multipliers.getOrDefault(outcome, outcome.defaultMultiplier());
    }

    /**
     * Roll a single outcome using the named config profile's weights.
     * Falls back to global weights for any missing profile key.
     * Uses the same {@link SecureRandom} as {@link #roll()}.
     *
     * @param profileName profile key (e.g. "default", "brutal"); falls back to global weights if unknown
     * @return rolled outcome
     */
    public Outcome rollForProfile(@NotNull String profileName) {
        double total = 0.0;
        EnumMap<Outcome, Double> profileWeights = new EnumMap<>(Outcome.class);
        for (Outcome o : Outcome.values()) {
            double w = Math.max(0.0, config.profileWeight(profileName, o));
            profileWeights.put(o, w);
            total += w;
        }
        if (total <= 0.0) {
            // Degenerate config — fall back to global roll.
            return roll();
        }
        double r = random.nextDouble() * total;
        double cursor = 0.0;
        Outcome last = Outcome.DESTROYED;
        for (Map.Entry<Outcome, Double> entry : profileWeights.entrySet()) {
            cursor += entry.getValue();
            last = entry.getKey();
            if (r < cursor) return entry.getKey();
        }
        return last;
    }

    /**
     * Pretty-printed map of outcome → percentage. Useful for /voidmachine info.
     */
    public synchronized Map<Outcome, Double> percentages() {
        EnumMap<Outcome, Double> out = new EnumMap<>(Outcome.class);
        for (Map.Entry<Outcome, Double> entry : weights.entrySet()) {
            out.put(entry.getKey(), (entry.getValue() / totalWeight) * 100.0);
        }
        return out;
    }
}
