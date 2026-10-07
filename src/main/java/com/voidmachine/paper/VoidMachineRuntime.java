package com.voidmachine.paper;

import com.voidmachine.core.budget.EffectBudget;
import com.voidmachine.core.config.ConfigProblem;
import com.voidmachine.core.config.Settings;
import com.voidmachine.core.custody.CustodyEngine;
import com.voidmachine.core.health.HealthMonitor;
import com.voidmachine.core.journal.FileJournal;
import com.voidmachine.core.journal.Journal;
import com.voidmachine.core.journal.JournalException;
import com.voidmachine.core.machine.MachineRecord;
import com.voidmachine.core.outcome.OutcomeEngine;
import com.voidmachine.core.outcome.RandomSource;
import com.voidmachine.core.script.Ritualbook;
import com.voidmachine.core.stats.StatsBook;
import com.voidmachine.core.stats.StatsStore;
import com.voidmachine.paper.audit.AuditLog;
import com.voidmachine.paper.command.Confirmations;
import com.voidmachine.paper.command.VoidMachineCommand;
import com.voidmachine.paper.config.ConfigService;
import com.voidmachine.paper.config.PaperGameRegistry;
import com.voidmachine.paper.i18n.BedrockDetector;
import com.voidmachine.paper.i18n.Messages;
import com.voidmachine.paper.listener.MachineListener;
import com.voidmachine.paper.listener.PlayerListener;
import com.voidmachine.paper.listener.WorldListener;
import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.machine.MachineRegistry;
import com.voidmachine.paper.machine.MachineStore;
import com.voidmachine.paper.menu.MenuListener;
import com.voidmachine.paper.migration.LegacyDataMigrator;
import com.voidmachine.paper.presentation.AmbientService;
import com.voidmachine.paper.presentation.DisplayService;
import com.voidmachine.paper.presentation.RitualDirector;
import com.voidmachine.paper.ritual.CustodyService;
import com.voidmachine.paper.ritual.JournalService;
import com.voidmachine.paper.ritual.RitualService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Wires VoidMachine together and owns its lifecycle. Gameplay opens only after recovery state is known.
 */
public final class VoidMachineRuntime {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Path dataDir;
    private final long sessionStart = System.currentTimeMillis();

    private final HealthMonitor health = new HealthMonitor(Clock.systemUTC());
    private final Confirmations confirmations = new Confirmations();
    private final List<BukkitTask> tasks = new ArrayList<>();

    private NamespacedKey ledgerKey;
    private NamespacedKey rewardKey;
    private NamespacedKey displayKey;

    private AuditLog audit;
    private ConfigService config;
    private BedrockDetector bedrock;
    private Messages messages;
    private JournalService journal;
    private CustodyService custody;
    private MachineRegistry machines;
    private MachineStore machineStore;
    private StatsBook stats = new StatsBook();
    private StatsStore statsStore;
    private OutcomeEngine engine;
    private RitualService rituals;
    private RitualDirector director;
    private DisplayService displays;
    private AmbientService ambient;
    private EffectBudget budget;
    private MachineListener machineListener;

    private final List<String> startupNotes = new ArrayList<>();
    private int quarantinedAtStart;
    private int recordsAtStart;
    private int orphansRemovedAtStart;

    public VoidMachineRuntime(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.dataDir = plugin.getDataFolder().toPath();
    }

    // ------------------------------------------------------------------------------------------
    // Startup
    // ------------------------------------------------------------------------------------------

