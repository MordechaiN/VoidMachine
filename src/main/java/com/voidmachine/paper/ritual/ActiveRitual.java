package com.voidmachine.paper.ritual;

import com.voidmachine.api.RitualView;
import com.voidmachine.core.config.Settings;
import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.outcome.RandomSource;
import com.voidmachine.core.outcome.Verdict;
import com.voidmachine.core.script.Theme;
import com.voidmachine.core.timeline.FakeoutPlan;
import com.voidmachine.core.timeline.Pacing;
import com.voidmachine.core.timeline.Phase;
import com.voidmachine.core.timeline.RitualTimeline;
import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.presentation.Audience;
import com.voidmachine.paper.presentation.DisplayService;
import com.voidmachine.paper.presentation.RitualBars;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One ritual in flight.
 *
 * <p><b>Truth</b> ({@link #verdict}, {@link #record}) is final from the moment the journal write
 * succeeds. <b>Presentation</b> (timeline, fakeout, bars, displays) only ever reads it.</p>
 *
 * <p>Main-thread confined.</p>
 */
public final class ActiveRitual {

    public enum State {
        /** Journal write in flight. Nothing has been taken. */
        PREPARING,
        /** Offering taken, verdict sealed, presentation running. */
        LIVE,
        /** Verdict revealed and payout attempted. */
        REVEALED,
        /** Cleaned up. */
        DONE,
        /** Ended before anything was taken. */
        ABORTED
    }

    // ---- identity & truth -------------------------------------------------------------------
    final UUID id;
    final UUID playerId;
    final String playerName;
    final Machine machine;
    final Settings settings;
    final Settings.Profile profile;
    final Theme theme;
    final Pacing pacing;
    final ItemStack template;
    final int slot;
    final Verdict verdict;
    final JournalRecord record;
    final long createdAt = System.currentTimeMillis();
    /** Admin preview: presentation only — no record, no payout, no statistics, no events. */
    final boolean preview;
    /** Preview only: force a jackpot variant / fakeout pattern. */
    String forcedVariant;
    com.voidmachine.core.timeline.FakeoutPlan forcedFakeout;

    State state = State.PREPARING;

    // ---- presentation (never influences the truth) -----------------------------------------
    final long seed;
    /** Cue chance rolls and jitter. Drawn identically for every verdict until the reveal. */
    final RandomSource presentationRng;
    /** Fakeout and jackpot-variant choices: a separate stream so they cannot shift the cue rolls. */
    final RandomSource planningRng;
    int lastCharge = -1;
    int lastBarPhaseTick;
    FakeoutPlan fakeout;
    String jackpotVariant;
    int revealHold;
    RitualTimeline timeline;
    int tick;
    Phase phase;
    Audience audience;
    RitualBars bars;
    DisplayService.Handle display;
    final Set<UUID> chargeViewers = new HashSet<>();
    Location tetherOrigin;
    boolean tetherReleased;
    int crowdAtReveal;
    int paidAtReveal;
    int heldAtReveal;
    boolean ownerSawReveal;

    ActiveRitual(UUID id, UUID playerId, String playerName, Machine machine, Settings settings, Settings.Profile profile,
                 Theme theme, Pacing pacing, ItemStack template, int slot, Verdict verdict, JournalRecord record, long seed,
                 boolean preview) {
        this.preview = preview;
        this.id = id;
        this.playerId = playerId;
        this.playerName = playerName;
        this.machine = machine;
        this.settings = settings;
        this.profile = profile;
        this.theme = theme;
        this.pacing = pacing;
        this.template = template.asOne();
        this.slot = slot;
        this.verdict = verdict;
        this.record = record;
        this.seed = seed;
        this.presentationRng = RandomSource.seeded(seed ^ 0x5DEECE66DL);
        this.planningRng = RandomSource.seeded(Long.rotateLeft(seed, 17) ^ 0x9E3779B97F4A7C15L);
    }

    public UUID id() {
        return id;
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public Machine machine() {
        return machine;
    }

    public State state() {
        return state;
    }

    public Verdict verdict() {
        return verdict;
    }

    public JournalRecord record() {
        return record;
    }

    public int tick() {
        return tick;
    }

    public Phase phase() {
        return phase;
    }

    public RitualTimeline timeline() {
        return timeline;
    }

    public FakeoutPlan fakeout() {
        return fakeout;
    }

    public String jackpotVariant() {
        return jackpotVariant;
    }

    public ItemStack template() {
        return template.clone();
    }

    public boolean isPreview() {
        return preview;
    }

    public long ageMillis() {
        return System.currentTimeMillis() - createdAt;
    }

    /** Rewards for this ritual may be paid by the background retry only after its reveal. */
    public boolean revealed() {
        return state == State.REVEALED || state == State.DONE;
    }

    public RitualView view() {
        return new RitualView(id, playerId, playerName, machine.view(), profile.id(), template, verdict.inputAmount(),
                verdict.outcomeId(), verdict.tier(), verdict.multiplier().toDisplayString(), verdict.rewardAmount());
    }
}
