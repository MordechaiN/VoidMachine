package com.voidmachine.paper.presentation;

import com.voidmachine.core.budget.EffectBudget;
import com.voidmachine.core.config.Settings;
import com.voidmachine.core.outcome.RandomSource;
import com.voidmachine.core.script.Ritualbook;
import com.voidmachine.core.script.Script;
import com.voidmachine.core.script.Theme;
import com.voidmachine.core.spectator.SpectatorTier;
import com.voidmachine.paper.diag.TickProfiler;
import com.voidmachine.paper.i18n.Messages;
import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.machine.MachineRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Idle machines breathe: faint particles and sounds, and rare "void events". Work is bounded:
 * machines are scanned for nearby players once per second, and only machines with someone nearby are
 * animated. Uses its own small budget so idle effects can never starve a ritual.
 */
public final class AmbientService {

    private static final UUID NO_OWNER = new UUID(0, 0);
    private static final int VOID_EVENT_TICKS = 60;

    private final Plugin plugin;
    private final MachineRegistry machines;
    private final Supplier<Settings> settings;
    private final Supplier<Ritualbook> book;
    private final Messages messages;
    private final CueGuard cues;
    private final EffectBudget budget = new EffectBudget(200, 80);
    private final TickProfiler profiler = new TickProfiler(1200);
    private final RandomSource rng = RandomSource.seeded(System.nanoTime());
    private final Map<String, Audience> audiences = new HashMap<>();
    private final Map<String, Set<UUID>> chargeViewers = new HashMap<>();
    private List<Machine> animated = List.of();
    private BukkitTask task;
    private long tick;
    private long nextVoidEventTick = -1;
    private Machine voidEventMachine;
    private long voidEventStart;

    public AmbientService(Plugin plugin, MachineRegistry machines, Supplier<Settings> settings, Supplier<Ritualbook> book,
                          Messages messages, CueGuard cues) {
        this.plugin = plugin;
        this.cues = cues;
        this.machines = machines;
        this.settings = settings;
        this.book = book;
        this.messages = messages;
    }

    public void start() {
        stop();
        Settings s = settings.get();
        if (s == null || !s.ambient().enabled()) return;
        scheduleVoidEvent(s);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        for (Machine m : machines.all()) restoreCharge(m);
        audiences.clear();
        animated = List.of();
        voidEventMachine = null;
    }

    private void tick() {
        long start = System.nanoTime();
        Settings s = settings.get();
        Ritualbook rb = book.get();
        if (s == null || rb == null) return;
        tick++;
        if (tick % 20 == 0) rescan(s);
        budget.beginTick(Math.max(1, animated.size()));
        for (Machine m : animated) {
            if (m.isBusy() || !m.isChunkLoaded()) continue;
            Settings.Profile profile = s.profiles().get(m.record().profile());
            if (profile == null) continue;
            Theme theme = rb.themes().get(profile.theme());
            if (theme == null) continue;
            Audience audience = audiences.get(m.id());
            if (audience == null) continue;
            Location block = m.location();
            Script idle = theme.ambientIdle();
            if (!idle.isEmpty()) {
                int stagger = Math.floorMod(m.id().hashCode(), Math.max(1, idle.every() == 0 ? 20 : idle.every()));
                idle.due((int) ((tick + stagger) % Integer.MAX_VALUE), cue -> cues.play(cue, 0, ctx(m, audience, block, s)));
            }
            if (m == voidEventMachine) {
                long local = tick - voidEventStart;
                if (local == 0) {
                    for (Player p : audience.players(SpectatorTier.Audience.NEAR)) messages.actionBar(p, "ambient.restless");
                }
                if (local < VOID_EVENT_TICKS) {
                    theme.voidEvent().due((int) local, cue -> cues.play(cue, local / (double) VOID_EVENT_TICKS, ctx(m, audience, block, s)));
                } else {
                    restoreCharge(m);
                    voidEventMachine = null;
                }
            }
        }
        if (s.ambient().voidEvents() && nextVoidEventTick >= 0 && tick >= nextVoidEventTick && voidEventMachine == null) {
            List<Machine> eligible = new ArrayList<>();
            for (Machine m : animated) if (!m.isBusy() && m.isChunkLoaded()) eligible.add(m);
            if (!eligible.isEmpty()) {
                voidEventMachine = eligible.get(rng.nextInt(eligible.size()));
                voidEventStart = tick + 1;
            }
            scheduleVoidEvent(s);
        }
        profiler.record(System.nanoTime() - start);
    }

