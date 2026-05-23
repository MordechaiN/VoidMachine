/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.animation;

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.config.PluginConfig;
import com.voidmachine.machine.MachineBlock;
import com.voidmachine.machine.MachineRegistry;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives idle ambient effects and attract-mode pulses for all registered
 * VoidMachine blocks.
 *
 * <h3>Goal</h3>
 * Idle machines must feel <em>alive</em> — dangerous, sleeping, watched.
 * Not a passive block. Not a crate.
 *
 * <h3>Effect schedule (fires every {@value #TICK_PERIOD} ticks)</h3>
 * <ul>
 *   <li><b>Smoke drift</b> (every fire / 1 s) — 2 portal void particles rising
 *       from the block surface. Sub-threshold count; no visual chaos.</li>
 *   <li><b>Soul-fire accent</b> (every {@value #FIRE_PERIOD_HUM} fires / 3 s) —
 *       single soul-fire flame above the drift. Gives the "dark, dangerous"
 *       lighting texture without touching any actual light source.</li>
 *   <li><b>Ambient hum</b> (every {@value #FIRE_PERIOD_HUM} fires / 3 s) —
 *       world-space respawn-anchor ambient sound at very low volume (~4 block
 *       range). Nearby players hear a subsonic drone.</li>
 *   <li><b>Attract flash</b> (every {@value #FIRE_PERIOD_ATTRACT} fires / 30 s) —
 *       END_ROD burst and amethyst chime. A brief flicker that catches the eye
 *       of players passing through the room. "What was that?"</li>
 * </ul>
 *
 * <h3>Staggering</h3>
 * Effects are staggered per machine using {@code locationKey().hashCode()} so
 * multiple machines never pulse simultaneously and the server never sees a
 * multi-machine spike on the same tick.
 *
 * <h3>Safety</h3>
 * <ul>
 *   <li>Machines mid-ritual ({@link MachineBlock#isLocked()}) are skipped —
 *       the {@link AnimationPipeline} owns their visual output.</li>
 *   <li>Chunks are <b>never</b> force-loaded for cosmetic effects. Machines in
 *       unloaded chunks are silently skipped until natural chunk load.</li>
 *   <li>All effects respect the global {@code particles.enabled} config flag.</li>
 * </ul>
 */
public final class AmbientEffectScheduler {

    // ── Schedule constants ────────────────────────────────────────────────────
    // Scheduler fires every TICK_PERIOD ticks. Effect periods are in "fires", so:
    //   FIRE_PERIOD_HUM    = 3  →  3 × 20 =  60 ticks =  3 s
    //   FIRE_PERIOD_RARE   = 45 → 45 × 20 = 900 ticks = 45 s
    //   FIRE_PERIOD_ATTRACT = 30 → 30 × 20 = 600 ticks = 30 s

    private static final long TICK_PERIOD         = 20L; // period of the repeating task
    private static final int  FIRE_PERIOD_HUM     = 3;   // fires between hum/soul-fire
    private static final int  FIRE_PERIOD_RARE    = 45;  // fires between deep bass pulses
    private static final int  FIRE_PERIOD_ATTRACT = 30;  // fires between attract pulses

    // ── Sounds (world-space, String keys — forward-compatible with Paper 1.21+) ─
    //
    // Ambient pool (all five moods rotate randomly):
    //   SND_HUM     — baseline void hum     (70% of hum-cycle fires)
    //   SND_PORTAL  — portal resonance echo  (20% of hum-cycle fires)
    //   SND_WHISPER — enderman ambient       (10% of hum-cycle fires — "rare whispers")
    //   SND_BASS    — deep bass pulse        (every FIRE_PERIOD_RARE = 45 s)
    //   SND_CREAK   — metallic creak         (1-in-7 attract fires)
    private static final String SND_HUM      = "block.respawn_anchor.ambient";
    private static final String SND_ATTRACT  = "block.amethyst_block.chime";
    private static final String SND_PORTAL   = "block.portal.ambient";
    private static final String SND_BASS     = "block.beacon.power_select";
    private static final String SND_WHISPER  = "entity.enderman.ambient";
    private static final String SND_CREAK    = "block.iron_door.open";
    // Void event — ender dragon ambient at louder + lower pitch than normal idle.
    private static final String SND_VOID_GROWL = "entity.ender_dragon.ambient";

    // ── Particle counts — keep minimal for Bedrock and high-machine-count servers ─
    private static final int  P_SMOKE_COUNT   = 2;
    private static final int  P_SOUL_COUNT    = 1;
    private static final int  P_ATTRACT_COUNT = 6;

    private final VoidMachinePlugin plugin;
    private final PluginConfig       config;
    private final MachineRegistry    machineRegistry;

    /** The active repeating task, or {@code null} when stopped. */
    @Nullable private BukkitTask task;

    /** Scheduled void-event task, or {@code null} when not scheduled / disabled. */
    @Nullable private BukkitTask voidEventTask;

    /** Per-machine nearby-player count cache. Key: {@link MachineBlock#locationKey()}. */
    private final Map<String, Integer> crowdCache = new HashMap<>();

    /** Monotonic fire counter. Never resets; stagger arithmetic uses modulo. */
    private int fireCount = 0;

    public AmbientEffectScheduler(@NotNull VoidMachinePlugin plugin,
                                  @NotNull PluginConfig config,
                                  @NotNull MachineRegistry machineRegistry) {
        this.plugin          = plugin;
        this.config          = config;
        this.machineRegistry = machineRegistry;
    }

    // =========================================================================
    //  Lifecycle
    // =========================================================================

    /**
     * Start the repeating ambient task.
     * Idempotent — safe to call again after {@link #shutdown()}.
     * A 40-tick initial delay lets the server finish loading before effects start.
     */
    public void start() {
        if (task != null && !task.isCancelled()) return;
        task = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::tick, 40L, TICK_PERIOD);
        // Schedule the first void event if enabled.
        if (config.voidEventsEnabled()) scheduleNextVoidEvent();
    }

    /**
     * Cancel the ambient task. Idempotent. Safe to call from {@code onDisable}.
     */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (voidEventTask != null) {
            voidEventTask.cancel();
            voidEventTask = null;
        }
        crowdCache.clear();
    }

    // =========================================================================
    //  Tick
    // =========================================================================

    private void tick() {
        final int fire = fireCount++;

        // Update nearby-player cache at the configured interval.
        if (config.crowdEnabled()) {
            int checkFires = Math.max(1, config.crowdCheckIntervalTicks() / (int) TICK_PERIOD);
            if (fire % checkFires == 0) updateCrowdCache();
        }

        for (MachineBlock machine : machineRegistry.all()) {
            // Active ritual — animation pipeline owns the effects for this machine.
            if (machine.isLocked()) continue;

            Location loc = machine.location();
            if (loc == null) continue;              // world unloaded

            World world = loc.getWorld();
            if (world == null) continue;

            // Never force-load chunks for cosmetic effects.
            if (!world.isChunkLoaded(machine.x() >> 4, machine.z() >> 4)) continue;

            // Stagger per machine so N machines never fire on the exact same tick.
            // The offset wraps within FIRE_PERIOD_ATTRACT so attract-phase staggering
            // still works correctly.
            int offset     = Math.abs(machine.locationKey().hashCode()) % FIRE_PERIOD_ATTRACT;
            int adjFire    = fire + offset;
            int crowdNearby = config.crowdEnabled()
                    ? crowdCache.getOrDefault(machine.locationKey(), 0) : 0;

            tickMachine(world, loc, adjFire, crowdNearby);
        }
    }

    /**
     * Apply ambient effects for a single idle machine.
     *
     * @param crowdNearby nearby player count from the cache — 0 if crowd
     *                    awareness is disabled or the cache has not yet warmed up.
     */
    private void tickMachine(@NotNull World world,
                             @NotNull Location loc,
                             int adjFire,
                             int crowdNearby) {
        // Emission point: top surface of the block, centred horizontally.
        Location center = loc.clone().add(0.5, 0.85, 0.5);
        boolean crowded = crowdNearby >= config.crowdMinPlayers();

        // ── Smoke drift (every fire = 1 s) ────────────────────────────────────
        if (config.particlesEnabled()) {
            // Portal void swirl — crowd doubles the particle count.
            world.spawnParticle(Particle.PORTAL, center,
                    crowded ? P_SMOKE_COUNT * 2 : P_SMOKE_COUNT,
                    0.30, 0.25, 0.30, 0.04);
        }

        // ── Soul-fire accent + ambient sound (every 3 s) ──────────────────────
        // Sound variant and pitch are randomised each cycle so the machine never
        // feels like a mechanical loop.
        if (adjFire % FIRE_PERIOD_HUM == 0) {
            if (config.particlesEnabled()) {
                world.spawnParticle(Particle.SOUL_FIRE_FLAME,
                        center.clone().add(0, 0.35, 0),
                        crowded ? P_SOUL_COUNT * 3 : P_SOUL_COUNT,
                        0.12, 0.08, 0.12, 0.0);
            }
            // Randomised ambient pool:
            //   70% — baseline void hum      (heard ≈ 4 blocks)
            //   20% — portal resonance echo  (heard ≈ 2-3 blocks, quieter)
            //   10% — enderman ambient       (heard ≈ 1-2 blocks — "rare whisper")
            float humVol = crowded ? 0.38f : 0.25f;
            float pitch  = 0.44f + ThreadLocalRandom.current().nextFloat() * 0.14f; // 0.44–0.58
            int roll = ThreadLocalRandom.current().nextInt(10);
            if (roll < 7) {
                world.playSound(center, SND_HUM, humVol, pitch);
            } else if (roll < 9) {
                world.playSound(center, SND_PORTAL, 0.14f, Math.max(0.38f, pitch - 0.06f));
            } else {
                // Barely audible at close range — a distant, spectral presence.
                world.playSound(center, SND_WHISPER, 0.09f, 0.55f);
            }
        }

        // ── Deep bass pulse (every 45 s) ───────────────────────────────────────
        // Low pitch (0.30) produces a subsonic-feeling thud — players feel more
        // than hear it.
        if (adjFire % FIRE_PERIOD_RARE == 0) {
            world.playSound(center, SND_BASS, crowded ? 0.45f : 0.28f, 0.30f);
        }

        // ── Attract flash (every 30 s) ─────────────────────────────────────────
        // A brief, eye-catching flicker. Sound alternates between amethyst chime
        // (6/7) and metallic creak (1/7). Crowd amplifies the particle burst.
        if (adjFire % FIRE_PERIOD_ATTRACT == 0) {
            if (config.particlesEnabled()) {
                world.spawnParticle(Particle.END_ROD,
                        center.clone().add(0, 0.25, 0),
                        crowded ? P_ATTRACT_COUNT * 2 : P_ATTRACT_COUNT,
                        0.25, 0.20, 0.25, 0.03);
            }
            if (ThreadLocalRandom.current().nextInt(7) == 0) {
                world.playSound(center, SND_CREAK, 0.18f, 0.42f);
            } else {
                world.playSound(center, SND_ATTRACT, 0.40f, 0.70f);
            }
        }
    }

    // =========================================================================
    //  Crowd cache
    // =========================================================================

    /** Refresh the per-machine nearby-player count cache. Main thread only. */
    private void updateCrowdCache() {
        crowdCache.clear();
        double radius = config.crowdRadius();
        for (MachineBlock machine : machineRegistry.all()) {
            Location loc = machine.location();
            if (loc == null) continue;
            World world = loc.getWorld();
            if (world == null) continue;
            if (!world.isChunkLoaded(machine.x() >> 4, machine.z() >> 4)) continue;
            Location center = loc.clone().add(0.5, 0.5, 0.5);
            crowdCache.put(machine.locationKey(),
                    world.getNearbyPlayers(center, radius).size());
        }
    }

    // =========================================================================
    //  Dynamic void events
    // =========================================================================

    /**
     * Schedule the next void-event after a random delay between
     * {@link PluginConfig#voidEventsMinMinutes()} and
     * {@link PluginConfig#voidEventsMaxMinutes()}.
     * Self-rescheduling: each event re-schedules the next one.
     */
    private void scheduleNextVoidEvent() {
        int minTicks = config.voidEventsMinMinutes() * 60 * 20;
        int maxTicks = config.voidEventsMaxMinutes() * 60 * 20;
        long delay = (maxTicks > minTicks)
                ? minTicks + ThreadLocalRandom.current().nextInt(maxTicks - minTicks)
                : minTicks;

        voidEventTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            voidEventTask = null;
            triggerVoidEvent();
            if (config.voidEventsEnabled()) scheduleNextVoidEvent();
        }, delay);
    }

    /**
     * Trigger a void-event surge on a randomly selected idle machine.
     *
     * <p>Effects: stronger particle burst, deeper bass surge, ender dragon
     * ambient resonance, lightning pulse, and an action-bar message to nearby
     * players.  Atmosphere only — no gameplay changes.</p>
     */
    private void triggerVoidEvent() {
        // Collect eligible (non-locked, loaded chunk) machines.
        List<MachineBlock> eligible = new ArrayList<>();
        for (MachineBlock m : machineRegistry.all()) {
            if (m.isLocked()) continue;
            Location loc = m.location();
            if (loc == null) continue;
            World world = loc.getWorld();
            if (world == null) continue;
            if (!world.isChunkLoaded(m.x() >> 4, m.z() >> 4)) continue;
            eligible.add(m);
        }
        if (eligible.isEmpty()) return;

        MachineBlock machine = eligible.get(
                ThreadLocalRandom.current().nextInt(eligible.size()));
        Location loc = machine.location();
        if (loc == null) return;
        World world = loc.getWorld();
        if (world == null) return;

        Location center = loc.clone().add(0.5, 0.85, 0.5);

        // Stronger particle burst — the Void stirs.
        if (config.particlesEnabled()) {
            world.spawnParticle(Particle.PORTAL, center, 40, 0.5, 0.6, 0.5, 0.08);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME,
                    center.clone().add(0, 0.5, 0), 8, 0.3, 0.2, 0.3, 0.0);
        }

        // Deep bass surge — louder and lower pitch than the normal idle pulse.
        world.playSound(center, SND_BASS, 0.65f, 0.22f);
        world.playSound(center, SND_VOID_GROWL, 0.40f, 0.85f);

        // Lightning pulse.
        world.strikeLightningEffect(loc);

        // Action bar to nearby players.
        double msgRadius = Math.max(8.0, config.crowdRadius());
        for (Player p : world.getNearbyPlayers(center, msgRadius)) {
            p.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<dark_purple><italic>The Void grows restless…</italic></dark_purple>"));
        }
    }
}
