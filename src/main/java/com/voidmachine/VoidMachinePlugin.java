/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine;

import com.voidmachine.animation.AmbientEffectScheduler;
import com.voidmachine.animation.AnimationPipeline;
import com.voidmachine.animation.AnimationWatchdog;
import com.voidmachine.animation.CinematicGui;
import com.voidmachine.animation.CinematicGuiListener;
import com.voidmachine.animation.StagingGui;
import com.voidmachine.animation.StagingGuiListener;
import com.voidmachine.interaction.PlayerDeathListener;
import com.voidmachine.audit.AuditLogger;
import com.voidmachine.checkpoint.CheckpointStore;
import com.voidmachine.checkpoint.PendingDeliveryQueue;
import com.voidmachine.checkpoint.StartupRecovery;
import com.voidmachine.command.VoidMachineCommand;
import com.voidmachine.config.MessageManager;
import com.voidmachine.config.PluginConfig;
import com.voidmachine.core.OutcomeRoller;
import com.voidmachine.db.DatabaseManager;
import com.voidmachine.gui.GuiManager;
import com.voidmachine.integration.DiscordHook;
import com.voidmachine.interaction.ItemCaptureService;
import com.voidmachine.interaction.MachineBlockListener;
import com.voidmachine.interaction.MachineInteractionListener;
import com.voidmachine.db.GlobalStats;
import com.voidmachine.machine.MachineDataStore;
import com.voidmachine.machine.MachineRegistry;
import com.voidmachine.service.BlacklistService;
import com.voidmachine.service.CooldownService;
import com.voidmachine.service.ProcessingService;
import com.voidmachine.service.RitualLockListener;
import com.voidmachine.service.RitualLockService;
import com.voidmachine.service.StatsService;
import com.voidmachine.transaction.TransactionRegistry;
import com.voidmachine.util.Effects;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * Entry point for the VoidMachine plugin.
 *
 * <p>The plugin follows a strict service-locator pattern: services are constructed
 * during {@link #onEnable()} and exposed via getters. Services receive their own
 * dependencies through their constructors so that the wiring is explicit and unit
 * tests can substitute fakes.</p>
 */
public final class VoidMachinePlugin extends JavaPlugin {

    // ── Legacy GUI services (Phase 1–2) ─────────────────────────────────────
    private PluginConfig pluginConfig;
    private MessageManager messages;
    private DatabaseManager database;
    private BlacklistService blacklist;
    private CooldownService cooldowns;
    private StatsService stats;
    private OutcomeRoller roller;
    private Effects effects;
    private ProcessingService processing;
    private GuiManager gui;
    private DiscordHook discord;

    // ── Physical machine services (Phase 2+) ────────────────────────────────
    private AuditLogger audit;
    private CheckpointStore checkpoints;
    private PendingDeliveryQueue pendingDeliveries;
    private MachineRegistry machineRegistry;
    private MachineDataStore machineDataStore;
    private TransactionRegistry txRegistry;
    private AnimationWatchdog watchdog;
    private ItemCaptureService captureService;
    private AnimationPipeline animationPipeline;
    private CinematicGui cinematicGui;
    private StagingGui stagingGui;
    private AmbientEffectScheduler ambientEffects;
    private GlobalStats globalStats;
    private RitualLockService ritualLockService;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
    }

    @Override
    public void onEnable() {
        try {
            printBanner();
            bootstrap();
            getLogger().info("VoidMachine enabled. Sacrifice responsibly.");
        } catch (Exception ex) {
            getLogger().severe("VoidMachine failed to enable: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void printBanner() {
        String version = getPluginMeta().getVersion();
        String[] lines = {
                "",
                "  __     __    _    _ __  __         _   _         ",
                "  \\ \\   / /__ (_) __| |  \\/  | __ _ ___| |_(_)_ __  ___ ",
                "   \\ \\ / / _ \\| |/ _` | |\\/| |/ _` / __| __| | '_ \\/ _ \\",
                "    \\ V / (_) | | (_| | |  | | (_| \\__ \\ |_| | | | |  __/",
                "     \\_/ \\___/|_|\\__,_|_|  |_|\\__,_|___/\\__|_|_| |_|\\___|",
                "",
                "  VoidMachine v" + version,
                "  Created by Mordechai Neeman <neeman2009@gmail.com>",
                "  https://github.com/MordechaiNeeman/VoidMachine",
                "  Licensed under the MIT License.",
                ""
        };
        for (String line : lines) {
            getLogger().info(line);
        }
    }

    @Override
    public void onDisable() {
        try {
            // Ambient effects first — purely cosmetic, safe to kill immediately.
            if (ambientEffects != null)   ambientEffects.shutdown();
            // Stop animation pipeline before aborting transactions
            // so display entities and boss bars are cleaned up first.
            if (stagingGui != null)       stagingGui.shutdown();
            if (cinematicGui != null)     cinematicGui.shutdown();
            if (animationPipeline != null) animationPipeline.shutdownAll();
            // Abort all active physical-machine transactions and return items.
            if (watchdog != null) watchdog.shutdown();
            if (txRegistry != null) {
                for (com.voidmachine.core.Transaction tx : txRegistry.snapshot()) {
                    if (captureService != null) {
                        captureService.abortTransaction(tx.playerId(), "server_shutdown");
                    }
                }
            }
            // Final safety net — guarantees no player is left permanently locked
            // regardless of which path was taken above.
            if (ritualLockService != null) ritualLockService.unlockAll();
            if (audit != null) audit.shutdown();
            if (gui != null) gui.shutdown();
            if (processing != null) processing.shutdown();
            if (database != null) database.shutdown();
        } catch (Exception ex) {
            getLogger().warning("Error during shutdown: " + ex.getMessage());
        }
        getLogger().info("VoidMachine disabled.");
    }

    private void bootstrap() {
        reloadConfig();
        this.pluginConfig = new PluginConfig(this);
        this.messages     = new MessageManager(this, pluginConfig);
        this.database     = new DatabaseManager(this, pluginConfig);
        database.start();

        // ── Legacy GUI services ─────────────────────────────────────────────
        this.blacklist  = new BlacklistService(pluginConfig);
        this.cooldowns  = new CooldownService(this, pluginConfig, database);
        this.stats      = new StatsService(this, database);
        this.roller     = new OutcomeRoller(pluginConfig);
        this.effects    = new Effects(this, pluginConfig);
        this.discord    = new DiscordHook(this, pluginConfig, messages);
        this.processing = new ProcessingService(this, pluginConfig, messages, roller, stats, effects, discord);
        this.gui        = new GuiManager(this, pluginConfig, messages, blacklist, cooldowns, processing, effects);

        // ── Physical machine services ───────────────────────────────────────
        this.audit            = new AuditLogger(this);
        this.audit.start();
        this.checkpoints      = new CheckpointStore(this);
        this.pendingDeliveries = new PendingDeliveryQueue(this);
        this.machineRegistry  = new MachineRegistry();
        this.machineDataStore = new MachineDataStore(this);
        this.txRegistry       = new TransactionRegistry();
        this.watchdog         = new AnimationWatchdog(this, pluginConfig.worldAnimMaxDurationTicks());
        this.captureService   = new ItemCaptureService(
                this, pluginConfig, checkpoints, txRegistry, watchdog,
                blacklist, audit, pendingDeliveries, machineRegistry, roller);

        // Wire animation pipeline (Phase 4).
        this.animationPipeline = new AnimationPipeline(
                this, pluginConfig, messages, checkpoints, machineRegistry,
                pendingDeliveries, audit, roller, captureService);
        captureService.setAfterCapture(animationPipeline::start);
        captureService.setAnimationPipeline(animationPipeline);

        // Wire cinematic GUI into animation pipeline.
        this.cinematicGui = new CinematicGui(this, messages);
        animationPipeline.setCinematicGui(cinematicGui);

        // Global lifetime stats — loaded from disk, updated on each completed transaction.
        this.globalStats = new GlobalStats(this);
        animationPipeline.setGlobalStats(globalStats);

        // Staging GUI — pre-commit step (opened by MachineInteractionListener on right-click).
        this.stagingGui = new StagingGui(this, messages, captureService);

        // Ritual lock — applied at START commit, released on all exit paths.
        this.ritualLockService = new RitualLockService(this);
        stagingGui.setRitualLockService(ritualLockService);
        animationPipeline.setRitualLockService(ritualLockService);
        // Wire into captureService so abortTransaction can unlock on any abort path,
        // including early exits before the AnimationContext is registered.
        captureService.setRitualLockService(ritualLockService);

        // Load machine registrations from disk and populate the registry.
        for (com.voidmachine.machine.MachineBlock m : machineDataStore.load()) {
            if (!machineRegistry.register(m)) {
                getLogger().warning("[Bootstrap] Could not register machine '" + m.name()
                        + "' at " + m.locationKey() + " — duplicate location or name?");
            }
        }
        getLogger().info("[Bootstrap] Registered " + machineRegistry.size() + " machine(s).");

        // Run startup recovery BEFORE registering listeners.
        StartupRecovery recovery = new StartupRecovery(
                checkpoints, pendingDeliveries, audit, pluginConfig, getLogger());
        recovery.run();

        // ── Register listeners ──────────────────────────────────────────────
        // NOTE: InventoryListener and PlayerQuitListener (legacy GUI) are NOT
        // registered — the GUI flow is disabled. Physical machine interaction
        // is the only player-facing path.
        Bukkit.getPluginManager().registerEvents(pendingDeliveries, this);
        Bukkit.getPluginManager().registerEvents(new CinematicGuiListener(cinematicGui), this);
        Bukkit.getPluginManager().registerEvents(new StagingGuiListener(stagingGui, this), this);
        Bukkit.getPluginManager().registerEvents(new RitualLockListener(ritualLockService), this);
        Bukkit.getPluginManager().registerEvents(
                new PlayerDeathListener(stagingGui, txRegistry, captureService), this);

        MachineInteractionListener interactionListener = new MachineInteractionListener(
                pluginConfig, machineRegistry, txRegistry, stagingGui, audit);
        Bukkit.getPluginManager().registerEvents(interactionListener, this);

        Bukkit.getPluginManager().registerEvents(new MachineBlockListener(
                pluginConfig, machineRegistry, txRegistry, captureService, getLogger()), this);

        // ── Ambient atmosphere ──────────────────────────────────────────────
        this.ambientEffects = new AmbientEffectScheduler(this, pluginConfig, machineRegistry);
        if (pluginConfig.atmosphereEnabled()) {
            ambientEffects.start();
        }

        // ── Commands ────────────────────────────────────────────────────────
        PluginCommand command = Objects.requireNonNull(getCommand("voidmachine"),
                "voidmachine command is missing from plugin.yml");
        VoidMachineCommand executor = new VoidMachineCommand(
                this, pluginConfig, messages, machineRegistry, machineDataStore, globalStats);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /**
     * Reload configuration, language file, and rebuild dependent services.
     * Returns {@code true} on success.
     */
    public boolean reloadAll() {
        try {
            reloadConfig();
            pluginConfig.reload();
            messages.reload();
            blacklist.reload();
            cooldowns.reload();
            roller.reload();
            effects.reload();
            discord.reload();
            // Restart ambient scheduler so atmosphere.enabled change takes effect.
            if (ambientEffects != null) {
                ambientEffects.shutdown();
                if (pluginConfig.atmosphereEnabled()) ambientEffects.start();
            }
            return true;
        } catch (Exception ex) {
            getLogger().severe("Reload failed: " + ex.getMessage());
            ex.printStackTrace();
            return false;
        }
    }

    public PluginConfig pluginConfig()             { return pluginConfig; }
    public MessageManager messages()               { return messages; }
    public DatabaseManager database()              { return database; }
    public BlacklistService blacklist()            { return blacklist; }
    public CooldownService cooldowns()             { return cooldowns; }
    public StatsService stats()                    { return stats; }
    public OutcomeRoller roller()                  { return roller; }
    public Effects effects()                       { return effects; }
    public ProcessingService processing()          { return processing; }
    public GuiManager gui()                        { return gui; }
    public DiscordHook discord()                   { return discord; }

    // Physical machine getters
    public AuditLogger audit()                     { return audit; }
    public CheckpointStore checkpoints()           { return checkpoints; }
    public PendingDeliveryQueue pendingDeliveries(){ return pendingDeliveries; }
    public MachineRegistry machineRegistry()       { return machineRegistry; }
    public MachineDataStore machineDataStore()     { return machineDataStore; }
    public TransactionRegistry txRegistry()        { return txRegistry; }
    public AnimationWatchdog watchdog()            { return watchdog; }
    public ItemCaptureService captureService()     { return captureService; }
    public AnimationPipeline animationPipeline()   { return animationPipeline; }
    public AmbientEffectScheduler ambientEffects() { return ambientEffects; }
    public GlobalStats globalStats()               { return globalStats; }
    public RitualLockService ritualLockService()   { return ritualLockService; }
}
