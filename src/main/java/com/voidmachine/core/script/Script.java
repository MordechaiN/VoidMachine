package com.voidmachine.core.script;

import java.util.List;

/**
 * A list of cues. With {@code every == 0} cues fire once at their offset from the start; otherwise the
 * script loops every {@code every} ticks and offsets are relative to each iteration.
 */
public record Script(int every, List<Cue> cues) {

    public static final Script EMPTY = new Script(0, List.of());

    public Script {
        cues = List.copyOf(cues);
        if (every < 0) throw new IllegalArgumentException("every cannot be negative");
    }

    public boolean isEmpty() {
        return cues.isEmpty();
    }

    /** Cues due at {@code tickInSegment}. */
    public void due(int tickInSegment, java.util.function.Consumer<Cue> sink) {
        int local = every > 0 ? tickInSegment % every : tickInSegment;
        for (Cue c : cues) if (c.at() == local) sink.accept(c);
    }
}
