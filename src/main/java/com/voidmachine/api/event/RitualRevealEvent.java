package com.voidmachine.api.event;

import com.voidmachine.api.RitualView;
import org.bukkit.event.HandlerList;

/**
 * The verdict is being revealed to the player and the crowd; rewards are paid at this moment.
 */
public final class RitualRevealEvent extends RitualEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String fakeoutPattern;
    private final int crowd;

    public RitualRevealEvent(RitualView ritual, String fakeoutPattern, int crowd) {
        super(ritual);
        this.fakeoutPattern = fakeoutPattern;
        this.crowd = crowd;
    }

    /** The fakeout that preceded this reveal ({@code false-loss}, {@code escalation}) or {@code null}. */
    public String fakeoutPattern() {
        return fakeoutPattern;
    }

    /** Onlookers (not counting the offering player) at the moment of the reveal. */
    public int crowd() {
        return crowd;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
