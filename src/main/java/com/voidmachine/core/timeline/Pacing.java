package com.voidmachine.core.timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Timing profile of a ritual, in ticks. Ranges ({@code min..max}) are drawn per ritual from the
 * presentation seed so rituals do not feel mechanical.
 */
public record Pacing(
        String id,
        int awaken,
        int capture,
        int stirMin, int stirMax,
        int ramp,
        int instabilityMin, int instabilityMax,
        int silenceMin, int silenceMax,
        int falseRevealHold,
        int destabilize,
        int revealHold,
        int aftermath
) {

    /** Validation problems (empty when valid). */
    public List<String> problems(int maxRitualTicks, int longestJackpotHold) {
        List<String> out = new ArrayList<>();
        nonNegative(out, "awaken", awaken);
        nonNegative(out, "capture", capture);
        range(out, "stir", stirMin, stirMax);
        nonNegative(out, "ramp", ramp);
        if (ramp < 10) out.add("ramp must be at least 10 ticks so the bar can visibly fill (found " + ramp + ")");
        range(out, "instability", instabilityMin, instabilityMax);
        range(out, "silence", silenceMin, silenceMax);
        nonNegative(out, "false-reveal", falseRevealHold);
        nonNegative(out, "destabilize", destabilize);
        nonNegative(out, "reveal", revealHold);
        if (revealHold < 20) out.add("reveal must be at least 20 ticks so the verdict is readable (found " + revealHold + ")");
        nonNegative(out, "aftermath", aftermath);
        int worst = worstCaseTicks(longestJackpotHold);
        if (worst > maxRitualTicks) {
            out.add("the longest possible ritual is " + worst + " ticks, above limits.max-ritual-duration-ticks ("
                    + maxRitualTicks + "); shorten the pacing or raise the limit");
        }
        return out;
    }

    /** Longest timeline this pacing can produce (fakeout + longest jackpot reveal). */
    public int worstCaseTicks(int longestJackpotHold) {
        return awaken + capture + stirMax + ramp + instabilityMax + silenceMax
                + falseRevealHold + destabilize + Math.max(revealHold, longestJackpotHold) + aftermath;
    }

    private static void nonNegative(List<String> out, String name, int v) {
        if (v < 0) out.add(name + " cannot be negative (found " + v + ")");
    }

    private static void range(List<String> out, String name, int min, int max) {
        if (min < 0) out.add(name + " minimum cannot be negative (found " + min + ")");
        if (max < min) out.add(name + " maximum (" + max + ") is below its minimum (" + min + ")");
    }
}
