/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.machine;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a registered VoidMachine physical block in the world.
 *
 * <p>Instances are lightweight and long-lived — one per registered machine, held
 * in {@link MachineRegistry} for the duration of the server session.</p>
 *
 * <p>The {@link #tryLock()} / {@link #unlock()} pair uses an {@link AtomicBoolean}
 * so that concurrent right-clicks cannot race into two simultaneous transactions on
 * the same machine. Both callers must call these on the main thread, but the
 * compare-and-set is still the correct primitive: Bukkit's main thread is single-
 * threaded for events, but scheduler tasks and async callbacks can touch the flag.</p>
 */
public final class MachineBlock {

    private final String name;
    private final String worldName;
    private final int x;
    private final int y;
    private final int z;
    /** Profile name as defined in {@code config.yml} under {@code profiles:}. */
    private final String configProfile;

    /** True while a transaction is active on this machine. CAS-protected. */
    private final AtomicBoolean locked = new AtomicBoolean(false);

    /**
     * Construct from explicit coordinates (used by {@link MachineDataStore} on load).
     */
    public MachineBlock(@NotNull String name,
                        @NotNull String worldName,
                        int x, int y, int z,
                        @NotNull String configProfile) {
        this.name = name;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.configProfile = configProfile;
    }

    /**
     * Construct from a live {@link Location} (used during admin registration).
     * The world must be loaded at call time.
     */
    public MachineBlock(@NotNull String name,
                        @NotNull Location location,
                        @NotNull String configProfile) {
        this(name,
             location.getWorld().getName(),
             location.getBlockX(),
             location.getBlockY(),
             location.getBlockZ(),
             configProfile);
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    @NotNull
    public String name() { return name; }

    @NotNull
    public String worldName() { return worldName; }

    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }

    @NotNull
    public String configProfile() { return configProfile; }

    /**
     * Stable string key used as {@link MachineRegistry} map entry.
     * Format: {@code "worldName:x:y:z"}.
     */
    @NotNull
    public String locationKey() {
        return worldName + ':' + x + ':' + y + ':' + z;
    }

    /**
     * Resolve a live {@link Location} for this machine.
     * Returns {@code null} if the world is currently unloaded.
     */
    @Nullable
    public Location location() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z);
    }

    // -------------------------------------------------------------------------
    // Lock
    // -------------------------------------------------------------------------

    /**
     * Attempt to lock this machine for an exclusive transaction.
     * Returns {@code true} only if the machine was idle and is now locked.
     * Returns {@code false} if the machine was already active.
     *
     * <p>Must be paired with an unconditional {@link #unlock()} call in every
     * code path that returns {@code true}.</p>
     */
    public boolean tryLock() {
        return locked.compareAndSet(false, true);
    }

    /**
     * Release the lock. Safe to call even if not currently locked (idempotent).
     */
    public void unlock() {
        locked.set(false);
    }

    /** Returns {@code true} if a transaction is currently active on this machine. */
    public boolean isLocked() {
        return locked.get();
    }

    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "MachineBlock{name='" + name + "', loc=" + locationKey()
                + ", profile='" + configProfile + "', locked=" + locked.get() + '}';
    }
}
