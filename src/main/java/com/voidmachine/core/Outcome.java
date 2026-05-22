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

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * The five possible outcomes of a single Void Machine ritual.
 *
 * <p>{@code multiplier} is the FACTOR applied to the inserted stack size.
 * A multiplier of 0 means the items are destroyed entirely.</p>
 */
public enum Outcome {

    DESTROYED("destroyed", 0, NamedTextColor.DARK_RED),
    RETURNED("returned",   1, NamedTextColor.GRAY),
    DOUBLED("doubled",     2, NamedTextColor.GREEN),
    TRIPLED("tripled",     3, NamedTextColor.GOLD),
    JACKPOT_X5("jackpot_x5", 5, NamedTextColor.LIGHT_PURPLE);

    private final String configKey;
    private final int defaultMultiplier;
    private final TextColor displayColor;

    Outcome(String configKey, int defaultMultiplier, TextColor displayColor) {
        this.configKey = configKey;
        this.defaultMultiplier = defaultMultiplier;
        this.displayColor = displayColor;
    }

    public String configKey() {
        return configKey;
    }

    public int defaultMultiplier() {
        return defaultMultiplier;
    }

    public TextColor displayColor() {
        return displayColor;
    }

    public boolean isDestructive() {
        return this == DESTROYED;
    }

    public boolean isWin() {
        return this == DOUBLED || this == TRIPLED || this == JACKPOT_X5;
    }
}
