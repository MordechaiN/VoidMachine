package com.voidmachine.core.custody;

import java.util.UUID;

/**
 * The minimum the custody protocol needs from a player. The live implementation wraps a Bukkit
 * {@code Player}; tests use a simulated player whose "saved file" can be rolled back to model crashes.
 *
 * <p>All methods are invoked on the server main thread.</p>
 *
 * @param <T> item template type ({@code ItemStack} in production)
 */
public interface CustodyPlayer<T> {

    UUID id();

    String name();

    /**
     * Removes exactly {@code amount} items similar to {@code template} from storage slot {@code slot}.
     * Must be all-or-nothing: returns {@code false} and changes nothing if the slot no longer holds at
     * least {@code amount} similar items.
     */
    boolean takeFromSlot(int slot, T template, int amount);

    /** How many more items of {@code template} the player can receive right now (never negative). */
    int capacityFor(T template);

    /** Gives up to {@code amount} items; returns how many were actually given. */
    int give(T template, int amount);

    /** Current encoded ledger, or {@code null} if the player has none. */
    String readLedger();

    /** Replaces the encoded ledger ({@code null} removes it). Takes effect in memory immediately. */
    void writeLedger(String encoded);

    /**
     * Asks the server to write the player's data file now. The file is replaced atomically, but
     * failures may be silent, so callers must never treat this as proof of durability.
     */
    void save();
}
