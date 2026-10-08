package com.voidmachine.api.event;

import com.voidmachine.api.RitualView;
import org.bukkit.event.Event;

/**
 * Base class of VoidMachine ritual events. All are fired synchronously on the server thread and carry
 * an immutable {@link RitualView}; listeners can observe but never alter a verdict.
 */
public abstract class RitualEvent extends Event {

    private final RitualView ritual;

    protected RitualEvent(RitualView ritual) {
        this.ritual = ritual;
    }

    public RitualView ritual() {
        return ritual;
    }
}
