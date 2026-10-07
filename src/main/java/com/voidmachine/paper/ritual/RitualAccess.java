package com.voidmachine.paper.ritual;

import com.voidmachine.core.config.Settings;
import com.voidmachine.core.outcome.RandomSource;
import com.voidmachine.core.script.Theme;
import com.voidmachine.core.timeline.FakeoutPlan;
import com.voidmachine.core.timeline.Pacing;
import com.voidmachine.core.timeline.Phase;
import com.voidmachine.core.timeline.RitualTimeline;
import com.voidmachine.paper.presentation.Audience;
import com.voidmachine.paper.presentation.DisplayService;
import com.voidmachine.paper.presentation.RitualBars;
import org.bukkit.Location;

import java.util.Set;
import java.util.UUID;

/**
 * The presentation layer's window into a ritual: it may read and write presentation state, and read
 * (never write) the truth. Keeping this separate makes "animation never controls truth" a compile-time
 * property: nothing outside {@code paper.ritual} can change a verdict, record or payout.
 */
public final class RitualAccess {

    private final ActiveRitual r;

    private RitualAccess(ActiveRitual r) {
        this.r = r;
    }

    public static RitualAccess of(ActiveRitual r) {
        return new RitualAccess(r);
    }

    public Settings settings() {
        return r.settings;
    }

    public Settings.Profile profile() {
        return r.profile;
    }

    public Theme theme() {
        return r.theme;
    }

    public Pacing pacing() {
        return r.pacing;
    }

    public long seed() {
        return r.seed;
    }

    public RandomSource presentationRng() {
        return r.presentationRng;
    }

    public RandomSource planningRng() {
        return r.planningRng;
    }

    public String forcedVariant() {
        return r.forcedVariant;
    }

    public FakeoutPlan forcedFakeout() {
        return r.forcedFakeout;
    }

    public void setFakeout(FakeoutPlan plan) {
        r.fakeout = plan;
    }

    public void setJackpotVariant(String variant) {
        r.jackpotVariant = variant;
    }

    public void setRevealHold(int hold) {
        r.revealHold = hold;
    }

    public void setTimeline(RitualTimeline timeline) {
        r.timeline = timeline;
    }

    public void setTick(int tick) {
        r.tick = tick;
    }

    public void setPhase(Phase phase) {
        r.phase = phase;
    }

    public Audience audience() {
        return r.audience;
    }

    public void setAudience(Audience audience) {
        r.audience = audience;
    }

    public RitualBars bars() {
        return r.bars;
    }

    public void setBars(RitualBars bars) {
        r.bars = bars;
    }

    public DisplayService.Handle display() {
        return r.display;
    }

    public void setDisplay(DisplayService.Handle display) {
        r.display = display;
    }

    public Location tetherOrigin() {
        return r.tetherOrigin;
    }

    public void setTetherOrigin(Location origin) {
        r.tetherOrigin = origin;
    }

    public boolean tetherReleased() {
        return r.tetherReleased;
    }

    public void setTetherReleased(boolean released) {
        r.tetherReleased = released;
    }

    public Set<UUID> chargeViewers() {
        return r.chargeViewers;
    }

    public int lastCharge() {
        return r.lastCharge;
    }

    public void setLastCharge(int level) {
        r.lastCharge = level;
    }

    public int crowdAtReveal() {
        return r.crowdAtReveal;
    }

    public void setCrowdAtReveal(int crowd) {
        r.crowdAtReveal = crowd;
    }
}