    public void start() {
        ledgerKey = new NamespacedKey(plugin, "ledger");
        rewardKey = new NamespacedKey(plugin, "reward");
        displayKey = new NamespacedKey(plugin, "display");

        // 1-2. Configuration: defaults, V1 migration, validation.
        config = new ConfigService(plugin, dataDir, logger, new PaperGameRegistry());
        try {
            startupNotes.addAll(config.prepare());
        } catch (IOException e) {
            logger.severe("Could not prepare configuration files: " + e.getMessage());
        }
        ConfigService.Outcome cfg = config.load();
        config.report(cfg.problems());
        if (!cfg.ok()) {
            health.raise(HealthMonitor.Source.CONFIG, cfg.errors().size() + " configuration error(s); see the console. "
                    + "Fix them and run /vm admin reload. Recovery keeps working; new offerings are refused.");
        }
        Settings s = config.settings();

        audit = new AuditLog(dataDir.resolve("audit"), logger, health);
        audit.start(s == null || s.logging().auditEnabled(), s == null ? 30 : s.logging().auditRetentionDays());

        bedrock = new BedrockDetector(logger);
        messages = new Messages(plugin, dataDir.resolve("lang"), bedrock, logger);
        for (String problem : messages.reload(s == null ? null : s.language())) logger.warning(problem);

        // 3. Persistence.
        FileJournal fileJournal = new FileJournal(dataDir.resolve("journal"));
        journal = new JournalService(plugin, fileJournal, health, logger);
        statsStore = new StatsStore(dataDir.resolve("stats.json"));
        try {
            stats = statsStore.load();
        } catch (IOException e) {
            logger.warning("Statistics: " + e.getMessage());
            health.raise(HealthMonitor.Source.STATS, e.getMessage());
            stats = new StatsBook();
        }

        // 4. Machines.
        machines = new MachineRegistry();
        machineStore = new MachineStore(dataDir.resolve("machines.yml"), config.backupDir(), logger);
        try {
            MachineStore.Loaded loaded = machineStore.load();
            machines.load(loaded.machines());
            for (ConfigProblem p : loaded.problems()) logger.warning(p.render());
        } catch (IOException e) {
            logger.severe("machines.yml could not be read (" + e.getMessage() + "); no machines are active. The file is left untouched.");
            health.raise(HealthMonitor.Source.CONFIG, "machines.yml unreadable: " + e.getMessage());
        }

        // 5-6. Pending records: V1 conversion, then load + quarantine report.
        LegacyDataMigrator.Report legacy = new LegacyDataMigrator(dataDir, config.backupDir(), fileJournal, logger).run(stats);
        startupNotes.addAll(legacy.notes());
        if (legacy.unreadable() > 0) health.raise(HealthMonitor.Source.RECOVERY, legacy.unreadable() + " V1 entries need manual review");
        try {
            Journal.LoadResult load = journal.loadSync();
            recordsAtStart = load.records().size();
            quarantinedAtStart = load.quarantined().size();
            for (Journal.Quarantined q : load.quarantined()) {
                logger.severe("Journal record " + q.fileName() + " is corrupt (" + q.reason() + ") and was moved to journal/quarantine/. "
                        + "It is kept for an admin; nothing was deleted.");
            }
            if (quarantinedAtStart > 0) {
                health.raise(HealthMonitor.Source.QUARANTINE, quarantinedAtStart + " corrupt journal record(s) in journal/quarantine/");
            }
        } catch (JournalException e) {
            logger.severe("The journal is not usable: " + e.getMessage() + ". New offerings are refused until storage works.");
            health.raise(HealthMonitor.Source.STORAGE, e.getMessage());
        }

        // Services.
        engine = new OutcomeEngine(RandomSource.secure());
        budget = new EffectBudget(s == null ? 1200 : s.limits().maxParticlesPerTick(), s == null ? 400 : s.limits().maxPacketsPerTick());
        displays = new DisplayService(displayKey);
        custody = new CustodyService(plugin, journal, messages, config::settings, ledgerKey, rewardKey, health, audit, logger, sessionStart);
        rituals = new RitualService(journal, custody, machines, messages, config::settings, config::book, engine, health, audit, stats, logger);
        director = new RitualDirector(plugin, messages, config::settings, displays, budget, health, logger);
        rituals.bind(director);
        director.bind(rituals);
        custody.bind(rituals, r -> {
            Machine m = machines.byId(r.machineId());
            return m == null ? null : m.view();
        });
        ambient = new AmbientService(plugin, machines, config::settings, config::book, messages);

        // 7. Recovery for players already online (plugin reload): live ledger, never treated as proof.
        for (Player p : Bukkit.getOnlinePlayers()) custody.reconcile(p, CustodyEngine.Mode.LIVE);

        // 8. Orphaned visuals near machines in loaded chunks.
        for (Machine m : machines.all()) {
            World w = m.world();
            if (w == null || !m.isChunkLoaded()) continue;
            Chunk c = w.getChunkAt(m.record().x() >> 4, m.record().z() >> 4);
            orphansRemovedAtStart += displays.sweep(c);
        }

        // 9. Listeners.
        machineListener = new MachineListener(plugin, machines, rituals, custody, messages, config::settings);
        Bukkit.getPluginManager().registerEvents(machineListener, plugin);
        Bukkit.getPluginManager().registerEvents(new MenuListener(), plugin);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(plugin, custody, rituals, director, bedrock, config::settings,
                machineListener::forget), plugin);
        Bukkit.getPluginManager().registerEvents(new WorldListener(machines, rituals, displays, logger), plugin);

