package com.voidmachine.api.event;

import com.voidmachine.api.RitualView;
import org.bukkit.event.HandlerList;

/**
 * An interrupted or deferred ritual was settled when its player came back (or after a restart).
 */
public final class RitualRecoveryEvent extends RitualEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int paidNow;

    public RitualRecoveryEvent(RitualView ritual, int paidNow) {
        super(ritual);
        this.paidNow = paidNow;
    }

    /** Reward items paid by this recovery step. */
    public int paidNow() {
        return paidNow;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
