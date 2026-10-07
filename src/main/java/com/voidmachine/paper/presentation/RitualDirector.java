package com.voidmachine.paper.presentation;

import com.voidmachine.api.event.RitualJackpotEvent;
import com.voidmachine.api.event.RitualRevealEvent;
import com.voidmachine.core.budget.EffectBudget;
import com.voidmachine.core.config.Settings;
import com.voidmachine.core.custody.CustodyEngine;
import com.voidmachine.core.health.HealthMonitor;
import com.voidmachine.core.outcome.OutcomeDefinition;
import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.outcome.RandomSource;
import com.voidmachine.core.script.Script;
import com.voidmachine.core.script.Theme;
import com.voidmachine.core.spectator.SpectatorTier;
import com.voidmachine.core.timeline.FakeoutPlan;
import com.voidmachine.core.timeline.FakeoutPlanner;
import com.voidmachine.core.timeline.Phase;
import com.voidmachine.core.timeline.RitualTimeline;
import com.voidmachine.core.timeline.TimelineComposer;
import com.voidmachine.core.timeline.VariantPicker;
import com.voidmachine.paper.diag.TickProfiler;
import com.voidmachine.paper.i18n.Messages;
import com.voidmachine.paper.item.ItemCodec;
import com.voidmachine.paper.menu.ChamberMenu;
import com.voidmachine.paper.ritual.ActiveRitual;
import com.voidmachine.paper.ritual.RitualAccess;
import com.voidmachine.paper.ritual.RitualService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the presentation of every live ritual from a single tick task. Reads the sealed verdict to
 * stage the reveal; never writes it. If anything in the presentation fails, the ritual is resolved
 * immediately — the player still receives exactly the sealed verdict.
 *
 * <p>The task only runs while at least one ritual is live (zero idle cost). Main-thread confined.</p>
 */
public final class RitualDirector implements RitualService.Presentation {

    private final Plugin plugin;
    private final Messages messages;
    private final DisplayService displays;
    private final EffectBudget budget;
    private final HealthMonitor health;
    private final Logger logger;
    private final CueGuard cues;
    private final TickProfiler profiler = new TickProfiler(1200);
    private final FakeoutPlanner fakeouts;
    private final VariantPicker variants = new VariantPicker();
    private final List<ActiveRitual> running = new ArrayList<>();
    private final Map<UUID, ChamberMenu> chambers = new HashMap<>();
    private RitualService rituals;
    private BukkitTask task;
    private Instant lastServerAnnounce = Instant.EPOCH;

    public RitualDirector(Plugin plugin, Messages messages, Supplier<Settings> settings, DisplayService displays,
                          EffectBudget budget, HealthMonitor health, Logger logger, CueGuard cues) {
        this.plugin = plugin;
        this.cues = cues;
        this.messages = messages;
        this.displays = displays;
        this.budget = budget;
        this.health = health;
        this.logger = logger;
        Settings s = settings.get();
        this.fakeouts = new FakeoutPlanner(fakeoutSettings(s));
    }

    public void bind(RitualService rituals) {
        this.rituals = rituals;
    }

    /** Applies reloaded settings to the stateful planners (running rituals keep their own settings). */
    public void reload(Settings s) {
        fakeouts.update(fakeoutSettings(s));
        if (s != null) budget.configure(s.limits().maxParticlesPerTick(), s.limits().maxPacketsPerTick());
    }

    private static FakeoutPlanner.Settings fakeoutSettings(Settings s) {
        if (s == null) return new FakeoutPlanner.Settings(false, 0, 0, Duration.ZERO, Set.of());
        Settings.Fakeouts f = s.presentation().fakeouts();
        return new FakeoutPlanner.Settings(f.enabled(), f.chance(), f.minRitualsBetweenPerPlayer(),
                Duration.ofSeconds(f.globalCooldownSeconds()), f.patterns());
    }

    // ------------------------------------------------------------------------------------------
    // Presentation lifecycle
    // ------------------------------------------------------------------------------------------

