/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.transaction;

import com.voidmachine.core.Transaction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of active {@link Transaction}s, keyed by <em>player UUID</em>.
 *
 * <h3>Invariant</h3>
 * At most one active transaction per player at any time. This is enforced by
 * {@link #register(Transaction)}: the call succeeds only if the player has no
 * existing active transaction ({@link ConcurrentHashMap#putIfAbsent} semantics).
 *
 * <h3>Keying by player, not transaction ID</h3>
 * The old GUI system keyed by transaction UUID to support hypothetical multi-GUI
 * scenarios. The physical machine system enforces one-per-player strictly, so
 * player UUID is the natural key. Admin look-ups by transaction ID can iterate
 * {@link #snapshot()} (small, ≤ max-concurrent-animations).
 *
 * <h3>Thread safety</h3>
 * Reads (get, hasActive, snapshot) are safe from any thread. Writes (register,
 * deregister) are expected on the main thread but are CAS-safe for concurrent
 * callers.
 */
public final class TransactionRegistry {

    private final ConcurrentHashMap<UUID, Transaction> active = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Mutations
    // -------------------------------------------------------------------------

    /**
     * Attempt to register a transaction for the player.
     *
     * @return {@code true} if registered; {@code false} if the player already
     *         has an active transaction (caller must reject the activation).
     */
    public boolean register(@NotNull Transaction tx) {
        return active.putIfAbsent(tx.playerId(), tx) == null;
    }

    /**
     * Remove the active transaction for the given player UUID.
     * No-op if the player has no active transaction.
     */
    public void deregister(@NotNull UUID playerUuid) {
        active.remove(playerUuid);
    }

    /**
     * Remove a specific transaction. Only removes if the registered transaction
     * for the player is the <em>same object</em> (identity check). Guards against
     * a race where a player's transaction was aborted and a new one started before
     * a stale deregister call arrives.
     */
    public void deregister(@NotNull Transaction tx) {
        active.remove(tx.playerId(), tx); // ConcurrentHashMap conditional remove
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    /**
     * Returns the active transaction for the given player, or {@code null}.
     */
    @Nullable
    public Transaction get(@NotNull UUID playerUuid) {
        return active.get(playerUuid);
    }

    /** Returns {@code true} if the player has an active transaction. */
    public boolean hasActive(@NotNull UUID playerUuid) {
        return active.containsKey(playerUuid);
    }

    /**
     * Returns a stable snapshot of all currently active transactions.
     * Safe to iterate after the call (no {@link java.util.ConcurrentModificationException}).
     * Used during shutdown and admin commands.
     */
    @NotNull
    public List<Transaction> snapshot() {
        return new ArrayList<>(active.values());
    }

    /** Returns all active transactions as an unmodifiable view. */
    @NotNull
    public Collection<Transaction> active() {
        return java.util.Collections.unmodifiableCollection(active.values());
    }

    /** Number of currently active transactions. */
    public int size() {
        return active.size();
    }

    /** Returns {@code true} if no transactions are active. */
    public boolean isEmpty() {
        return active.isEmpty();
    }
}