    /** Once per second: which idle machines have someone nearby. */
    private void rescan(Settings s) {
        List<Machine> next = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        double r = s.ambient().radius();
        SpectatorTier.Radii radii = new SpectatorTier.Radii(Math.min(6, r / 3), Math.min(r - 1, Math.max(7, r * 0.6)), r);
        for (Machine m : machines.all()) {
            if (!m.record().enabled() || m.isBusy() || !m.isChunkLoaded()) {
                if (!m.isBusy()) restoreCharge(m);
                continue;
            }
            Location center = m.center();
            if (center.getWorld().getNearbyPlayers(center, r).isEmpty()) {
                restoreCharge(m);
                continue;
            }
            Audience a = audiences.computeIfAbsent(m.id(), k -> new Audience(NO_OWNER));
            a.refresh(center, radii, 32);
            next.add(m);
            seen.add(m.id());
        }
        audiences.keySet().retainAll(seen);
        animated = List.copyOf(next);
    }

    private void scheduleVoidEvent(Settings s) {
        int min = s.ambient().voidEventMinMinutes() * 1200;
        int max = s.ambient().voidEventMaxMinutes() * 1200;
        nextVoidEventTick = tick + min + (max > min ? rng.nextInt(max - min + 1) : 0);
    }

    private void restoreCharge(Machine m) {
        Set<UUID> viewers = chargeViewers.remove(m.id());
        if (viewers != null && !viewers.isEmpty() && m.location() != null) MachineVisuals.restore(m.location(), viewers);
    }

    private CuePlayer.Context ctx(Machine m, Audience audience, Location block, Settings s) {
        EffectBudget.Allowance allowance = budget.allowance();
        Set<UUID> viewers = chargeViewers.computeIfAbsent(m.id(), k -> new HashSet<>());
        double intensity = s.spectators().crowd().intensity(audience.onlookers());
        return new CuePlayer.Context() {
            @Override
            public Location machineBlock() {
                return block;
            }

            @Override
            public Audience audience() {
                return audience;
            }

            @Override
            public EffectBudget.Allowance allowance() {
                return allowance;
            }

            @Override
            public RandomSource rng() {
                return rng;
            }

            @Override
            public double intensity() {
                return intensity;
            }

            @Override
            public ItemStack offering() {
                return new ItemStack(org.bukkit.Material.ENDER_EYE);
            }

            @Override
            public ItemStack reward() {
                return offering();
            }

            @Override
            public DisplayService.Handle display() {
                return null;
            }

            @Override
            public Set<UUID> chargeViewers() {
                return viewers;
            }
        };
    }

    /** Admin preview / testing: start a void event on a machine now. */
    public boolean triggerVoidEvent(Machine m) {
        if (task == null || m.isBusy() || !m.isChunkLoaded()) return false;
        if (!animated.contains(m)) {
            List<Machine> with = new ArrayList<>(animated);
            with.add(m);
            animated = List.copyOf(with);
            Audience a = audiences.computeIfAbsent(m.id(), k -> new Audience(NO_OWNER));
            Settings s = settings.get();
            double r = s.ambient().radius();
            a.refresh(m.center(), new SpectatorTier.Radii(Math.min(6, r / 3), Math.min(r - 1, Math.max(7, r * 0.6)), r), 32);
        }
        voidEventMachine = m;
        voidEventStart = tick + 1;
        return true;
    }

    public TickProfiler profiler() {
        return profiler;
    }

    public int animatedMachines() {
        return animated.size();
    }
}
