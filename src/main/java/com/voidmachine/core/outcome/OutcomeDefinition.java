package com.voidmachine.core.outcome;

import com.voidmachine.core.util.Ids;

import java.util.Objects;

/**
 * A named outcome the Void can choose: how many items return ({@link #multiplier()}) and how the
 * reveal is staged ({@link #tier()}).
 */
public record OutcomeDefinition(String id, Multiplier multiplier, OutcomeTier tier) {

    public OutcomeDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(tier, "tier");
        if (!Ids.isValid(id)) {
            throw new IllegalArgumentException("invalid outcome id '" + id + "' (" + Ids.RULE + ")");
        }
        tier.incompatibilityWith(multiplier).ifPresent(reason -> {
            throw new IllegalArgumentException("outcome '" + id + "': " + reason);
        });
    }
}
