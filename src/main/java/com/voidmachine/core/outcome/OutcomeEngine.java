package com.voidmachine.core.outcome;

import java.util.Objects;

/**
 * Decides verdicts. This is the only place in VoidMachine where an outcome is chosen.
 */
public final class OutcomeEngine {

    private final RandomSource random;

    public OutcomeEngine(RandomSource random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Rolls one verdict.
     *
     * @param table       the machine profile's table
     * @param inputAmount items offered (&gt;= 1)
     * @param maxReward   hard cap on items returned by one ritual (&gt;= inputAmount, validated by config)
     */
    public Verdict decide(OutcomeTable table, int inputAmount, int maxReward) {
        OutcomeDefinition chosen = table.select(random);
        return verdictFor(chosen, inputAmount, maxReward);
    }

    /**
     * Computes the verdict for an already-chosen outcome. Public so admin previews and tests can
     * build verdicts without randomness; it performs no selection.
     */
    public static Verdict verdictFor(OutcomeDefinition outcome, int inputAmount, int maxReward) {
        if (inputAmount < 1) throw new IllegalArgumentException("inputAmount must be >= 1");
        if (maxReward < 0) throw new IllegalArgumentException("maxReward must be >= 0");
        long raw = outcome.multiplier().apply(inputAmount);
        boolean capped = raw > maxReward;
        int reward = (int) Math.min(raw, maxReward);
        return new Verdict(outcome.id(), outcome.tier(), outcome.multiplier(), inputAmount, reward, capped);
    }

    public RandomSource random() {
        return random;
    }
}
