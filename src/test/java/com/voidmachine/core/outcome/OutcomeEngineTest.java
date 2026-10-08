package com.voidmachine.core.outcome;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutcomeEngineTest {

    static final OutcomeDefinition CONSUMED = new OutcomeDefinition("consumed", Multiplier.ZERO, OutcomeTier.LOSS);
    static final OutcomeDefinition TITHE = new OutcomeDefinition("tithe", new Multiplier(1, 2), OutcomeTier.PARTIAL);
    static final OutcomeDefinition RETURNED = new OutcomeDefinition("returned", Multiplier.ONE, OutcomeTier.NEUTRAL);
    static final OutcomeDefinition DOUBLED = new OutcomeDefinition("doubled", new Multiplier(2, 1), OutcomeTier.WIN);
    static final OutcomeDefinition JACKPOT = new OutcomeDefinition("jackpot", new Multiplier(5, 1), OutcomeTier.JACKPOT);

    static OutcomeTable table(double... w) {
        OutcomeDefinition[] defs = {CONSUMED, TITHE, RETURNED, DOUBLED, JACKPOT};
        Map<OutcomeDefinition, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < w.length; i++) m.put(defs[i], w[i]);
        return OutcomeTable.of("test", m);
    }

    @Test
    void multiplierParsing() {
        assertEquals(new Multiplier(1, 2), Multiplier.parse("0.5"));
        assertEquals(new Multiplier(1, 2), Multiplier.parse("1/2"));
        assertEquals(new Multiplier(5, 1), Multiplier.parse("x5"));
        assertEquals(new Multiplier(5, 4), Multiplier.parse("1.25"));
        assertEquals("1/2", Multiplier.parse("0.50").toStorageString());
        assertEquals("0.5", new Multiplier(2, 4).toDisplayString());
        assertThrows(IllegalArgumentException.class, () -> Multiplier.parse("-1"));
        assertThrows(IllegalArgumentException.class, () -> Multiplier.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> Multiplier.parse("101"));
        assertThrows(IllegalArgumentException.class, () -> Multiplier.parse("1/0"));
        assertThrows(IllegalArgumentException.class, () -> Multiplier.parse("NaN"));
        assertThrows(IllegalArgumentException.class, () -> Multiplier.parse(""));
    }

    @Test
    void rewardRoundsDownAndNeverOverflows() {
        assertEquals(3, new Multiplier(1, 2).apply(7));
        assertEquals(0, new Multiplier(1, 2).apply(1));
        assertEquals((long) Integer.MAX_VALUE * 100, new Multiplier(100, 1).apply(Integer.MAX_VALUE));
    }

    @Test
    void tierMustMatchMultiplier() {
        assertThrows(IllegalArgumentException.class, () -> new OutcomeDefinition("x", Multiplier.ZERO, OutcomeTier.JACKPOT));
        assertThrows(IllegalArgumentException.class, () -> new OutcomeDefinition("x", Multiplier.ONE, OutcomeTier.LOSS));
        assertThrows(IllegalArgumentException.class, () -> new OutcomeDefinition("x", new Multiplier(1, 2), OutcomeTier.NEUTRAL));
        assertThrows(IllegalArgumentException.class, () -> new OutcomeDefinition("Bad Id", Multiplier.ONE, OutcomeTier.NEUTRAL));
    }

    @Test
    void invalidWeightsAreRejectedWithReasons() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> table(-1, 1)).getMessage().contains("negative"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> table(Double.NaN, 1)).getMessage().contains("finite"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> table(Double.POSITIVE_INFINITY)).getMessage().contains("finite"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> table(0, 0, 0)).getMessage().contains("every outcome weight is 0"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> table(1e-9, 1)).getMessage().contains("minimum"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> table(9e8, 9e8)).getMessage().contains("exceeds"));
    }

    @Test
    void zeroWeightOutcomeIsNeverChosen() {
        OutcomeTable t = table(1, 0, 1, 0, 0);
        RandomSource r = RandomSource.seeded(1);
        for (int i = 0; i < 50_000; i++) {
            String id = t.select(r).id();
            assertTrue(id.equals("consumed") || id.equals("returned"), id);
        }
    }

    @Test
    void distributionMatchesWeights() {
        // Chi-square goodness of fit, 4 degrees of freedom. Critical value at p=0.001 is 18.47.
        double[] w = {64, 10, 20, 5, 1};
        OutcomeTable t = table(w);
        RandomSource r = RandomSource.seeded(42);
        int n = 400_000;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) counts.merge(t.select(r).id(), 1, Integer::sum);
        double chi = 0;
        int i = 0;
        for (Map.Entry<String, Double> e : t.probabilities().entrySet()) {
            double expected = e.getValue() * n;
            double observed = counts.getOrDefault(e.getKey(), 0);
            chi += (observed - expected) * (observed - expected) / expected;
            assertEquals(w[i++] / 100.0, e.getValue(), 1e-9);
        }
        assertTrue(chi < 18.47, "chi-square " + chi);
    }

    @Test
    void seededEngineIsDeterministic() {
        OutcomeTable t = table(64, 10, 20, 5, 1);
        OutcomeEngine a = new OutcomeEngine(RandomSource.seeded(7));
        OutcomeEngine b = new OutcomeEngine(RandomSource.seeded(7));
        for (int i = 0; i < 1000; i++) assertEquals(a.decide(t, 64, 320), b.decide(t, 64, 320));
    }

    @Test
    void rewardIsCappedAndFlagged() {
        Verdict v = OutcomeEngine.verdictFor(JACKPOT, 64, 256);
        assertEquals(256, v.rewardAmount());
        assertTrue(v.capped());
        Verdict ok = OutcomeEngine.verdictFor(DOUBLED, 10, 256);
        assertEquals(20, ok.rewardAmount());
        assertFalse(ok.capped());
        assertEquals(10, ok.netGain());
    }

    @Test
    void expectedReturnIsReported() {
        // 74% x0, 20% x1, 4% x2, 1.8% x3 (not present here), so use the five-entry table.
        OutcomeTable t = table(74, 0, 20, 4, 0.2);
        assertEquals((20 + 8 + 1.0) / 98.2, t.expectedReturn(), 1e-9);
    }
}