    @Override
    public void start(ActiveRitual r) {
        RitualAccess a = RitualAccess.of(r);
        Settings s = a.settings();
        Theme theme = a.theme();
        // Planning uses its own random stream; cue rolls are untouched by it.
        RandomSource plan = a.planningRng();
        if (r.isPreview()) {
            a.setFakeout(a.forcedFakeout());
        } else if (r.verdict().tier().isWin()) {
            List<OutcomeDefinition> outcomes = a.profile().table().entries().stream()
                    .filter(e -> e.units() > 0).map(e -> e.definition()).toList();
            a.setFakeout(fakeouts.plan(r.playerId(), r.machine().id(), r.verdict(), outcomes, plan, Instant.now()).orElse(null));
        }
        int hold = a.pacing().revealHold();
        if (r.verdict().tier() == OutcomeTier.JACKPOT) {
            String variant = a.forcedVariant() != null && theme.jackpot(a.forcedVariant()).isPresent()
                    ? a.forcedVariant() : variants.pick(r.machine().id(), theme.jackpotOptions(), plan);
            a.setJackpotVariant(variant);
            hold = theme.jackpot(variant).map(Theme.Jackpot::hold).orElse(hold);
        }
        a.setRevealHold(hold);
        a.setTimeline(TimelineComposer.compose(a.pacing(), a.seed(), r.verdict().tier(), r.fakeout(), hold));

        Location block = r.machine().location();
        Audience audience = new Audience(r.playerId());
        audience.refresh(block.clone().add(0.5, 0.5, 0.5), s.spectators().radii(), r.isPreview() ? 0 : s.limits().maxSpectatorsPerRitual());
        a.setAudience(audience);
        RitualBars bars = new RitualBars(messages);
        bars.color(theme.bossbarColor());
        bars.progress(0);
        a.setBars(bars);
        syncBars(r, s);
        if (s.limits().maxDisplayEntitiesPerRitual() > 0) {
            a.setDisplay(displays.open(r.id(), block, r.template(), s.limits().maxDisplayEntitiesPerRitual()));
        }
        Player owner = Bukkit.getPlayer(r.playerId());
        if (owner != null) {
            owner.playSound(owner.getLocation(), "block.anvil.use", 0.6f, 0.55f);
            if (s.presentation().tetherPlayer()) a.setTetherOrigin(owner.getLocation());
            if (s.presentation().ritualChamberGui()) {
                ChamberMenu chamber = new ChamberMenu(messages, owner);
                chambers.put(r.id(), chamber);
                chamber.open(owner);
            }
        }
        narrate(r, "ritual.narrate.offer", true, offerResolvers(r));
        if (s.spectators().crowd().isCrowd(audience.onlookers())) narrate(r, "ritual.narrate.crowd", false);
        running.add(r);
        ensureTask();
    }

    @Override
    public void cleanup(ActiveRitual r) {
        RitualAccess a = RitualAccess.of(r);
        running.remove(r);
        if (a.bars() != null) a.bars().hideAll();
        if (a.display() != null) a.display().close();
        Location block = r.machine().location();
        if (block != null) MachineVisuals.restore(block, a.chargeViewers());
        ChamberMenu chamber = chambers.remove(r.id());
        if (chamber != null) chamber.close(Bukkit.getPlayer(r.playerId()));
        a.setTetherReleased(true);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        running.clear();
        chambers.clear();
        displays.removeAll();
    }

