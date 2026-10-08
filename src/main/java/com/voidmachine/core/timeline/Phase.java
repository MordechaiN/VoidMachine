package com.voidmachine.core.timeline;

/**
 * Beats of a ritual. Every phase before {@link #FALSE_REVEAL}/{@link #REVEAL} is
 * <em>verdict-blind</em>: its timing comes only from the pacing profile and the ritual's presentation
 * seed, so nobody can read the outcome from how the machine behaves before the reveal.
 */
public enum Phase {
    /** The machine wakes: commit sound, flash. */
    AWAKEN(false),
    /** The offering is drawn in. */
    CAPTURE(false),
    /** Slow pulses; the Void considers. */
    STIR(false),
    /** Building energy; the bar fills. */
    RAMP(false),
    /** Flickers, crackles, near-surges. Identical distribution for every verdict. */
    INSTABILITY(false),
    /** Everything stops. */
    SILENCE(false),
    /** Fakeouts only: the Void pretends a lesser verdict. */
    FALSE_REVEAL(true),
    /** Fakeouts only: the pretence breaks apart. */
    DESTABILIZE(true),
    /** The truth. Rewards are paid at the first tick of this phase. */
    REVEAL(true),
    /** Crowd reaction, reward display, fading effects. */
    AFTERMATH(true);

    private final boolean verdictAware;

    Phase(boolean verdictAware) {
        this.verdictAware = verdictAware;
    }

    /** Whether choreography of this phase may depend on the verdict. */
    public boolean verdictAware() {
        return verdictAware;
    }
}
