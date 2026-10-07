package com.voidmachine.core.spectator;

/**
 * How much of a ritual a nearby player experiences. The owner always gets {@link #OWNER}.
 */
public enum SpectatorTier {
    /** The player making the offering. */
    OWNER,
    /** Close enough to feel everything: full particles, all sounds, narration, the shared boss bar. */
    INNER,
    /** In the room: reduced particles, key sounds, narration at key beats. */
    NEAR,
    /** Within earshot: only the big stingers (thunder, reveal). */
    FAR,
    /** Not part of the audience. */
    NONE;

    /** Radii in blocks; validated so that {@code inner < near < far}. */
    public record Radii(double inner, double near, double far) {
        public Radii {
            if (!(inner > 0 && inner < near && near < far)) {
                throw new IllegalArgumentException("spectator radii must satisfy 0 < inner < near < far (got "
                        + inner + ", " + near + ", " + far + ")");
            }
        }

        public SpectatorTier classify(double distanceSquared) {
            if (distanceSquared <= inner * inner) return INNER;
            if (distanceSquared <= near * near) return NEAR;
            if (distanceSquared <= far * far) return FAR;
            return NONE;
        }
    }

    /** Fraction of a cue's particle count this tier receives. */
    public double particleShare() {
        return switch (this) {
            case OWNER, INNER -> 1.0;
            case NEAR -> 0.5;
            case FAR, NONE -> 0.0;
        };
    }

    /** Volume factor for sounds addressed to this tier. */
    public float volumeShare() {
        return switch (this) {
            case OWNER, INNER -> 1.0f;
            case NEAR -> 0.7f;
            case FAR -> 0.45f;
            case NONE -> 0f;
        };
    }

    /** Whether this tier is included by a cue addressed to {@code audience}. */
    public boolean hears(Audience audience) {
        return switch (audience) {
            case OWNER -> this == OWNER;
            case INNER -> this == OWNER || this == INNER;
            case NEAR -> this == OWNER || this == INNER || this == NEAR;
            case ALL -> this != NONE;
        };
    }

    /** Who a cue is addressed to. */
    public enum Audience {
        OWNER, INNER, NEAR, ALL
    }
}
