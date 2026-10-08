package com.voidmachine.api.event;

import com.voidmachine.api.RitualView;
import org.bukkit.event.HandlerList;

/**
 * A jackpot was revealed. Fired right after {@link RitualRevealEvent}; useful for server-wide celebrations.
 */
public final class RitualJackpotEvent extends RitualEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String variant;

    public RitualJackpotEvent(RitualView ritual, String variant) {
        super(ritual);
        this.variant = variant;
    }

    /** Jackpot variant id from rituals.yml, e.g. {@code black_star}. */
    public String variant() {
        return variant;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
