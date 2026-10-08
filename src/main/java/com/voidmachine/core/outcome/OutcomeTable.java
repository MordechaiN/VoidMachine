package com.voidmachine.core.outcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A validated, immutable weighted table of outcomes for one machine profile.
 *
 * <p>Weights are converted to integer units (millionths) at construction, so selection is exact
 * integer arithmetic: no floating-point edge where {@code r == total}, no NaN, no drift.</p>
 */
public final class OutcomeTable {

    /** Resolution of weights: 1 unit = 0.000001. */
    static final long UNITS_PER_WEIGHT = 1_000_000L;
    /** Largest accepted single weight / sum of weights. Keeps unit totals far below 2^53. */
    public static final double MAX_TOTAL_WEIGHT = 1_000_000_000d;
    /** Smallest positive weight that is distinguishable from zero. */
    public static final double MIN_POSITIVE_WEIGHT = 0.000001d;

    private final String profileId;
    private final List<Entry> entries;
    private final long totalUnits;

    private OutcomeTable(String profileId, List<Entry> entries, long totalUnits) {
        this.profileId = profileId;
        this.entries = entries;
        this.totalUnits = totalUnits;
    }

    /**
     * Builds a table. Entries with weight 0 are kept (they are reported as 0%) but can never be chosen.
     *
     * @throws IllegalArgumentException with a precise reason for negative, non-finite, too-small or
     *                                  too-large weights, or if every weight is zero
     */
    public static OutcomeTable of(String profileId, Map<OutcomeDefinition, Double> weights) {
        Objects.requireNonNull(profileId, "profileId");
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("profile '" + profileId + "' has no outcomes");
        }
        List<Entry> list = new ArrayList<>(weights.size());
        long total = 0;
        double sum = 0;
        for (Map.Entry<OutcomeDefinition, Double> e : weights.entrySet()) {
            OutcomeDefinition def = Objects.requireNonNull(e.getKey(), "outcome");
            double w = Objects.requireNonNull(e.getValue(), "weight");
            if (Double.isNaN(w) || Double.isInfinite(w)) {
                throw new IllegalArgumentException("weight of '" + def.id() + "' is not a finite number");
            }
            if (w < 0) {
                throw new IllegalArgumentException("weight of '" + def.id() + "' is negative (" + w + ")");
            }
            if (w > 0 && w < MIN_POSITIVE_WEIGHT) {
                throw new IllegalArgumentException("weight of '" + def.id() + "' is below the minimum of "
                        + MIN_POSITIVE_WEIGHT + " (" + w + ")");
            }
            sum += w;
            if (sum > MAX_TOTAL_WEIGHT) {
                throw new IllegalArgumentException("sum of weights exceeds " + (long) MAX_TOTAL_WEIGHT);
            }
            long units = Math.round(w * UNITS_PER_WEIGHT);
            list.add(new Entry(def, w, units));
            total += units;
        }
        if (total <= 0) {
            throw new IllegalArgumentException("profile '" + profileId + "': every outcome weight is 0, nothing can be chosen");
        }
        return new OutcomeTable(profileId, Collections.unmodifiableList(list), total);
    }

    /** Selects exactly one outcome. Probability of each entry is {@code units / totalUnits}. */
    public OutcomeDefinition select(RandomSource random) {
        long r = random.nextLong(totalUnits);
        long cursor = 0;
        for (Entry entry : entries) {
            cursor += entry.units;
            if (r < cursor) return entry.definition;
        }
        // Unreachable: r < totalUnits == sum(units). Kept as a hard failure rather than a silent default.
        throw new IllegalStateException("weighted selection overflowed (r=" + r + ", total=" + totalUnits + ")");
    }

    public String profileId() {
        return profileId;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Exact probability (0..1) of each outcome id, in table order. */
    public Map<String, Double> probabilities() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Entry e : entries) out.put(e.definition.id(), (double) e.units / totalUnits);
        return out;
    }

    /** Expected items returned per item offered (before reward caps). Below 1.0 means the machine is a net item sink. */
    public double expectedReturn() {
        double ev = 0;
        for (Entry e : entries) {
            Multiplier m = e.definition.multiplier();
            ev += ((double) e.units / totalUnits) * ((double) m.numerator() / m.denominator());
        }
        return ev;
    }

    public record Entry(OutcomeDefinition definition, double weight, long units) {
    }
}