        // 10. Commands.
        PluginCommand command = plugin.getCommand("voidmachine");
        if (command == null) throw new IllegalStateException("command 'voidmachine' missing from plugin.yml");
        VoidMachineCommand executor = new VoidMachineCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        // Background work.
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, custody::retryTick, 40L, 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> custody.sweepOffline(10), 1200L, 6000L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::healthTick, 600L, 600L));
        int statsEvery = (s == null ? 60 : s.stats().saveIntervalSeconds()) * 20;
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> saveStats(false), statsEvery, statsEvery));
        ambient.start();

        // 11. Report.
        report();
        audit.log(AuditLog.Event.HEALTH_CHANGED, Map.of("state", health.state().name(), "phase", "startup"));
    }

    private void report() {
        for (String note : startupNotes) logger.warning("[Migration] " + note);
        long dormant = machines.all().stream().filter(m -> m.world() == null).count();
        logger.info("VoidMachine " + plugin.getPluginMeta().getVersion() + " initialized");
        logger.info("  configuration: " + (config.settings() == null ? "INVALID (offerings refused)" : "validated"));
        logger.info("  machines loaded: " + machines.size() + (dormant > 0 ? " (" + dormant + " waiting for their world to load)" : ""));
        logger.info("  pending records: " + recordsAtStart + " (settled when their players join)"
                + (quarantinedAtStart > 0 ? ", " + quarantinedAtStart + " quarantined" : ""));
        if (orphansRemovedAtStart > 0) logger.info("  orphaned displays removed: " + orphansRemovedAtStart);
        logger.info("  bedrock detection: " + bedrock.source());
        logger.info("  health: " + health.state() + (health.acceptsRituals() ? " — ready" : " — offerings refused until fixed"));
    }

    private void healthTick() {
        if (health.problem(HealthMonitor.Source.STORAGE).isPresent()) {
            journal.probe(ok -> {
                if (ok) logger.info("Journal storage is writable again; offerings are accepted.");
            });
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reload
    // ------------------------------------------------------------------------------------------

    /** Reloads config.yml, rituals.yml and languages. Running rituals keep the settings they started with. */
    public ConfigService.Outcome reload() {
        ConfigService.Outcome out = config.load();
        config.report(out.problems());
        if (out.ok()) {
            health.clear(HealthMonitor.Source.CONFIG);
            Settings s = out.settings();
            for (String p : messages.reload(s.language())) logger.warning(p);
            director.reload(s);
            ambient.start();
            for (Machine m : machines.all()) {
                if (!s.profiles().containsKey(m.record().profile())) {
                    logger.warning("Machine '" + m.id() + "' uses profile '" + m.record().profile() + "', which no longer exists; it refuses offerings until "
                            + "you set a valid profile (/vm admin profile " + m.id() + " <profile>).");
                }
            }
            audit.log(AuditLog.Event.CONFIG_RELOADED, Map.of("ok", true, "warnings", out.problems().size()));
        } else {
            audit.log(AuditLog.Event.CONFIG_RELOADED, Map.of("ok", false, "errors", out.errors().size()));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------------------------------

    public void stop() {
        List<UUID> preparing = rituals == null ? List.of() : rituals.shutdown();
        if (director != null) director.stop();
        if (ambient != null) ambient.stop();
        if (displays != null) displays.removeAll();
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        if (journal != null) {
            journal.drain(5_000);
            // These rituals never took anything; their records (if written) are not needed.
            for (UUID id : preparing) journal.deleteNow(id);
        }
        saveStats(true);
        if (audit != null) audit.stop();
    }

    public void saveStats(boolean sync) {
        Settings s = config == null ? null : config.settings();
        if (statsStore == null || (s != null && !s.stats().enabled()) || !stats.isDirty()) return;
        StatsBook snapshot = stats.copy();
        stats.markClean();
        Runnable write = () -> {
            try {
                statsStore.save(snapshot);
                health.clear(HealthMonitor.Source.STATS);
            } catch (IOException e) {
                health.raise(HealthMonitor.Source.STATS, "stats.json cannot be written: " + e.getMessage());
            }
        };
        if (sync) write.run();
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
    }

    public void saveMachines() throws IOException {
        List<MachineRecord> records = machines.all().stream().map(Machine::record).toList();
        machineStore.save(records);
    }

    // ------------------------------------------------------------------------------------------
    // Accessors for commands and diagnostics
    // ------------------------------------------------------------------------------------------

    public JavaPlugin plugin() {
        return plugin;
    }

    public Logger logger() {
        return logger;
    }

    public HealthMonitor health() {
        return health;
    }

    public AuditLog audit() {
        return audit;
    }

    public ConfigService config() {
        return config;
    }

    public Settings settings() {
        return config.settings();
    }

    public Ritualbook book() {
        return config.book();
    }

    public Messages messages() {
        return messages;
    }

    public BedrockDetector bedrock() {
        return bedrock;
    }

    public JournalService journal() {
        return journal;
    }

    public CustodyService custody() {
        return custody;
    }

    public MachineRegistry machines() {
        return machines;
    }

    public StatsBook stats() {
        return stats;
    }

    public RitualService rituals() {
        return rituals;
    }

    public RitualDirector director() {
        return director;
    }

    public DisplayService displays() {
        return displays;
    }

    public AmbientService ambient() {
        return ambient;
    }

    public EffectBudget budget() {
        return budget;
    }

    public Confirmations confirmations() {
        return confirmations;
    }

    public long sessionStart() {
        return sessionStart;
    }

    public int quarantinedAtStart() {
        return quarantinedAtStart;
    }
}
