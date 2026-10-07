package com.voidmachine.core.timeline;

import com.voidmachine.core.outcome.OutcomeTier;

import java.util.Locale;

/**
 * A planned piece of theatre: before the truth, the Void pretends a <em>lesser</em> verdict.
 *
 * <p>Design rule: fakeouts only ever end better than they pretended. The Void may scare a winner;
 * it never shows a win and then takes it away. The transaction is unaffected either way — the
 * verdict was sealed before the first frame.</p>
 */
public record FakeoutPlan(Pattern pattern, String decoyOutcomeId, OutcomeTier decoyTier) {

    public enum Pattern {
        /** Pretends the offering was consumed, then snaps to a win. */
        FALSE_LOSS,
        /** Pretends a modest result, then "the Void is not finished" and escalates. */
        ESCALATION;

        public String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        public boolean eligibleFor(OutcomeTier truth) {
            return switch (this) {
                case FALSE_LOSS -> truth.isWin();
                case ESCALATION -> truth == OutcomeTier.GREAT || truth == OutcomeTier.JACKPOT;
            };
        }
    }
}
