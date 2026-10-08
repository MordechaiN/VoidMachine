package com.voidmachine.core.timeline;

import com.voidmachine.core.outcome.Multiplier;
import com.voidmachine.core.outcome.OutcomeDefinition;
import com.voidmachine.core.outcome.OutcomeEngine;
import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.outcome.RandomSource;
import com.voidmachine.core.outcome.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineComposerTest {

    static final Pacing STANDARD = new Pacing("standard", 20, 30, 30, 50, 60, 30, 70, 20, 30, 40, 25, 60, 50);

    static final OutcomeDefinition CONSUMED = new OutcomeDefinition("consumed", Multiplier.ZERO, OutcomeTier.LOSS);
    static final OutcomeDefinition RETURNED = new OutcomeDefinition("returned", Multiplier.ONE, OutcomeTier.NEUTRAL);
    static final OutcomeDefinition DOUBLED = new OutcomeDefinition("doubled", new Multiplier(2, 1), OutcomeTier.WIN);
    static final OutcomeDefinition TRIPLED = new OutcomeDefinition("tripled", new Multiplier(3, 1), OutcomeTier.GREAT);
    static final OutcomeDefinition JACKPOT = new OutcomeDefinition("jackpot", new Multiplier(5, 1), OutcomeTier.JACKPOT);
    static final List<OutcomeDefinition> ALL = List.of(CONSUMED, RETURNED, DOUBLED, TRIPLED, JACKPOT);

    @Test
    void verdictBlindPhasesAreIdenticalForEveryVerdict() {
        for (long seed = 0; seed < 2_000; seed++) {
            List<RitualTimeline.Segment> reference = null;
            for (OutcomeTier tier : OutcomeTier.values()) {
                for (FakeoutPlan fake : new FakeoutPlan[]{null,
                        new FakeoutPlan(FakeoutPlan.Pattern.FALSE_LOSS, "consumed", OutcomeTier.LOSS),
                        new FakeoutPlan(FakeoutPlan.Pattern.ESCALATION, "returned", OutcomeTier.NEUTRAL)}) {
                    int hold = tier == OutcomeTier.JACKPOT ? 120 : STANDARD.revealHold();
                    RitualTimeline t = TimelineComposer.compose(STANDARD, seed, tier, fake, hold);
                    if (reference == null) reference = t.verdictBlindSegments();
                    assertEquals(reference, t.verdictBlindSegments(), "seed " + seed + " tier " + tier + " fake " + fake);
                }
            }
        }
    }

    @Test
    void segmentsAreContiguousAndRevealExists() {
        RitualTimeline t = TimelineComposer.compose(STANDARD, 99, OutcomeTier.JACKPOT,
                new FakeoutPlan(FakeoutPlan.Pattern.FALSE_LOSS, "consumed", OutcomeTier.LOSS), 120);
        int expectedStart = 0;
        for (RitualTimeline.Segment s : t.segments()) {
            assertEquals(expectedStart, s.start());
            expectedStart = s.end();
        }
        assertEquals(expectedStart, t.totalTicks());
        assertTrue(t.segment(Phase.FALSE_REVEAL).isPresent());
        assertTrue(t.revealTick() > t.segment(Phase.FALSE_REVEAL).orElseThrow().start());
        assertTrue(t.totalTicks() <= STANDARD.worstCaseTicks(120));
    }

    @Test
    void ineligibleFakeoutIsIgnored() {
        RitualTimeline t = TimelineComposer.compose(STANDARD, 1, OutcomeTier.LOSS,
                new FakeoutPlan(FakeoutPlan.Pattern.FALSE_LOSS, "consumed", OutcomeTier.LOSS), 60);
        assertFalse(t.segment(Phase.FALSE_REVEAL).isPresent());
    }

    @Test
    void pacingValidationExplainsProblems() {
        Pacing bad = new Pacing("bad", -1, 0, 50, 10, 5, 0, 0, 0, 0, 0, 0, 5, 0);
        List<String> problems = bad.problems(100, 0);
        assertTrue(problems.stream().anyMatch(p -> p.contains("awaken cannot be negative")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("stir maximum")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("ramp must be at least")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("reveal must be at least")));
        assertTrue(STANDARD.problems(1000, 120).isEmpty());
        assertFalse(STANDARD.problems(200, 120).isEmpty(), "worst case above the limit must be reported");
    }

    @Test
    void fakeoutsNeverPretendBetterThanTheTruth() {
        RandomSource rnd = RandomSource.seeded(5);
        FakeoutPlanner planner = new FakeoutPlanner(new FakeoutPlanner.Settings(true, 1.0, 0, Duration.ZERO,
                EnumSet.allOf(FakeoutPlan.Pattern.class)));
        int planned = 0;
        for (int i = 0; i < 5_000; i++) {
            OutcomeDefinition truth = ALL.get(rnd.nextInt(ALL.size()));
            Verdict v = OutcomeEngine.verdictFor(truth, 10, 1000);
            Optional<FakeoutPlan> plan = planner.plan(UUID.randomUUID(), "m", v, ALL, rnd, Instant.ofEpochSecond(i));
            if (plan.isEmpty()) {
                assertFalse(truth.tier() == OutcomeTier.WIN && false);
                continue;
            }
            planned++;
            assertTrue(truth.tier().isWin(), "fakeout planned for non-win " + truth.id());
            assertTrue(plan.get().decoyTier().rank() < truth.tier().rank(), "decoy must be worse: " + plan.get() + " vs " + truth.id());
            if (plan.get().pattern() == FakeoutPlan.Pattern.FALSE_LOSS) assertEquals(OutcomeTier.LOSS, plan.get().decoyTier());
        }
        assertTrue(planned > 1000);
    }

    @Test
    void fakeoutsAreRateLimited() {
        RandomSource rnd = RandomSource.seeded(1);
        FakeoutPlanner planner = new FakeoutPlanner(new FakeoutPlanner.Settings(true, 1.0, 3, Duration.ofSeconds(60),
                EnumSet.allOf(FakeoutPlan.Pattern.class)));
        UUID player = UUID.randomUUID();
        Verdict jackpot = OutcomeEngine.verdictFor(JACKPOT, 10, 1000);
        Instant t0 = Instant.ofEpochSecond(1_000);
        assertTrue(planner.plan(player, "m", jackpot, ALL, rnd, t0).isPresent());
        // Global cooldown blocks everyone for 60 s.
        assertTrue(planner.plan(UUID.randomUUID(), "m", jackpot, ALL, rnd, t0.plusSeconds(30)).isEmpty());
        // Same player: needs 3 rituals in between even after the cooldown.
        assertTrue(planner.plan(player, "m", jackpot, ALL, rnd, t0.plusSeconds(61)).isEmpty());
        assertTrue(planner.plan(player, "m", jackpot, ALL, rnd, t0.plusSeconds(62)).isEmpty());
        assertTrue(planner.plan(player, "m", jackpot, ALL, rnd, t0.plusSeconds(63)).isEmpty());
        assertTrue(planner.plan(player, "m", jackpot, ALL, rnd, t0.plusSeconds(64)).isPresent());
    }

    @Test
    void fakeoutPatternsRotatePerMachine() {
        RandomSource rnd = RandomSource.seeded(3);
        FakeoutPlanner planner = new FakeoutPlanner(new FakeoutPlanner.Settings(true, 1.0, 0, Duration.ZERO,
                EnumSet.allOf(FakeoutPlan.Pattern.class)));
        Verdict jackpot = OutcomeEngine.verdictFor(JACKPOT, 10, 1000);
        FakeoutPlan.Pattern last = null;
        for (int i = 0; i < 50; i++) {
            FakeoutPlan p = planner.plan(UUID.randomUUID(), "altar", jackpot, ALL, rnd, Instant.ofEpochSecond(i)).orElseThrow();
            if (last != null) assertFalse(p.pattern() == last, "pattern repeated at " + i);
            last = p.pattern();
        }
    }

    @Test
    void variantPickerAvoidsImmediateRepeats() {
        VariantPicker picker = new VariantPicker();
        RandomSource rnd = RandomSource.seeded(11);
        List<VariantPicker.Option> opts = List.of(new VariantPicker.Option("a", 1), new VariantPicker.Option("b", 1),
                new VariantPicker.Option("c", 0.2));
        String last = null;
        for (int i = 0; i < 500; i++) {
            String v = picker.pick("m", opts, rnd);
            if (last != null) assertFalse(v.equals(last));
            last = v;
        }
        assertEquals("only", picker.pick("m", List.of(new VariantPicker.Option("only", 1)), rnd));
        assertEquals("only", picker.pick("m", List.of(new VariantPicker.Option("only", 1)), rnd));
    }
}
