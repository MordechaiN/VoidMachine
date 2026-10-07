package com.voidmachine.core.outcome;

import java.util.Locale;
import java.util.Optional;

/**
 * Presentation class of an outcome. The tier selects reveal choreography, announcements and
 * statistics buckets; the {@link Multiplier} alone decides how many items are returned.
 *
 * <p>Tiers are validated against their multiplier so a misconfigured outcome can never play a
 * jackpot fanfare while taking the player's items.</p>
 */
public enum OutcomeTier {
    /** Multiplier exactly 0 — the offering is consumed. */
    LOSS,
    /** 0 &lt; multiplier &lt; 1 — part of the offering returns. */
    PARTIAL,
    /** Multiplier exactly 1 — the offering returns unchanged. */
    NEUTRAL,
    /** Multiplier &gt; 1 — a win. */
    WIN,
    /** Multiplier &gt; 1 — a big win (louder, may be announced). */
    GREAT,
    /** Multiplier &gt; 1 — legendary; uses jackpot variants and server-wide announcements. */
    JACKPOT;

    public boolean isWin() {
        return this == WIN || this == GREAT || this == JACKPOT;
    }

    public boolean isLoss() {
        return this == LOSS || this == PARTIAL;
    }

    /** Ordering used by fakeouts: a fakeout may only pretend a <em>lower</em> rank than the truth. */
    public int rank() {
        return ordinal();
    }

    /** Returns an explanation if this tier cannot be used with {@code multiplier}, otherwise empty. */
    public Optional<String> incompatibilityWith(Multiplier multiplier) {
        int cmp = multiplier.compareToOne();
        boolean ok = switch (this) {
            case LOSS -> multiplier.isZero();
            case PARTIAL -> !multiplier.isZero() && cmp < 0;
            case NEUTRAL -> cmp == 0;
            case WIN, GREAT, JACKPOT -> cmp > 0;
        };
        if (ok) return Optional.empty();
        String expected = switch (this) {
            case LOSS -> "a multiplier of exactly 0";
            case PARTIAL -> "a multiplier between 0 and 1 (exclusive)";
            case NEUTRAL -> "a multiplier of exactly 1";
            case WIN, GREAT, JACKPOT -> "a multiplier greater than 1";
        };
        return Optional.of("tier " + name().toLowerCase(Locale.ROOT) + " requires " + expected
                + ", but the multiplier is " + multiplier.toDisplayString());
    }

    public static Optional<OutcomeTier> parse(String raw) {
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
