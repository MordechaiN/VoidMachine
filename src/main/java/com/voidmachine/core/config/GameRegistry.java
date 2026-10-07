package com.voidmachine.core.config;

import java.util.Optional;

/**
 * Lookups the validator needs from the game. Implemented with Bukkit registries in production and
 * with a fake in tests, so configuration validation stays testable without a server.
 */
public interface GameRegistry {

    /** Whether {@code material} (e.g. {@code RESPAWN_ANCHOR}) is a placeable block. */
    boolean isBlock(String material);

    /** Whether {@code material} is an item. */
    boolean isItem(String material);

    /** Whether a sound event with this namespaced key exists in the vanilla registry. */
    boolean soundExists(String key);

    /** Data type of a particle ({@code VOID}, {@code DUST}, ...) or empty if the particle does not exist. */
    Optional<ParticleData> particle(String key);

    /** Particle data kinds the cue engine can supply. */
    enum ParticleData {
        /** No data. */
        NONE,
        /** {@code DustOptions}: colour + size. */
        DUST,
        /** {@code DustTransition}: two colours + size. */
        DUST_TRANSITION,
        /** {@code Color}. */
        COLOR,
        /** {@code ItemStack}: the offering is used. */
        ITEM,
        /** {@code Float}. */
        FLOAT,
        /** {@code Integer}. */
        INTEGER,
        /** Anything else: not usable from configuration. */
        UNSUPPORTED
    }
}
