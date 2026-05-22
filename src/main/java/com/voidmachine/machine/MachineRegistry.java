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

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of all registered {@link MachineBlock} instances.
 *
 * <p>Two internal maps are maintained for O(1) lookup by location key and by
 * machine name. Both maps are kept in sync by every mutating operation.</p>
 *
 * <p>Only reads (lookup operations) are expected to occur concurrently. Writes
 * (register / deregister) are admin-only operations that happen on the main
 * thread, but the {@link ConcurrentHashMap} backing ensures visibility across
 * all threads regardless.</p>
 */
public final class MachineRegistry {

    /** Primary index: location key ("worldName:x:y:z") → MachineBlock. */
    private final ConcurrentHashMap<String, MachineBlock> byLocation = new ConcurrentHashMap<>();

    /** Secondary index: machine name → MachineBlock. */
    private final ConcurrentHashMap<String, MachineBlock> byName = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Mutations (main thread only, but ConcurrentHashMap for visibility)
    // -------------------------------------------------------------------------

    /**
     * Register a machine. Returns {@code false} (and does not register) if a
     * machine already occupies the same location or uses the same name.
     */
    public boolean register(@NotNull MachineBlock machine) {
        String locKey = machine.locationKey();
        if (byLocation.containsKey(locKey)) return false;
        if (byName.containsKey(machine.name())) return false;
        byLocation.put(locKey, machine);
        byName.put(machine.name(), machine);
        return true;
    }

    /**
     * Deregister a machine. No-op if the machine is not currently registered.
     */
    public void deregister(@NotNull MachineBlock machine) {
        byLocation.remove(machine.locationKey());
        byName.remove(machine.name());
    }

    /**
     * Deregister by name. Returns the removed machine, or {@code null} if not found.
     */
    @Nullable
    public MachineBlock deregisterByName(@NotNull String name) {
        MachineBlock m = byName.remove(name);
        if (m != null) byLocation.remove(m.locationKey());
        return m;
    }

    /** Remove all registered machines. Used on plugin reload/disable. */
    public void clear() {
        byLocation.clear();
        byName.clear();
    }

    // -------------------------------------------------------------------------
    // Reads (any thread)
    // -------------------------------------------------------------------------

    /**
     * Look up a machine at the given block location.
     * Returns {@code null} if no machine is registered there.
     *
     * <p>O(1) — builds the key from the location's block coordinates.</p>
     */
    @Nullable
    public MachineBlock atLocation(@NotNull Location location) {
        String key = location.getWorld().getName()
                + ':' + location.getBlockX()
                + ':' + location.getBlockY()
                + ':' + location.getBlockZ();
        return byLocation.get(key);
    }

    /**
     * Look up a machine by location key string ("worldName:x:y:z").
     * Used during startup recovery when a live Location is unavailable.
     */
    @Nullable
    public MachineBlock atKey(@NotNull String locationKey) {
        return byLocation.get(locationKey);
    }

    /**
     * Look up a machine by its registered name.
     * Returns {@code null} if no machine has that name.
     */
    @Nullable
    public MachineBlock byName(@NotNull String name) {
        return byName.get(name);
    }

    /** Snapshot of all registered machines. Safe to iterate. */
    @NotNull
    public Collection<MachineBlock> all() {
        return Collections.unmodifiableCollection(byLocation.values());
    }

    /** Number of registered machines. */
    public int size() {
        return byLocation.size();
    }

    /** Returns {@code true} if no machines are registered. */
    public boolean isEmpty() {
        return byLocation.isEmpty();
    }
}
