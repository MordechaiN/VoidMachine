package com.voidmachine.core.health;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks what is wrong with the plugin right now and whether new rituals may start.
 *
 * <p>Fail-closed rule: if VoidMachine cannot durably record a ritual (storage) or does not know what
 * it is supposed to do (configuration), no offering is accepted. Everything else (a corrupt record
 * of one player, an audit log that cannot be written) degrades but does not stop the machines.</p>
 *
 * <p>Thread-safe: problems may be raised from the journal I/O thread.</p>
 */
public final class HealthMonitor {

    public enum State {
        HEALTHY(true),
        /** Something non-critical is wrong; rituals continue. */
        DEGRADED(true),
        /** Some records need an admin decision; rituals continue for everyone else. */
        RECOVERY_REQUIRED(true),
        /** Configuration failed validation; no new rituals. */
        CONFIG_INVALID(false),
        /** The journal cannot write durably; no new rituals. */
        STORAGE_ERROR(false);

        private final boolean acceptsRituals;

        State(boolean acceptsRituals) {
            this.acceptsRituals = acceptsRituals;
        }

        public boolean acceptsRituals() {
            return acceptsRituals;
        }
    }

    /** Independent problem sources; each can be raised and cleared on its own. */
    public enum Source {
        STORAGE(State.STORAGE_ERROR),
        CONFIG(State.CONFIG_INVALID),
        RECOVERY(State.RECOVERY_REQUIRED),
        QUARANTINE(State.RECOVERY_REQUIRED),
        AUDIT(State.DEGRADED),
        STATS(State.DEGRADED),
        PRESENTATION(State.DEGRADED);

        private final State severity;

        Source(State severity) {
            this.severity = severity;
        }

        public State severity() {
            return severity;
        }
    }

    public record Problem(Source source, String detail, Instant since) {
    }

    private final Clock clock;
    private final Map<Source, Problem> problems = new EnumMap<>(Source.class);
    private final List<String> recentErrors = new ArrayList<>();

    public HealthMonitor(Clock clock) {
        this.clock = clock;
    }

    public synchronized void raise(Source source, String detail) {
        Problem existing = problems.get(source);
        Instant since = existing != null ? existing.since() : clock.instant();
        problems.put(source, new Problem(source, detail, since));
        recordError(source + ": " + detail);
    }

    public synchronized boolean clear(Source source) {
        return problems.remove(source) != null;
    }

    public synchronized State state() {
        State worst = State.HEALTHY;
        for (Problem p : problems.values()) {
            if (p.source().severity().ordinal() > worst.ordinal()) worst = p.source().severity();
        }
        return worst;
    }

    public boolean acceptsRituals() {
        return state().acceptsRituals();
    }

    public synchronized List<Problem> problems() {
        return List.copyOf(problems.values());
    }

    public synchronized Optional<Problem> problem(Source source) {
        return Optional.ofNullable(problems.get(source));
    }

    public synchronized void recordError(String message) {
        recentErrors.add(clock.instant() + " " + message);
        if (recentErrors.size() > 20) recentErrors.removeFirst();
    }

    public synchronized List<String> recentErrors() {
        return Collections.unmodifiableList(new ArrayList<>(recentErrors));
    }
}
