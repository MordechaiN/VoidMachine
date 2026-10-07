package com.voidmachine.core.script;

import com.voidmachine.core.config.GameRegistry;
import com.voidmachine.core.spectator.SpectatorTier;

/**
 * One timed effect in a choreography. Cues are pure data; the Paper layer turns them into particles,
 * sounds, lightning and display-entity motion — always as presentation, never as game state.
 */
public sealed interface Cue permits Cue.Sound, Cue.Particle, Cue.Lightning, Cue.Display, Cue.Charge {

    /** Tick offset inside the script (or inside each loop iteration). */
    int at();

    /** Probability that this cue fires (drawn from the ritual's presentation seed). */
    double chance();

    enum Anchor {
        /** Centre of the machine block. */
        MACHINE,
        /** Top surface of the machine. */
        TOP,
        /** 1.6 blocks above the machine, where the offering floats. */
        ABOVE,
        /** The offering player's chest. Falls back to {@link #ABOVE} if they are not present. */
        OWNER
    }

    enum Shape {
        POINT, RING, COLUMN, SPIRAL
    }

    enum Channel {
        MASTER, BLOCK, AMBIENT, HOSTILE, WEATHER
    }

    record Sound(int at, double chance, String key, Range volume, Range pitch, double jitter,
                 SpectatorTier.Audience to, Channel channel) implements Cue {
    }

    /**
     * @param data        particle data kind (validated against the particle)
     * @param color       {@code #rrggbb} for DUST / DUST_TRANSITION / COLOR particles
     * @param toColor     {@code #rrggbb} for DUST_TRANSITION
     * @param value       data value for FLOAT / INTEGER particles
     */
    record Particle(int at, double chance, String particle, GameRegistry.ParticleData data, Range count,
                    double spreadX, double spreadY, double spreadZ, double speed, Anchor anchor, Shape shape,
                    double radius, double height, int points, int color, int toColor, float size, float value,
                    SpectatorTier.Audience to) implements Cue {
    }

    /** Visual lightning (no damage, no fire). */
    record Lightning(int at, double chance) implements Cue {
    }

    enum DisplayAction {
        /** Offering appears above the machine and rises. */
        RISE,
        /** Slow rotation. */
        SPIN,
        /** Scale pulse. */
        PULSE,
        /** Shrinks into the machine. */
        IMPLODE,
        /** Rises high and fades. */
        ASCEND,
        /** Removes the display. */
        HIDE,
        /** Shows the reward item large and glowing. */
        REWARD
    }

    /** Moves the floating offering (Java clients; Bedrock sees the particles instead). */
    record Display(int at, double chance, DisplayAction action) implements Cue {
    }

    /** Client-side charge level of a respawn-anchor machine (0..4) for the audience; never changes the world. */
    record Charge(int at, double chance, int level, SpectatorTier.Audience to) implements Cue {
    }
}