    private void ensureTask() {
        if (task != null || running.isEmpty()) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ------------------------------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------------------------------

    private void tick() {
        long start = System.nanoTime();
        budget.beginTick(running.size());
        for (ActiveRitual r : List.copyOf(running)) {
            try {
                step(r);
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Presentation of ritual " + r.id() + " failed; resolving it now (the verdict is unaffected)", t);
                health.raise(HealthMonitor.Source.PRESENTATION, "a ritual presentation failed: " + t);
                running.remove(r);
                rituals.resolveNow(r, "presentation-error");
            }
        }
        if (running.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
        profiler.record(System.nanoTime() - start);
    }

    private void step(ActiveRitual r) {
        RitualAccess a = RitualAccess.of(r);
        Settings s = a.settings();
        int t = r.tick();
        a.setTick(t + 1);
        if (t > s.limits().maxRitualDurationTicks()) {
            logger.warning("Ritual " + r.id() + " exceeded limits.max-ritual-duration-ticks; resolving it now");
            rituals.finish(r, "watchdog");
            return;
        }
        if (!r.machine().isChunkLoaded()) {
            rituals.resolveNow(r, "machine-unloaded");
            return;
        }
        Location block = r.machine().location();
        if (t % s.spectators().refreshTicks() == 0) {
            a.audience().refresh(block.clone().add(0.5, 0.5, 0.5), s.spectators().radii(),
                    r.isPreview() ? 0 : s.limits().maxSpectatorsPerRitual());
            syncBars(r, s);
        }
        RitualTimeline.Segment seg = r.timeline().segmentAt(t).orElse(null);
        if (seg == null) {
            rituals.finish(r, "complete");
            return;
        }
        if (seg.phase() != r.phase()) {
            a.setPhase(seg.phase());
            enter(r, a, seg.phase());
            if (r.state() == ActiveRitual.State.DONE) return;
        }
        int local = t - seg.start();
        double progress = seg.duration() <= 1 ? 1.0 : (double) local / (seg.duration() - 1);
        continuous(r, a, seg.phase(), progress, t);
        Script script = scriptFor(r, a, seg.phase());
        if (!script.isEmpty()) {
            double intensity = s.spectators().crowd().intensity(a.audience().onlookers());
            Ctx ctx = new Ctx(a, block, budget.allowance(), intensity);
            script.due(local, cue -> cues.play(cue, progress, ctx));
        }
    }

    private Script scriptFor(ActiveRitual r, RitualAccess a, Phase phase) {
        Theme theme = a.theme();
        return switch (phase) {
            case FALSE_REVEAL -> r.fakeout() == null ? Script.EMPTY : theme.reveal(r.fakeout().decoyTier());
            case REVEAL -> {
                if (r.verdict().tier() == OutcomeTier.JACKPOT && r.jackpotVariant() != null) {
                    yield theme.jackpot(r.jackpotVariant()).map(Theme.Jackpot::script).orElse(Script.EMPTY);
                }
                yield theme.reveal(r.verdict().tier());
            }
            default -> theme.phase(phase);
        };
    }

    /** Boss bar progress and the machine's charge glow follow the phase continuously. */
    private void continuous(ActiveRitual r, RitualAccess a, Phase phase, double progress, int t) {
        RitualBars bars = a.bars();
        switch (phase) {
            case AWAKEN, CAPTURE -> bars.progress((float) (0.05 * progress));
            case STIR -> bars.progress((float) (0.05 + 0.25 * progress));
            case RAMP -> {
                bars.progress((float) (0.30 + 0.60 * progress));
                int level = 1 + (int) Math.min(3, Math.floor(progress * 4));
                if (level != a.lastCharge()) {
                    a.setLastCharge(level);
                    MachineVisuals.showCharge(r.machine().location(), level, a.audience().players(SpectatorTier.Audience.ALL), a.chargeViewers());
                }
            }
            case INSTABILITY -> {
                if (t % 3 == 0) bars.progress((float) (0.86 + a.presentationRng().nextDouble() * 0.11));
            }
            case SILENCE -> bars.progress(0.95f);
            default -> {
            }
        }
    }

    private void enter(ActiveRitual r, RitualAccess a, Phase phase) {
        RitualBars bars = a.bars();
        ChamberMenu chamber = chambers.get(r.id());
        switch (phase) {
            case AWAKEN -> bars.name("ritual.bar.awaken");
            case CAPTURE -> {
                bars.name("ritual.bar.capture", offerResolvers(r));
                if (chamber != null) chamber.status(Material.ENDER_EYE, "chamber.status.capture", offerResolvers(r));
            }
            case STIR -> {
                bars.name("ritual.bar.stir");
                if (chamber != null) chamber.status(Material.ENDER_EYE, "chamber.status.stir");
            }
            case RAMP -> bars.name("ritual.bar.ramp");
            case INSTABILITY -> {
                bars.name("ritual.bar.instability");
                if (chamber != null) chamber.status(Material.CRYING_OBSIDIAN, "chamber.status.instability");
            }
            case SILENCE -> {
                bars.name("ritual.bar.silence");
                bars.color(BossBar.Color.WHITE);
                if (chamber != null) chamber.status(Material.BLACK_STAINED_GLASS_PANE, "chamber.status.silence");
            }
            case FALSE_REVEAL -> {
                FakeoutPlan f = r.fakeout();
                OutcomeTier decoy = f.decoyTier();
                String bar = verdictKey(f.decoyOutcomeId(), decoy, "bar");
                bars.name(bar, verdictResolvers(r, f.decoyOutcomeId(), decoyReward(r, f)));
                bars.color(tierColor(decoy, a.theme().bossbarColor()));
                bars.progress(1f);
                Player owner = Bukkit.getPlayer(r.playerId());
                if (owner != null) {
                    messages.title(owner, verdictKey(f.decoyOutcomeId(), decoy, "title"), verdictKey(f.decoyOutcomeId(), decoy, "subtitle"),
                            5, 40, 10, verdictResolvers(r, f.decoyOutcomeId(), decoyReward(r, f)));
                }
                if (chamber != null) chamber.reveal(decoy, verdictKey(f.decoyOutcomeId(), decoy, "bar"),
                        verdictResolvers(r, f.decoyOutcomeId(), decoyReward(r, f)));
            }
            case DESTABILIZE -> {
                bars.name("ritual.bar.destabilize");
                bars.color(BossBar.Color.RED);
                Player owner = Bukkit.getPlayer(r.playerId());
                if (owner != null) messages.title(owner, null, "ritual.destabilize", 0, 30, 5);
                if (chamber != null) chamber.status(Material.CRYING_OBSIDIAN, "ritual.bar.destabilize");
            }
            case REVEAL -> {
                a.setCrowdAtReveal(a.audience().onlookers());
                rituals.reveal(r);
            }
            case AFTERMATH -> {
            }
        }
    }

    @Override
    public void revealed(ActiveRitual r, CustodyEngine.PayoutResult payout) {
        RitualAccess a = RitualAccess.of(r);
        Settings s = a.settings();
        OutcomeTier tier = r.verdict().tier();
        String outcome = r.verdict().outcomeId();
        TagResolver[] v = verdictResolvers(r, outcome, r.verdict().rewardAmount());
        RitualBars bars = a.bars();
        if (bars != null) {
            bars.progress(1f);
            if (tier == OutcomeTier.JACKPOT && r.jackpotVariant() != null) {
                bars.name("jackpot." + r.jackpotVariant() + ".bar", v);
                bars.color(a.theme().jackpot(r.jackpotVariant()).map(Theme.Jackpot::bossbarColor).orElse("purple"));
            } else {
                bars.name(verdictKey(outcome, tier, "bar"), v);
                bars.color(tierColor(tier, a.theme().bossbarColor()));
            }
        }
        Player owner = Bukkit.getPlayer(r.playerId());
        if (owner != null && owner.isOnline()) {
            if (tier == OutcomeTier.JACKPOT && r.jackpotVariant() != null) {
                messages.title(owner, "jackpot." + r.jackpotVariant() + ".title", "jackpot.subtitle", 5, 80, 20, v);
                messages.send(owner, "jackpot." + r.jackpotVariant() + ".lore", v);
            } else {
                messages.title(owner, verdictKey(outcome, tier, "title"), verdictKey(outcome, tier, "subtitle"), 5, 50, 15, v);
            }
            if (!r.isPreview()) messages.send(owner, verdictKey(outcome, tier, "message"), v);
            if (payout.remaining() > 0 && !owner.isDead()) {
                messages.send(owner, "claims.held", Placeholder.unparsed("amount", Integer.toString(payout.remaining())),
                        Placeholder.component("item", ItemCodec.name(r.template())));
            }
        }
        ChamberMenu chamber = chambers.get(r.id());
        if (chamber != null) chamber.reveal(tier, verdictKey(outcome, tier, "bar"), v);

        // The crowd.
        String narrationKey = "ritual.narrate.verdict." + tier.name().toLowerCase(Locale.ROOT);
        narrate(r, narrationKey, true, v);
        if (tier == OutcomeTier.JACKPOT) {
            for (Audience.Member m : a.audience().members()) {
                if (m.tier() == SpectatorTier.INNER) {
                    messages.title(m.player(), "jackpot.spectator-title", "jackpot.spectator-subtitle", 5, 50, 15, v);
                }
            }
        }
        if (r.isPreview()) return;
        announce(r, a, s, tier, v);
        Bukkit.getPluginManager().callEvent(new RitualRevealEvent(r.view(),
                r.fakeout() == null ? null : r.fakeout().pattern().id(), a.crowdAtReveal()));
        if (tier == OutcomeTier.JACKPOT) Bukkit.getPluginManager().callEvent(new RitualJackpotEvent(r.view(), r.jackpotVariant()));
    }

    private void announce(ActiveRitual r, RitualAccess a, Settings s, OutcomeTier tier, TagResolver[] v) {
        String tierKey = switch (tier) {
            case WIN -> "win";
            case GREAT -> "great";
            case JACKPOT -> "jackpot";
            default -> null;
        };
        if (tierKey == null) return;
        Settings.AnnounceScope scope = s.presentation().announceFor(tierKey);
        if (scope == Settings.AnnounceScope.SERVER) {
            Instant now = Instant.now();
            if (Duration.between(lastServerAnnounce, now).toSeconds() >= s.presentation().serverAnnounceCooldownSeconds()) {
                lastServerAnnounce = now;
                String key = tier == OutcomeTier.JACKPOT && r.jackpotVariant() != null ? "announce.jackpot" : "announce." + tierKey;
                List<Player> listeners = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("voidmachine.notify")) listeners.add(p);
                messages.sendAll(listeners, key, v);
                return;
            }
            scope = Settings.AnnounceScope.AREA;
        }
        if (scope == Settings.AnnounceScope.AREA) {
            List<Player> area = new ArrayList<>();
            for (Audience.Member m : a.audience().members()) {
                if (m.tier() != SpectatorTier.OWNER) area.add(m.player());
            }
            messages.sendAll(area, "announce." + tierKey, v);
        }
    }

    private void narrate(ActiveRitual r, String key, boolean spectatorsOnly, TagResolver... resolvers) {
        RitualAccess a = RitualAccess.of(r);
        if (!a.settings().presentation().narrateToSpectators()) return;
        List<Player> listeners = new ArrayList<>();
        for (Audience.Member m : a.audience().members()) {
            if (m.tier() == SpectatorTier.FAR) continue;
            if (spectatorsOnly && m.tier() == SpectatorTier.OWNER) continue;
            listeners.add(m.player());
        }
        messages.actionBarAll(listeners, key, resolvers);
    }

    private void syncBars(ActiveRitual r, Settings s) {
        RitualAccess a = RitualAccess.of(r);
        List<Player> viewers = new ArrayList<>();
        for (Audience.Member m : a.audience().members()) {
            if (m.tier() == SpectatorTier.OWNER || (s.presentation().spectatorBossbar() && m.tier() == SpectatorTier.INNER)) {
                viewers.add(m.player());
            }
        }
        a.bars().sync(viewers);
    }

    // ------------------------------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------------------------------

    /** {@code verdict.<outcome>.<part>} if defined, else the tier's generic text. */
    private String verdictKey(String outcomeId, OutcomeTier tier, String part) {
        String specific = "verdict." + outcomeId + "." + part;
        return messages.has(specific) ? specific : "verdict.tier." + tier.name().toLowerCase(Locale.ROOT) + "." + part;
    }

    private TagResolver[] offerResolvers(ActiveRitual r) {
        return new TagResolver[]{
                Placeholder.unparsed("player", r.playerName()),
                Placeholder.unparsed("amount", Integer.toString(r.verdict().inputAmount())),
                Placeholder.component("item", ItemCodec.name(r.template()))
        };
    }

    private TagResolver[] verdictResolvers(ActiveRitual r, String outcomeId, int reward) {
        String nameKey = "outcomes." + outcomeId + ".name";
        Component outcomeName = messages.has(nameKey) ? messages.render(messages.defaultVariant(), nameKey) : Component.text(outcomeId);
        return new TagResolver[]{
                Placeholder.unparsed("player", r.playerName()),
                Placeholder.unparsed("amount", Integer.toString(r.verdict().inputAmount())),
                Placeholder.unparsed("input", Integer.toString(r.verdict().inputAmount())),
                Placeholder.unparsed("reward", Integer.toString(reward)),
                Placeholder.unparsed("multiplier", r.verdict().multiplier().toDisplayString()),
                Placeholder.component("item", ItemCodec.name(r.template())),
                Placeholder.component("outcome", outcomeName),
                Placeholder.unparsed("machine", r.machine().record().displayName())
        };
    }

    private static int decoyReward(ActiveRitual r, FakeoutPlan f) {
        return switch (f.decoyTier()) {
            case LOSS -> 0;
            case NEUTRAL -> r.verdict().inputAmount();
            default -> r.verdict().inputAmount() * 2;
        };
    }

    private static String tierColor(OutcomeTier tier, String themeColor) {
        return switch (tier) {
            case LOSS -> "red";
            case PARTIAL -> "yellow";
            case NEUTRAL -> "white";
            case WIN -> "green";
            case GREAT -> "yellow";
            case JACKPOT -> themeColor;
        };
    }

    // ------------------------------------------------------------------------------------------
    // Diagnostics & tether
    // ------------------------------------------------------------------------------------------

    public TickProfiler profiler() {
        return profiler;
    }

    public int running() {
        return running.size();
    }

    public int bossbarViewers() {
        int n = 0;
        for (ActiveRitual r : running) {
            RitualBars b = RitualAccess.of(r).bars();
            if (b != null) n += b.viewerCount();
        }
        return n;
    }

    /** Tether of a player in a ritual, or {@code null}. */
    public Location tetherOf(UUID player) {
        ActiveRitual r = rituals.byPlayer(player);
        if (r == null || r.state() != ActiveRitual.State.LIVE) return null;
        RitualAccess a = RitualAccess.of(r);
        if (a.tetherReleased()) return null;
        return a.tetherOrigin();
    }

    public void releaseTether(UUID player) {
        ActiveRitual r = rituals.byPlayer(player);
        if (r != null) RitualAccess.of(r).setTetherReleased(true);
    }

    // ------------------------------------------------------------------------------------------

    private static final class Ctx implements CuePlayer.Context {
        private final RitualAccess a;
        private final Location block;
        private final EffectBudget.Allowance allowance;
        private final double intensity;

        Ctx(RitualAccess a, Location block, EffectBudget.Allowance allowance, double intensity) {
            this.a = a;
            this.block = block;
            this.allowance = allowance;
            this.intensity = intensity;
        }

        @Override
        public Location machineBlock() {
            return block;
        }

        @Override
        public Audience audience() {
            return a.audience();
        }

        @Override
        public EffectBudget.Allowance allowance() {
            return allowance;
        }

        @Override
        public RandomSource rng() {
            return a.presentationRng();
        }

        @Override
        public double intensity() {
            return intensity;
        }

        @Override
        public ItemStack offering() {
            return a.templateView();
        }

        @Override
        public ItemStack reward() {
            return a.templateView();
        }

        @Override
        public DisplayService.Handle display() {
            return a.display();
        }

        @Override
        public Set<UUID> chargeViewers() {
            return a.chargeViewers();
        }
    }
}
