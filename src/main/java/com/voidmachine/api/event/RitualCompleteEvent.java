package com.voidmachine.api.event;

import com.voidmachine.api.RitualView;
import org.bukkit.event.HandlerList;

/**
 * The ritual presentation ended. {@code held} items may still be waiting for inventory space.
 */
public final class RitualCompleteEvent extends RitualEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int paid;
    private final int held;

    public RitualCompleteEvent(RitualView ritual, int paid, int held) {
        super(ritual);
        this.paid = paid;
        this.held = held;
    }

    /** Reward items placed in the player's inventory (or dropped for them) so far. */
    public int paid() {
        return paid;
    }

    /** Reward items still held by the Void until the player has room or comes back. */
    public int held() {
        return held;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
