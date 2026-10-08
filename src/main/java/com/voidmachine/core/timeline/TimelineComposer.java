package com.voidmachine.core.timeline;

import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.outcome.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the schedule of a ritual.
 *
 * <p><b>No-leak guarantee:</b> the verdict-blind phases are computed first, from the pacing and the
 * presentation seed only. The verdict, fakeout and jackpot choreography influence nothing before the
 * reveal, so a ritual that ends in a jackpot is indistinguishable from one that ends in nothing until
 * the moment of truth. {@code TimelineComposerTest} proves this for every tier.</p>
 */
public final class TimelineComposer {

    private TimelineComposer() {
    }

    /**
     * Builds the ritual's timeline; phases before the reveal depend only on the pacing and the seed.
     *
     * @param revealHold   ticks the true reveal is held (a jackpot variant may hold longer)
     */
    public static RitualTimeline compose(Pacing pacing, long presentationSeed, OutcomeTier tier,
                                         FakeoutPlan fakeout, int revealHold) {
        RandomSource rnd = RandomSource.seeded(presentationSeed);
        // Draw every random duration of the verdict-blind part first, in a fixed order.
        int stir = between(rnd, pacing.stirMin(), pacing.stirMax());
        int instability = between(rnd, pacing.instabilityMin(), pacing.instabilityMax());
        int silence = between(rnd, pacing.silenceMin(), pacing.silenceMax());

        List<RitualTimeline.Segment> out = new ArrayList<>();
        int t = 0;
        t = add(out, Phase.AWAKEN, t, pacing.awaken());
        t = add(out, Phase.CAPTURE, t, pacing.capture());
        t = add(out, Phase.STIR, t, stir);
        t = add(out, Phase.RAMP, t, pacing.ramp());
        t = add(out, Phase.INSTABILITY, t, instability);
        t = add(out, Phase.SILENCE, t, silence);

        // Verdict-aware part.
        if (fakeout != null && fakeout.pattern().eligibleFor(tier)) {
            t = add(out, Phase.FALSE_REVEAL, t, pacing.falseRevealHold());
            t = add(out, Phase.DESTABILIZE, t, pacing.destabilize());
        }
        t = add(out, Phase.REVEAL, t, Math.max(1, revealHold));
        add(out, Phase.AFTERMATH, t, pacing.aftermath());
        return new RitualTimeline(out);
    }

    private static int add(List<RitualTimeline.Segment> out, Phase phase, int start, int duration) {
        if (duration <= 0 && phase != Phase.REVEAL) return start;
        out.add(new RitualTimeline.Segment(phase, start, duration));
        return start + duration;
    }

    private static int between(RandomSource rnd, int min, int max) {
        if (max <= min) return min;
        return min + rnd.nextInt(max - min + 1);
    }
}
