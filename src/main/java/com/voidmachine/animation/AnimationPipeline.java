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
import com.voidmachine.audit.AuditLogger;
import com.voidmachine.checkpoint.CheckpointStore;
import com.voidmachine.checkpoint.PendingDeliveryQueue;
import com.voidmachine.config.MessageManager;
import com.voidmachine.config.PluginConfig;
import com.voidmachine.core.Outcome;
import com.voidmachine.core.OutcomeRoller;
import com.voidmachine.core.Transaction;
import com.voidmachine.db.GlobalStats;
import com.voidmachine.interaction.ItemCaptureService;
import com.voidmachine.service.RitualLockService;
import com.voidmachine.machine.MachineBlock;
import com.voidmachine.machine.MachineRegistry;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Drives the in-world ritual animation for a single active transaction.
 *
 * <h3>Phase state machine</h3>
 * <pre>
 *   start() → RAMP (boss bar 0 → 90%, particles, ambient sound)
 *           → TENSION (boss bar frozen at 90%, 2-second ominous silence)
 *           → REVEAL (roll outcome, deliver items, reveal effects)
 *           → completeTransaction()
 * </pre>
 *
 * <h3>Bedrock / Geyser compatibility</h3>
 * <ul>
 *   <li>Boss bar — shown via Adventure API; Geyser renders these on Bedrock. ✓</li>
 *   <li>{@link ItemDisplay} entities — not visible on Bedrock clients (Geyser limitation
 *       as of 1.21.4); spawned only as a best-effort enhancement for Java clients.</li>
 *   <li>Particles — world-space spawns; most vanilla particles translate through
 *       Geyser with some degradation.</li>
 *   <li>Sounds — standard Bukkit Sound enum maps to vanilla sounds on both platforms.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * All methods must be called on the main thread.
 * {@link #active} uses {@link ConcurrentHashMap} for safe reads from admin commands.
 */
public final class AnimationPipeline {

    // PDC key to identify our display entities for cleanup.
    private static final String DISPLAY_PDC_KEY = "vm_anim_display";

    // ── Sound keys (Minecraft namespaced, no mc: prefix needed) ──────────────
    // Using Strings avoids the deprecated org.bukkit.Sound enum (Paper 1.21+).
    private static final String SND_ANCHOR_CHARGE  = "block.respawn_anchor.charge";
    private static final String SND_ANCHOR_AMBIENT = "block.respawn_anchor.ambient";
    private static final String SND_ANCHOR_DEPLETE = "block.respawn_anchor.deplete";
    private static final String SND_PORTAL_AMBIENT = "block.portal.ambient";
    private static final String SND_LEVELUP        = "entity.player.levelup";
    private static final String SND_AMETHYST_CHIME = "block.amethyst_block.chime";
    private static final String SND_CHALLENGE_DONE = "ui.toast.challenge_complete";
    // Jackpot identity — "the void acknowledges the sacrifice".
    // Played at the second lightning strike (+10 ticks) so it lands with the visual.
    private static final String SND_DRAGON_GROWL   = "entity.ender_dragon.growl";
    // Fakeout — played when the machine pretends to consume a rewarding item.
    private static final String SND_WITHER_SPAWN   = "entity.wither.spawn";
    // Dragon variant — ominous ambient resonance before the real reveal.
    private static final String SND_DRAGON_AMBIENT = "entity.ender_dragon.ambient";

    private final VoidMachinePlugin plugin;
    private final PluginConfig config;
    private final MessageManager messages;
    private final CheckpointStore checkpoints;
    private final MachineRegistry machineRegistry;
    private final PendingDeliveryQueue deliveryQueue;
    private final AuditLogger audit;
    private final OutcomeRoller roller;
    private final ItemCaptureService captureService;
    private final Logger logger;

    /** Live animations keyed by player UUID. */
    private final ConcurrentHashMap<UUID, AnimationContext> active = new ConcurrentHashMap<>();

    /**
     * Cinematic inventory GUI — wired after construction via {@link #setCinematicGui}.
     * Null until bootstrap completes; all call sites guard with a null-check.
     */
    @Nullable
    private volatile CinematicGui cinematicGui;

    /**
     * Global lifetime stats — wired after construction via {@link #setGlobalStats}.
     * Null until bootstrap completes; call site guards with a null-check.
     */
    @Nullable
    private volatile GlobalStats globalStats;

    /**
     * Ritual lock service — wired after construction via {@link #setRitualLockService}.
     * Null until bootstrap completes; call sites guard with a null-check.
     */
    @Nullable
    private volatile RitualLockService ritualLockService;

    public AnimationPipeline(@NotNull VoidMachinePlugin plugin,
                             @NotNull PluginConfig config,
                             @NotNull MessageManager messages,
                             @NotNull CheckpointStore checkpoints,
                             @NotNull MachineRegistry machineRegistry,
                             @NotNull PendingDeliveryQueue deliveryQueue,
                             @NotNull AuditLogger audit,
                             @NotNull OutcomeRoller roller,
                             @NotNull ItemCaptureService captureService) {
        this.plugin          = plugin;
        this.config          = config;
        this.messages        = messages;
        this.checkpoints     = checkpoints;
        this.machineRegistry = machineRegistry;
        this.deliveryQueue   = deliveryQueue;
        this.audit           = audit;
        this.roller          = roller;
        this.captureService  = captureService;
        this.logger          = plugin.getLogger();
    }

    /** Wire the cinematic GUI after construction (called from bootstrap). */
    public void setCinematicGui(@NotNull CinematicGui gui) {
        this.cinematicGui = gui;
    }

    /** Wire global stats after construction (called from bootstrap). */
    public void setGlobalStats(@NotNull GlobalStats stats) {
        this.globalStats = stats;
    }

    /** Wire ritual lock service after construction (called from bootstrap). */
    public void setRitualLockService(@NotNull RitualLockService service) {
        this.ritualLockService = service;
    }

    // =========================================================================
    //  Public API
    // =========================================================================

    /**
     * Begin the ritual animation for the given transaction.
     * Called by {@link ItemCaptureService}'s after-capture hook.
     * Must run on the main thread.
     *
     * <p>If the player is offline or the machine world is unloaded, aborts
     * immediately via {@link ItemCaptureService#abortTransaction}.</p>
     */
    public void start(@NotNull Transaction tx) {
        UUID uuid = tx.playerId();

        // Sanity checks before committing to an animation.
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            captureService.abortTransaction(uuid, "offline_before_animation");
            return;
        }

        MachineBlock machine = machineRegistry.atKey(tx.machineLoc());
        if (machine == null) {
            captureService.abortTransaction(uuid, "machine_gone_before_animation");
            return;
        }

        Location machLoc = machine.location();
        if (machLoc == null) {
            captureService.abortTransaction(uuid, "world_unloaded_before_animation");
            return;
        }

        // ── Compute phase timing ───────────────────────────────────────────────
        // Ramp is driven by animation.steps × world-animation.step-ticks.
        // Watchdog (worldAnimMaxDurationTicks) is separate — it must be > ramp + tension + hold.
        // Defaults: 30 steps × 2 ticks = 60 ticks ramp + 20 tension + 20 hold ≈ 5 s total.

        int stepTicks    = config.worldAnimStepTicks();       // default 2
        int tensionTicks = config.worldAnimTensionLockTicks(); // default 20
        int rampSteps    = config.animationSteps();            // default 30

        // ── Build boss bar ────────────────────────────────────────────────────

        BossBar bossBar = BossBar.bossBar(
                messages.render("animation.bossbar.start"),
                0.0f,
                BossBar.Color.PURPLE,
                BossBar.Overlay.PROGRESS);
        player.showBossBar(bossBar);

        // ── Spawn ItemDisplay (best-effort, Java clients only) ────────────────

        ItemDisplay display = spawnDisplay(machLoc, tx.sacrifice());

        // ── Play start sound ──────────────────────────────────────────────────

        playSound(player, SND_ANCHOR_CHARGE, 1.0f, 0.8f);

        // ── Pre-roll outcome (requirement: decided before animation begins) ────
        //
        // The reels in CinematicGui are presentation only.  Rolling here ensures
        // the result is committed to the AnimationContext before any GUI frame is
        // shown.  The reveal() method uses ctx.outcome / ctx.outputAmount directly
        // and does NOT re-roll.

        Outcome outcome  = roller.rollForProfile(tx.configProfile());
        int multiplier   = config.outcomeMultiplier(outcome);
        int rawOutput    = tx.inputAmount() * multiplier;
        int outputAmount = config.clampOnOverflow()
                ? Math.min(rawOutput, config.maxReturnAmount())
                : rawOutput;

        // ── Pre-roll fakeout reveal ───────────────────────────────────────────
        // DOUBLED / TRIPLED / JACKPOT_X5 only. Pure presentation — items are
        // committed before any visual plays; no economic impact.
        boolean isFakeout = false;
        int fakeoutChance = config.fakeoutChance(); // 0 = disabled
        if (fakeoutChance > 0
                && (outcome == Outcome.DOUBLED
                        || outcome == Outcome.TRIPLED
                        || outcome == Outcome.JACKPOT_X5)) {
            isFakeout = ThreadLocalRandom.current().nextInt(fakeoutChance) == 0;
        }

        // ── Pre-roll jackpot variant (JACKPOT_X5 only) ───────────────────────
        JackpotVariant jackpotVariant = (outcome == Outcome.JACKPOT_X5)
                ? JackpotVariant.roll() : null;

        // ── Create context and start ramp ────────────────────────────────────

        AnimationContext ctx = new AnimationContext(
                tx, bossBar, display, rampSteps, stepTicks, tensionTicks,
                outcome, outputAmount, isFakeout, jackpotVariant);
        active.put(uuid, ctx);

        // ── Open cinematic GUI ────────────────────────────────────────────────
        // Permit the next InventoryOpenEvent for this player — the ritual lock
        // blocks all inventory opens, but the CinematicGui open is plugin-initiated
        // and must be allowed through.  The permit is consumed atomically by
        // RitualLockListener and is valid for this one open only.

        RitualLockService rl = ritualLockService;
        if (rl != null) rl.permitNextGuiOpen(uuid);

        CinematicGui cg = cinematicGui;
        if (cg != null) cg.open(player, tx, ctx.outcome, ctx.outputAmount);

        scheduleRamp(ctx, machLoc);
    }

    /**
     * Cancel and clean up the animation for a player.
     * Called from {@link ItemCaptureService#abortTransaction} — must be called
     * BEFORE the transaction is deregistered.
     *
     * <p>No-op if no animation is active for this player.</p>
     */
    public void cancelForPlayer(@NotNull UUID playerUuid) {
        AnimationContext ctx = active.remove(playerUuid);
        if (ctx == null) return;

        ctx.cancelled = true;
        if (ctx.rampTask != null) ctx.rampTask.cancel();

        despawnDisplay(ctx.display);

        Player p = plugin.getServer().getPlayer(playerUuid);
        if (p != null) p.hideBossBar(ctx.bossBar);

        CinematicGui cg = cinematicGui;
        if (cg != null) cg.close(playerUuid);

        // Release ritual lock — animation cancelled, player is free.
        RitualLockService rl = ritualLockService;
        if (rl != null) rl.unlock(playerUuid);
    }

    /**
     * Cancel all active animations without firing abort callbacks.
     * Called from {@code VoidMachinePlugin.onDisable()} — the outer shutdown
     * loop aborts transactions separately.
     */
    public void shutdownAll() {
        for (Map.Entry<UUID, AnimationContext> entry : active.entrySet()) {
            entry.getValue().cancelled = true;
            if (entry.getValue().rampTask != null) entry.getValue().rampTask.cancel();
            despawnDisplay(entry.getValue().display);
            Player p = plugin.getServer().getPlayer(entry.getKey());
            if (p != null) p.hideBossBar(entry.getValue().bossBar);
        }
        active.clear();
        CinematicGui cg = cinematicGui;
        if (cg != null) cg.shutdown();
        // Unlock all ritual locks — shutdown clears every active animation.
        RitualLockService rl = ritualLockService;
        if (rl != null) rl.unlockAll();
    }

    /** Returns {@code true} if an animation is currently running for this player. */
    public boolean isActive(@NotNull UUID playerUuid) {
        return active.containsKey(playerUuid);
    }

    // =========================================================================
    //  Phase 1 — RAMP (boss bar 0 → 90%)
    // =========================================================================

    private void scheduleRamp(@NotNull AnimationContext ctx,
                              @NotNull Location machLoc) {
        AtomicReference<BukkitTask> taskRef = new AtomicReference<>();

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (ctx.cancelled) {
                BukkitTask t = taskRef.get();
                if (t != null) t.cancel();
                return;
            }

            UUID uuid = ctx.tx.playerId();
            Player player = plugin.getServer().getPlayer(uuid);

            if (player == null || !player.isOnline()) {
                BukkitTask t = taskRef.get();
                if (t != null) t.cancel();
                // abortTransaction handles cleanup (calls cancelForPlayer first).
                captureService.abortTransaction(uuid, "player_quit_during_ramp");
                return;
            }

            ctx.step++;

            // Update boss bar: 0% → 90% linearly over rampSteps.
            float progress = Math.min(0.9f, (float) ctx.step / ctx.rampSteps * 0.9f);
            ctx.bossBar.progress(progress);

            // Particles at machine.
            spawnRampParticles(machLoc, ctx.step);

            // Update display entity spin (every step).
            if (ctx.display != null && !ctx.display.isDead()) {
                updateDisplaySpin(ctx.display, ctx.step, ctx.stepTicks);
            }

            // Ambient sound escalating with progress (every 20 steps).
            if (ctx.step % 20 == 0) {
                float pitch = 0.8f + (progress * 0.4f); // 0.8 → 1.16 as it fills
                playSound(player, SND_PORTAL_AMBIENT, 0.35f, pitch);
                // World-space version — spectators nearby hear the machine building.
                // volume 0.4 → heard ≈ 6 blocks; subtle enough not to be intrusive.
                World rampWorld = machLoc.getWorld();
                if (rampWorld != null) {
                    rampWorld.playSound(machLoc.clone().add(0.5, 0.5, 0.5),
                            SND_PORTAL_AMBIENT, 0.40f, pitch);
                }
            }

            // Update cinematic GUI.
            CinematicGui cg = cinematicGui;
            if (cg != null) cg.onRampStep(uuid, ctx.step, progress);

            // Ramp complete → transition to tension.
            if (ctx.step >= ctx.rampSteps) {
                BukkitTask t = taskRef.get();
                if (t != null) t.cancel();
                startTension(ctx, machLoc);
            }

        }, 1L, ctx.stepTicks);

        taskRef.set(task);
        ctx.rampTask = task;
    }

    // =========================================================================
    //  Phase 2 — TENSION (boss bar frozen at 90%, ominous pause)
    // =========================================================================

    private void startTension(@NotNull AnimationContext ctx,
                              @NotNull Location machLoc) {
        if (ctx.cancelled) return;

        UUID uuid = ctx.tx.playerId();
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            captureService.abortTransaction(uuid, "player_quit_during_tension");
            return;
        }

        // Boss bar: frozen at 90%, colour shifts to red, title goes ominous.
        ctx.bossBar.progress(0.9f);
        ctx.bossBar.color(BossBar.Color.RED);
        ctx.bossBar.name(messages.render("animation.bossbar.tension"));

        // Tension sound — deep respawn-anchor hum (player-local, full volume).
        playSound(player, SND_ANCHOR_AMBIENT, 0.9f, 0.5f);
        // World-space version — spectators within ~8 blocks feel the dread too.
        World tensionWorld = machLoc.getWorld();
        if (tensionWorld != null) {
            tensionWorld.playSound(machLoc.clone().add(0.5, 0.5, 0.5),
                    SND_ANCHOR_AMBIENT, 0.5f, 0.5f);
        }

        // Update cinematic GUI.
        CinematicGui cg = cinematicGui;
        if (cg != null) cg.onTension(uuid);

        // Stop display spin — freeze the floating item.
        // (No further updates to display entity until reveal.)

        // Schedule reveal after tensionTicks.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (ctx.cancelled) return;
            Player p = plugin.getServer().getPlayer(uuid);
            reveal(ctx, p, machLoc);
        }, ctx.tensionTicks);
    }

    // =========================================================================
    //  Phase 3 — REVEAL (use pre-rolled outcome, deliver, cleanup)
    // =========================================================================

    private void reveal(@NotNull AnimationContext ctx,
                        @Nullable Player player,
                        @NotNull Location machLoc) {
        if (ctx.cancelled) return;

        UUID uuid = ctx.tx.playerId();

        // ── Use pre-rolled outcome (decided in start() before animation began) ─

        Outcome outcome      = ctx.outcome;
        int     outputAmount = ctx.outputAmount;

        // ── Mark DELIVERING in checkpoint BEFORE any delivery ────────────────

        boolean deliverOk = checkpoints.markDelivering(uuid, outcome, outputAmount);
        if (!deliverOk) {
            logger.severe("[AnimationPipeline] markDelivering failed for " + ctx.tx.playerName()
                    + " — aborting to prevent phantom delivery.");
            cleanupAnimation(ctx, uuid);
            captureService.abortTransaction(uuid, "delivering_checkpoint_failed");
            return;
        }

        // Advance transaction state.
        ctx.tx.markDelivering(outcome, outputAmount);

        // ── Deliver items ────────────────────────────────────────────────────

        if (outcome != Outcome.DESTROYED && outputAmount > 0) {
            ItemStack reward = ctx.tx.copyOfSacrificeWithAmount(outputAmount);
            if (player != null && player.isOnline()) {
                giveOrDrop(player, reward);
                player.updateInventory();
            } else {
                deliveryQueue.add(uuid, ctx.tx.playerName(), reward, "offline_during_reveal");
            }
        }

        // ── Complete transaction bookkeeping (before visual sequence) ─────────
        // Items are safe — audit and stats are recorded before any fakeout delay.

        ctx.tx.markCompleted();

        String itemType = ctx.tx.sacrifice() != null
                ? ctx.tx.sacrifice().getType().key().asString() : "unknown";
        audit.logCompleted(uuid, ctx.tx.playerName(), ctx.tx.machineLoc(),
                itemType, ctx.tx.inputAmount(), outcome, outputAmount);

        GlobalStats gs = globalStats;
        if (gs != null) gs.record(itemType, ctx.tx.inputAmount(), outcome);

        logger.info("[AnimationPipeline] " + ctx.tx.playerName() + " → " + outcome.name()
                + " (in=" + ctx.tx.inputAmount() + " out=" + outputAmount + ')');

        // ── Visual sequence ───────────────────────────────────────────────────

        if (ctx.isFakeout) {
            playFakeoutSequence(ctx, player, machLoc, uuid);
        } else {
            int crowdNearby = getCrowdNearby(machLoc);
            showRevealState(ctx, player, machLoc, outcome, outputAmount, crowdNearby);
            schedulePostRevealCleanup(ctx, uuid);
        }
    }

    // =========================================================================
    //  Reveal helpers — shared by normal and fakeout paths
    // =========================================================================

    /**
     * Apply the real reveal visuals: boss bar update, sound/particle effects,
     * cinematic GUI reveal, and chat message.
     * Called by both the normal reveal path and the fakeout real-reveal phase.
     */
    private void showRevealState(@NotNull AnimationContext ctx,
                                  @Nullable Player player,
                                  @NotNull Location machLoc,
                                  @NotNull Outcome outcome,
                                  int outputAmount,
                                  int crowdNearby) {
        UUID uuid = ctx.tx.playerId();

        ctx.bossBar.progress(1.0f);
        ctx.bossBar.name(messages.render("animation.bossbar.reveal." + outcome.configKey()));
        ctx.bossBar.color(revealBarColor(outcome));

        playRevealEffects(outcome, ctx.jackpotVariant, player, machLoc, crowdNearby);

        CinematicGui cg = cinematicGui;
        if (cg != null) cg.onReveal(uuid, outcome, outputAmount);

        if (player != null && player.isOnline()) {
            String itemKey = ctx.tx.sacrifice() != null
                    ? ctx.tx.sacrifice().getType().key().asString() : "unknown";
            player.sendMessage(messages.outcomeMessage(outcome,
                    Placeholder.parsed("input",  String.valueOf(ctx.tx.inputAmount())),
                    Placeholder.parsed("output", String.valueOf(outputAmount)),
                    Placeholder.parsed("item",   itemKey)));
        }
    }

    /**
     * Schedule the post-reveal cleanup: hide boss bar after 1 s, then clean up
     * animation state and mark the transaction complete.
     */
    private void schedulePostRevealCleanup(@NotNull AnimationContext ctx,
                                            @NotNull UUID uuid) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.hideBossBar(ctx.bossBar);
            cleanupAnimation(ctx, uuid);
            captureService.completeTransaction(ctx.tx);
        }, 20L);
    }

    /**
     * Rare fakeout sequence: present a fake CONSUMED result, pause ~1.25 s,
     * then snap to the real outcome with a lightning strike.
     *
     * <p>Items are already delivered and the transaction is already marked
     * completed before this method is called — the fakeout is pure theatre.</p>
     */
    private void playFakeoutSequence(@NotNull AnimationContext ctx,
                                      @Nullable Player player,
                                      @NotNull Location machLoc,
                                      @NotNull UUID uuid) {
        World world  = machLoc.getWorld();
        Location center = machLoc.clone().add(0.5, 1.0, 0.5);

        // ── Phase A: fake CONSUMED state ──────────────────────────────────────
        ctx.bossBar.progress(1.0f);
        ctx.bossBar.name(messages.render("animation.bossbar.reveal."
                + Outcome.DESTROYED.configKey()));
        ctx.bossBar.color(BossBar.Color.RED);

        if (world != null) {
            world.spawnParticle(Particle.SMOKE, center, 20, 0.3, 0.4, 0.3, 0.04);
            // World-space wither sound — spectators hear the "consumption" too.
            world.playSound(center, SND_WITHER_SPAWN, 0.55f, 1.3f);
        }

        // CinematicGui: display fake consumed state.
        CinematicGui cgFake = cinematicGui;
        if (cgFake != null) cgFake.onReveal(uuid, Outcome.DESTROYED, 0);

        // ── Phase B: lightning snap → real reveal (~1.25 s later) ────────────
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (ctx.cancelled) return;
            Player p = plugin.getServer().getPlayer(uuid);

            // Lightning snap — the "just kidding" moment.
            if (world != null && world.isChunkLoaded(
                    machLoc.getBlockX() >> 4, machLoc.getBlockZ() >> 4)) {
                world.strikeLightningEffect(machLoc);
            }

            int crowdNearby = getCrowdNearby(machLoc);
            showRevealState(ctx, p, machLoc, ctx.outcome, ctx.outputAmount, crowdNearby);
            schedulePostRevealCleanup(ctx, uuid);
        }, 25L);
    }

    /**
     * Returns the number of players within the crowd-awareness radius of the
     * machine. Returns 0 if crowd awareness is disabled or the world is null.
     * Called once at reveal time — not cached.
     */
    private int getCrowdNearby(@NotNull Location machLoc) {
        if (!config.crowdEnabled()) return 0;
        World world = machLoc.getWorld();
        if (world == null) return 0;
        Location center = machLoc.clone().add(0.5, 0.5, 0.5);
        return world.getNearbyPlayers(center, config.crowdRadius()).size();
    }

    // =========================================================================
    //  Display entity
    // =========================================================================

    @Nullable
    private ItemDisplay spawnDisplay(@NotNull Location machLoc,
                                     @Nullable ItemStack sacrifice) {
        if (sacrifice == null) return null;
        World world = machLoc.getWorld();
        if (world == null) return null;

        try {
            // Spawn centred 1.5 blocks above the machine block.
            Location spawnAt = machLoc.clone().add(0.5, 1.6, 0.5);
            ItemDisplay display = (ItemDisplay) world.spawnEntity(spawnAt, EntityType.ITEM_DISPLAY);
            display.setItemStack(sacrifice.clone());
            display.setGravity(false);
            display.setPersistent(false); // removed automatically if chunk unloads

            // Scale to a reasonable size; no initial rotation.
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(0);
            display.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new Quaternionf(0, 0, 0, 1), // identity
                    new Vector3f(0.55f, 0.55f, 0.55f),
                    new Quaternionf(0, 0, 0, 1)));

            // Tag so we can find stale entities after a crash (future: cleanup on startup).
            display.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, DISPLAY_PDC_KEY),
                    PersistentDataType.BYTE, (byte) 1);

            return display;
        } catch (Exception ex) {
            logger.warning("[AnimationPipeline] Could not spawn ItemDisplay: " + ex.getMessage());
            return null;
        }
    }

    private void updateDisplaySpin(@NotNull ItemDisplay display, int step, int stepTicks) {
        try {
            // Rotate Y-axis: 0.12 radians per step ≈ full rotation every ~52 steps.
            float angle = step * 0.12f;
            Quaternionf spin = new Quaternionf().rotationY(angle);
            display.setInterpolationDelay(-1);
            display.setInterpolationDuration(stepTicks);
            display.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new Quaternionf(0, 0, 0, 1),
                    new Vector3f(0.55f, 0.55f, 0.55f),
                    spin));
        } catch (Exception ex) {
            // Non-fatal — just skip this frame's spin.
        }
    }

    private void despawnDisplay(@Nullable ItemDisplay display) {
        if (display != null && !display.isDead()) {
            display.remove();
        }
    }

    // =========================================================================
    //  Particles
    // =========================================================================

    private void spawnRampParticles(@NotNull Location machLoc, int step) {
        World world = machLoc.getWorld();
        if (world == null) return;

        int count = config.worldAnimParticlesPerStep();
        if (count <= 0) return;

        Location center = machLoc.clone().add(0.5, 0.5, 0.5);

        // Portal swirl — main ramp particle.
        world.spawnParticle(Particle.PORTAL, center, count, 0.4, 0.4, 0.4, 0.1);

        // Every 10 steps: soul-fire accent.
        if (step % 10 == 0) {
            int accent = Math.min(4, count / 3);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME,
                    center.clone().add(0, 0.6, 0), accent, 0.15, 0.15, 0.15, 0.0);
        }
    }

    private void playRevealEffects(@NotNull Outcome outcome,
                                   @Nullable JackpotVariant jackpotVariant,
                                   @Nullable Player player,
                                   @NotNull Location machLoc,
                                   int crowdNearby) {
        World world = machLoc.getWorld();
        Location center = machLoc.clone().add(0.5, 1.0, 0.5);

        switch (outcome) {
            case DESTROYED -> {
                if (player != null && player.isOnline())
                    playSound(player, SND_ANCHOR_DEPLETE, 1.0f, 0.7f);
                if (world != null)
                    world.spawnParticle(Particle.SMOKE, center, 20, 0.3, 0.4, 0.3, 0.04);
            }
            case RETURNED -> {
                if (player != null && player.isOnline())
                    playSound(player, SND_AMETHYST_CHIME, 1.0f, 1.3f);
                if (world != null)
                    world.spawnParticle(Particle.END_ROD, center, 12, 0.25, 0.25, 0.25, 0.02);
            }
            case DOUBLED -> {
                if (player != null && player.isOnline())
                    playSound(player, SND_LEVELUP, 1.0f, 1.0f);
                if (world != null)
                    world.spawnParticle(Particle.END_ROD, center, 25, 0.35, 0.35, 0.35, 0.04);
            }
            case TRIPLED -> {
                if (player != null && player.isOnline())
                    playSound(player, SND_LEVELUP, 1.0f, 1.3f);
                if (world != null) {
                    // Crowd bonus: extra totem burst when min-players threshold met.
                    int extraTotem = (crowdNearby >= config.crowdMinPlayers()) ? 20 : 0;
                    world.spawnParticle(Particle.TOTEM_OF_UNDYING, center,
                            30 + extraTotem, 0.4, 0.4, 0.4, 0.08);
                    world.spawnParticle(Particle.END_ROD, center, 12, 0.3, 0.3, 0.3, 0.03);
                    // World-space sound — nearby players (~32 blocks) hear the surge.
                    world.playSound(center, SND_LEVELUP, 2.0f, 1.3f);
                    // Resonance aftershock at +4 ticks — distinct TRIPLED audio identity.
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (world.isChunkLoaded(machLoc.getBlockX() >> 4, machLoc.getBlockZ() >> 4)) {
                            world.playSound(center, SND_AMETHYST_CHIME, 0.85f, 0.65f);
                        }
                    }, 4L);
                }
            }
            case JACKPOT_X5 -> {
                // Dispatch to pre-rolled jackpot variant.
                JackpotVariant variant = (jackpotVariant != null)
                        ? jackpotVariant : JackpotVariant.STORM;
                boolean crowded = crowdNearby >= config.crowdMinPlayers();
                switch (variant) {
                    case STORM  -> playJackpotStorm(player, machLoc, crowded);
                    case SILENT -> playJackpotSilent(player, machLoc, crowded);
                    case DRAGON -> playJackpotDragon(player, machLoc, crowded);
                    case ECHO   -> playJackpotEcho(player, machLoc, crowded);
                }
            }
        }
    }

    // ── Jackpot variant implementations ──────────────────────────────────────

    /** Classic storm — triple lightning, challenge fanfare, dragon growl. */
    private void playJackpotStorm(@Nullable Player player,
                                   @NotNull Location machLoc,
                                   boolean crowded) {
        World world = machLoc.getWorld();
        Location center = machLoc.clone().add(0.5, 1.0, 0.5);
        if (player != null && player.isOnline())
            playSound(player, SND_CHALLENGE_DONE, 1.0f, 1.0f);
        if (world != null) {
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, center,
                    crowded ? 70 : 50, 0.5, 0.5, 0.5, 0.15);
            world.spawnParticle(Particle.END_ROD, center,
                    crowded ? 30 : 20, 0.4, 0.4, 0.4, 0.05);
            world.strikeLightningEffect(machLoc);
            world.playSound(center, SND_CHALLENGE_DONE, crowded ? 6.0f : 4.0f, 1.0f);
            // Second strike — +10 ticks: dragon growl.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (world.isChunkLoaded(machLoc.getBlockX() >> 4, machLoc.getBlockZ() >> 4)) {
                    world.strikeLightningEffect(machLoc);
                    world.spawnParticle(Particle.TOTEM_OF_UNDYING, center,
                            30, 0.4, 0.4, 0.4, 0.10);
                    world.playSound(center, SND_DRAGON_GROWL, 0.65f, 1.2f);
                }
            }, 10L);
            // Third strike — +20 ticks: final punctuation.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (world.isChunkLoaded(machLoc.getBlockX() >> 4, machLoc.getBlockZ() >> 4)) {
                    world.strikeLightningEffect(machLoc);
                    if (crowded) {
                        world.spawnParticle(Particle.TOTEM_OF_UNDYING, center,
                                20, 0.35, 0.35, 0.35, 0.08);
                    }
                }
            }, 20L);
        }
    }

    /**
     * Silent void — smoke implosion, one second of silence, then sudden chime
     * and lightning burst. "The void doesn't need to be loud."
     */
    private void playJackpotSilent(@Nullable Player player,
                                    @NotNull Location machLoc,
                                    boolean crowded) {
        World world = machLoc.getWorld();
        Location center = machLoc.clone().add(0.5, 1.0, 0.5);
        // Immediate: brief smoke implosion — dark and ominous.
        if (world != null)
            world.spawnParticle(Particle.SMOKE, center, 12, 0.3, 0.5, 0.3, 0.02);
        // After 1 s: sudden burst — the void breaks its own silence.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (world == null) return;
            if (!world.isChunkLoaded(machLoc.getBlockX() >> 4, machLoc.getBlockZ() >> 4)) return;
            if (player != null && player.isOnline())
                playSound(player, SND_AMETHYST_CHIME, 1.0f, 0.5f); // deep, resonant
            world.strikeLightningEffect(machLoc);
            world.spawnParticle(Particle.END_ROD, center,
                    crowded ? 50 : 35, 0.5, 0.5, 0.5, 0.08);
            world.spawnParticle(Particle.PORTAL, center,
                    crowded ? 60 : 40, 0.6, 0.6, 0.6, 0.12);
            world.playSound(center, SND_AMETHYST_CHIME, crowded ? 2.0f : 1.2f, 0.5f);
            world.playSound(center, SND_CHALLENGE_DONE, crowded ? 3.0f : 2.0f, 1.2f);
        }, 20L);
    }

    /**
     * Dragon resonance — the void acknowledges something ancient.
     * Slow sequence: portal wash → dragon growl echo → two delayed lightning strikes.
     */
    private void playJackpotDragon(@Nullable Player player,
                                    @NotNull Location machLoc,
                                    boolean crowded) {
        World world = machLoc.getWorld();
        Location center = machLoc.clone().add(0.5, 1.0, 0.5);
        if (player != null && player.isOnline())
            playSound(player, SND_DRAGON_AMBIENT, 1.0f, 0.8f);
        if (world != null) {
            world.spawnParticle(Particle.PORTAL, center,
                    crowded ? 60 : 40, 0.5, 0.6, 0.5, 0.10);
            world.spawnParticle(Particle.END_ROD, center,
                    crowded ? 25 : 15, 0.4, 0.4, 0.4, 0.04);
            world.playSound(center, SND_DRAGON_AMBIENT, crowded ? 1.5f : 0.9f, 0.8f);
            // First lightning — slow, dramatic.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (world.isChunkLoaded(machLoc.getBlockX() >> 4, machLoc.getBlockZ() >> 4)) {
                    world.strikeLightningEffect(machLoc);
                    world.spawnParticle(Particle.TOTEM_OF_UNDYING, center,
                            25, 0.4, 0.4, 0.4, 0.08);
                }
            }, 8L);
            // Second lightning — deep amethyst chime.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (world.isChunkLoaded(machLoc.getBlockX() >> 4, machLoc.getBlockZ() >> 4)) {
                    world.strikeLightningEffect(machLoc);
                    world.playSound(center, SND_AMETHYST_CHIME,
                            crowded ? 1.2f : 0.8f, 0.55f);
                    world.spawnParticle(Particle.TOTEM_OF_UNDYING, center,
                            crowded ? 30 : 18, 0.4, 0.4, 0.4, 0.09);
                }
            }, 22L);
        }
    }

    /**
     * Void echo — chaotic, overwhelming. The machine couldn't contain it.
     * Wither scream + double challenge-done + rapid double lightning.
     */
    private void playJackpotEcho(@Nullable Player player,
                                  @NotNull Location machLoc,
                                  boolean crowded) {
        World world = machLoc.getWorld();
        Location center = machLoc.clone().add(0.5, 1.0, 0.5);
        if (player != null && player.isOnline()) {
            playSound(player, SND_WITHER_SPAWN, 0.8f, 1.1f);
            playSound(player, SND_CHALLENGE_DONE, 1.0f, 0.9f);
        }
        if (world != null) {
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, center,
                    crowded ? 80 : 60, 0.6, 0.6, 0.6, 0.18);
            world.spawnParticle(Particle.PORTAL, center,
                    crowded ? 50 : 35, 0.5, 0.5, 0.5, 0.14);
            world.strikeLightningEffect(machLoc);
            world.playSound(center, SND_CHALLENGE_DONE, crowded ? 5.0f : 3.5f, 0.9f);
            // Rapid second strike — echo.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (world.isChunkLoaded(machLoc.getBlockX() >> 4, machLoc.getBlockZ() >> 4)) {
                    world.strikeLightningEffect(machLoc);
                    world.spawnParticle(Particle.END_ROD, center,
                            crowded ? 35 : 25, 0.45, 0.45, 0.45, 0.07);
                    world.playSound(center, SND_CHALLENGE_DONE,
                            crowded ? 4.0f : 2.5f, 1.1f);
                }
            }, 6L);
        }
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private void playSound(@NotNull Player player, @NotNull String soundKey,
                           float volume, float pitch) {
        player.playSound(player.getLocation(), soundKey, volume, pitch);
    }

    private void giveOrDrop(@NotNull Player player, @NotNull ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (overflow.isEmpty()) return;
        Location loc = player.getLocation();
        for (ItemStack drop : overflow.values()) {
            loc.getWorld().dropItemNaturally(loc, drop);
        }
    }

    /**
     * Remove the animation from the active map and despawn the display entity.
     * Boss bar removal is caller's responsibility (so the reveal title stays visible
     * for the configured hold period before hide).
     */
    private void cleanupAnimation(@NotNull AnimationContext ctx, @NotNull UUID uuid) {
        active.remove(uuid);
        despawnDisplay(ctx.display);
        ctx.display = null;
        CinematicGui cg = cinematicGui;
        if (cg != null) cg.close(uuid);
        // Release ritual lock — animation complete, player is free.
        RitualLockService rl = ritualLockService;
        if (rl != null) rl.unlock(uuid);
    }

    // ─── Text / colour helpers ───────────────────────────────────────────────

    @NotNull
    private static BossBar.Color revealBarColor(@NotNull Outcome outcome) {
        return switch (outcome) {
            case DESTROYED  -> BossBar.Color.RED;
            case RETURNED   -> BossBar.Color.WHITE;
            case DOUBLED    -> BossBar.Color.GREEN;
            case TRIPLED    -> BossBar.Color.YELLOW;
            case JACKPOT_X5 -> BossBar.Color.PINK;
        };
    }

    // =========================================================================
    //  Animation context (per active transaction)
    // =========================================================================

    /**
     * Mutable state for a single running animation.
     * All fields accessed on the main thread only.
     */
    private static final class AnimationContext {

        final Transaction tx;
        final BossBar bossBar;
        @Nullable ItemDisplay display;
        final int rampSteps;
        final int stepTicks;
        final int tensionTicks;

        /**
         * Outcome pre-rolled in {@link AnimationPipeline#start} before animation begins.
         * The reels are presentation only — this value is the source of truth.
         */
        final Outcome outcome;

        /**
         * Output item count pre-computed from {@link #outcome} in
         * {@link AnimationPipeline#start}.  Used at reveal to avoid a second roll.
         */
        final int outputAmount;

        /**
         * Pre-rolled fakeout flag. When {@code true}, the reveal phase plays a fake
         * CONSUMED result before snapping to the real outcome. Only possible for
         * DOUBLED, TRIPLED, and JACKPOT_X5.
         */
        final boolean isFakeout;

        /**
         * Pre-rolled jackpot variant; non-null only for {@link Outcome#JACKPOT_X5}.
         * Determines which visual/audio sequence plays at reveal time.
         */
        @Nullable final JackpotVariant jackpotVariant;

        /** Current ramp step count. Incremented each timer fire. */
        int step;
        /** Set to {@code true} to abort on next timer tick. */
        volatile boolean cancelled;
        /** Reference to the ramp BukkitTask; set immediately after scheduling. */
        @Nullable BukkitTask rampTask;

        AnimationContext(@NotNull Transaction tx,
                         @NotNull BossBar bossBar,
                         @Nullable ItemDisplay display,
                         int rampSteps,
                         int stepTicks,
                         int tensionTicks,
                         @NotNull Outcome outcome,
                         int outputAmount,
                         boolean isFakeout,
                         @Nullable JackpotVariant jackpotVariant) {
            this.tx             = tx;
            this.bossBar        = bossBar;
            this.display        = display;
            this.rampSteps      = rampSteps;
            this.stepTicks      = stepTicks;
            this.tensionTicks   = tensionTicks;
            this.outcome        = outcome;
            this.outputAmount   = outputAmount;
            this.isFakeout      = isFakeout;
            this.jackpotVariant = jackpotVariant;
        }
    }
}
