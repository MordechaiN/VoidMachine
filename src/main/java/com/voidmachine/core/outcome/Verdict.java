package com.voidmachine.core.outcome;

import java.util.Objects;

/**
 * The sealed, final truth of one ritual. Created exactly once, written to the journal before the
 * offering is taken, and never re-rolled. Presentation reads it; it never changes it.
 *
 * @param outcomeId    id of the chosen outcome
 * @param tier         presentation tier of the outcome
 * @param multiplier   multiplier of the outcome
 * @param inputAmount  items offered
 * @param rewardAmount items the Void owes the player (after caps)
 * @param capped       {@code true} if {@code rewardAmount} was reduced by the reward cap
 */
public record Verdict(String outcomeId, OutcomeTier tier, Multiplier multiplier,
                      int inputAmount, int rewardAmount, boolean capped) {

    public Verdict {
        Objects.requireNonNull(outcomeId, "outcomeId");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(multiplier, "multiplier");
        if (inputAmount < 1) throw new IllegalArgumentException("inputAmount must be >= 1");
        if (rewardAmount < 0) throw new IllegalArgumentException("rewardAmount must be >= 0");
    }

    /** Net change for the player in items: positive = gained, negative = lost. */
    public int netGain() {
        return rewardAmount - inputAmount;
    }
}
