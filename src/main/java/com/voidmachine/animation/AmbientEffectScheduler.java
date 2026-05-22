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
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    //   FIRE_PERIOD_HUM    = 3  →  3 × 20 =  60 ticks = 3 s
    //   FIRE_PERIOD_ATTRACT = 30 → 30 × 20 = 600 ticks = 30 s

    private static final long TICK_PERIOD         = 20L; // period of the repeating task
    private static final int  FIRE_PERIOD_HUM     = 3;   // fires between hum/soul-fire
    private static final int  FIRE_PERIOD_ATTRACT = 30;  // fires between attract pulses

    // ── Sounds (world-space, String keys — forward-compatible with Paper 1.21+) ─
    private static final String SND_HUM     = "block.respawn_anchor.ambient";
    private static final String SND_ATTRACT = "block.amethyst_block.chime";

    // ── Particle counts — keep minimal for Bedrock and high-machine-count servers ─
    private static final int  P_SMOKE_COUNT   = 2;
    private static final int  P_SOUL_COUNT    = 1;
    private static final int  P_ATTRACT_COUNT = 6;

    private final VoidMachinePlugin plugin;
    private final PluginConfig       config;
    private final MachineRegistry    machineRegistry;

    /** The active repeating task, or {@code null} when stopped. */
    @Nullable private BukkitTask task;

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
    }

    /**
     * Cancel the ambient task. Idempotent. Safe to call from {@code onDisable}.
     */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // =========================================================================
    //  Tick
    // =========================================================================

    private void tick() {
        final int fire = fireCount++;

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
            int offset  = Math.abs(machine.locationKey().hashCode()) % FIRE_PERIOD_ATTRACT;
            int adjFire = fire + offset;

            tickMachine(world, loc, adjFire);
        }
    }

    /** Apply ambient effects for a single idle machine. */
    private void tickMachine(@NotNull World world,
                             @NotNull Location loc,
                             int adjFire) {
        // Emission point: top surface of the block, centred horizontally.
        Location center = loc.clone().add(0.5, 0.85, 0.5);

        // ── Smoke drift (every fire = 1 s) ────────────────────────────────────
        if (config.particlesEnabled()) {
            // Portal void swirl — 2 particles, very low extra-velocity.
            world.spawnParticle(Particle.PORTAL, center,
                    P_SMOKE_COUNT, 0.30, 0.25, 0.30, 0.04);
        }

        // ── Soul-fire accent + hum (every 3 s) ────────────────────────────────
        if (adjFire % FIRE_PERIOD_HUM == 0) {
            if (config.particlesEnabled()) {
                // Single soul-fire flame drifting upward — dark-blue accent light.
                world.spawnParticle(Particle.SOUL_FIRE_FLAME,
                        center.clone().add(0, 0.35, 0),
                        P_SOUL_COUNT, 0.12, 0.08, 0.12, 0.0);
            }
            // Ambient hum — volume 0.25 → heard ≈ 4 blocks. Barely audible;
            // creates a subsonic "presence" rather than an obvious sound.
            world.playSound(center, SND_HUM, 0.25f, 0.50f);
        }

        // ── Attract flash (every 30 s) ─────────────────────────────────────────
        // Goal: a brief, eye-catching flicker that makes a passing player turn around.
        // "Was that something over there?"
        if (adjFire % FIRE_PERIOD_ATTRACT == 0) {
            if (config.particlesEnabled()) {
                world.spawnParticle(Particle.END_ROD,
                        center.clone().add(0, 0.25, 0),
                        P_ATTRACT_COUNT, 0.25, 0.20, 0.25, 0.03);
            }
            // Amethyst chime — volume 0.4 → heard ≈ 6 blocks. Distinct enough to
            // notice, quiet enough not to be annoying at distance.
            world.playSound(center, SND_ATTRACT, 0.40f, 0.70f);
        }
    }
}
