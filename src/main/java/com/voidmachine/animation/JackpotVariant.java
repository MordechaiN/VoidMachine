/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.animation;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Visual/audio variant for a {@code JACKPOT_X5} reveal.
 *
 * <p>Pre-rolled in {@link AnimationPipeline#start} alongside the outcome and
 * stored in the {@code AnimationContext} so the reveal phase dispatches to the
 * correct sequence without re-rolling.</p>
 *
 * <h3>Bedrock compatibility</h3>
 * All variants use vanilla particles, vanilla sounds, and lightning effects
 * that translate through Geyser. No custom resource packs, no blindness, no
 * nausea effects.
 */
public enum JackpotVariant {

    /**
     * Classic storm — triple lightning, challenge-complete fanfare, dragon growl.
     * The original jackpot behaviour; retained as the most common variant (equal
     * probability with the others, but the most recognisable reference point).
     */
    STORM,

    /**
     * Silent void — builds maximum dread through absence.
     * One second of smoke silence, then a sudden amethyst burst and single
     * lightning strike. "The void doesn't need to be loud."
     */
    SILENT,

    /**
     * Dragon resonance — the void acknowledges something ancient.
     * Slower sequence: portal wash → dragon growl echo → deep amethyst chime.
     * Two lightning strikes, delayed for drama.
     */
    DRAGON,

    /**
     * Void echo — chaotic, overwhelming. The machine couldn't contain it.
     * Wither spawn scream overlaid with double challenge-done, rapid double
     * lightning. More particles, faster.
     */
    ECHO;

    /** Uniform random selection across all variants. */
    @NotNull
    public static JackpotVariant roll() {
        JackpotVariant[] values = values();
        return values[ThreadLocalRandom.current().nextInt(values.length)];
    }
}
