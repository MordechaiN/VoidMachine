package com.voidmachine.core.timeline;

import java.util.List;
import java.util.Optional;

/**
 * Immutable schedule of one ritual: consecutive segments starting at tick 0.
 */
public final class RitualTimeline {

    public record Segment(Phase phase, int start, int duration) {
        public int end() {
            return start + duration;
        }
    }

    private final List<Segment> segments;
    private final int total;

    RitualTimeline(List<Segment> segments) {
        this.segments = List.copyOf(segments);
        this.total = segments.isEmpty() ? 0 : segments.getLast().end();
    }

    public List<Segment> segments() {
        return segments;
    }

    public int totalTicks() {
        return total;
    }

    /** Segment active at {@code tick}, or empty once the timeline is over. */
    public Optional<Segment> segmentAt(int tick) {
        for (Segment s : segments) {
            if (tick >= s.start() && tick < s.end()) return Optional.of(s);
        }
        return Optional.empty();
    }

    public Optional<Segment> segment(Phase phase) {
        for (Segment s : segments) if (s.phase() == phase) return Optional.of(s);
        return Optional.empty();
    }

    /** First tick of the true reveal (where the reward is paid). */
    public int revealTick() {
        return segment(Phase.REVEAL).map(Segment::start).orElseThrow();
    }

    /** Segments that may not depend on the verdict. */
    public List<Segment> verdictBlindSegments() {
        return segments.stream().filter(s -> !s.phase().verdictAware()).toList();
    }
}
