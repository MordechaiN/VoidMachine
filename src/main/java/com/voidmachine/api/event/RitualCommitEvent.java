package com.voidmachine.api.event;

import com.voidmachine.api.RitualView;
import org.bukkit.event.HandlerList;

/**
 * The offering was taken and the verdict sealed (it is final). The player has not seen it yet.
 */
public final class RitualCommitEvent extends RitualEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public RitualCommitEvent(RitualView ritual) {
        super(ritual);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
