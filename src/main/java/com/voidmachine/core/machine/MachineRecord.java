package com.voidmachine.core.machine;

import com.voidmachine.core.util.Ids;

import java.util.Objects;

/**
 * A registered machine as stored on disk. Independent of whether its world is loaded: a machine in a
 * world that loads later (or is temporarily unloaded) stays registered and is never dropped.
 */
public record MachineRecord(String id, String displayName, String world, int x, int y, int z, String profile,
                            boolean enabled, long createdAt, String createdBy) {

    public MachineRecord {
        Ids.require(id, "machine id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(createdBy, "createdBy");
        if (world.isBlank()) throw new IllegalArgumentException("world is empty");
    }

    /** {@code world:x:y:z}, the block key used for lookups. */
    public String locationKey() {
        return key(world, x, y, z);
    }

    public static String key(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }

    public long chunkKey() {
        return chunkKey(x >> 4, z >> 4);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    public MachineRecord withProfile(String newProfile) {
        return new MachineRecord(id, displayName, world, x, y, z, newProfile, enabled, createdAt, createdBy);
    }

    public MachineRecord withEnabled(boolean newEnabled) {
        return new MachineRecord(id, displayName, world, x, y, z, profile, newEnabled, createdAt, createdBy);
    }
}
