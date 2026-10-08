package com.voidmachine.paper.config;

import com.voidmachine.core.config.ConfigProblem;
import com.voidmachine.core.config.GameRegistry;
import com.voidmachine.core.config.Settings;
import com.voidmachine.core.config.SettingsLoader;
import com.voidmachine.core.migration.V1ConfigMigration;
import com.voidmachine.core.script.Ritualbook;
import com.voidmachine.core.script.RitualsLoader;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Owns {@code config.yml} and {@code rituals.yml}: writes defaults on first start, migrates a V1
 * configuration (keeping the V1 file as a backup), and validates on load and reload. A reload that
 * fails validation keeps the previous, working configuration.
 */
public final class ConfigService {

    public record Outcome(Settings settings, Ritualbook book, List<ConfigProblem> problems, List<String> notes) {
        public boolean ok() {
            return settings != null && book != null;
        }

        public List<ConfigProblem> errors() {
            return problems.stream().filter(p -> p.severity() == ConfigProblem.Severity.ERROR).toList();
        }
    }

    private final Plugin plugin;
    private final Path dataDir;
    private final Path backupDir;
    private final Logger logger;
    private final GameRegistry registry;
    private volatile Settings settings;
    private volatile Ritualbook book;

    public ConfigService(Plugin plugin, Path dataDir, Logger logger, GameRegistry registry) {
        this.plugin = plugin;
        this.dataDir = dataDir;
        this.backupDir = dataDir.resolve("backup").resolve("v1");
        this.logger = logger;
        this.registry = registry;
    }

    public Path backupDir() {
        return backupDir;
    }

    /** Writes missing default files and migrates a V1 config. Returns migration notes. */
    public List<String> prepare() throws IOException {
        Files.createDirectories(dataDir);
        List<String> notes = new ArrayList<>();
        Path config = dataDir.resolve("config.yml");
        if (Files.exists(config)) {
            Map<String, Object> tree;
            try {
                tree = Yamls.read(config);
            } catch (Yamls.YamlSyntaxException e) {
                tree = null; // reported by load()
            }
            if (tree != null && V1ConfigMigration.isV1(tree)) notes.addAll(migrate(config, tree));
        } else {
            copyResource("config.yml", config);
        }
        Path rituals = dataDir.resolve("rituals.yml");
        if (!Files.exists(rituals)) copyResource("rituals.yml", rituals);
        Files.createDirectories(dataDir.resolve("lang"));
        for (String lang : List.of("he", "en")) {
            Path f = dataDir.resolve("lang").resolve(lang + ".yml");
            if (!Files.exists(f)) copyResource("lang/" + lang + ".yml", f);
        }
        Path oldMessages = dataDir.resolve("messages.yml");
        if (Files.exists(oldMessages)) {
            Files.createDirectories(backupDir);
            Files.move(oldMessages, backupDir.resolve("messages.yml"), StandardCopyOption.REPLACE_EXISTING);
            notes.add("V1 messages.yml was moved to backup/v1/ (V2 text lives in lang/he.yml and lang/en.yml).");
        }
        return notes;
    }

    private List<String> migrate(Path config, Map<String, Object> v1) throws IOException {
        Files.createDirectories(backupDir);
        Files.copy(config, backupDir.resolve("config.yml"), StandardCopyOption.REPLACE_EXISTING);
        V1ConfigMigration.Result result = V1ConfigMigration.migrate(v1);
        YamlConfiguration v2 = new YamlConfiguration();
        v2.options().parseComments(true);
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) throw new IOException("bundled config.yml missing from the jar");
            v2.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException("bundled config.yml is invalid: " + e.getMessage(), e);
        }
        for (Map.Entry<String, Object> e : result.assignments().entrySet()) v2.set(e.getKey(), e.getValue());
        v2.save(config.toFile());
        List<String> notes = new ArrayList<>();
        notes.add("config.yml was migrated from V1 to V2. Your V1 file is kept at backup/v1/config.yml. Odds are unchanged.");
        notes.addAll(result.notes());
        return notes;
    }

    /** Loads and validates both files. Does not replace the active configuration on failure. */
    public Outcome load() {
        List<ConfigProblem> problems = new ArrayList<>();
        Ritualbook newBook = null;
        Settings newSettings = null;
        try {
            RitualsLoader.Result rr = RitualsLoader.load(Yamls.read(dataDir.resolve("rituals.yml")), registry);
            problems.addAll(rr.problems());
            newBook = rr.book().orElse(null);
        } catch (IOException e) {
            problems.add(new ConfigProblem(ConfigProblem.Severity.ERROR, "rituals.yml", "", e.getMessage(), null, null, null));
        }
        if (newBook != null) {
            try {
                SettingsLoader.Result sr = SettingsLoader.load(Yamls.read(dataDir.resolve("config.yml")), registry, newBook);
                problems.addAll(sr.problems());
                newSettings = sr.settings().orElse(null);
            } catch (IOException e) {
                problems.add(new ConfigProblem(ConfigProblem.Severity.ERROR, "config.yml", "", e.getMessage(), null, null, null));
            }
        } else {
            problems.add(new ConfigProblem(ConfigProblem.Severity.ERROR, "config.yml", "", "not checked because rituals.yml is invalid",
                    null, null, null));
        }
        if (newSettings != null && newBook != null) {
            this.settings = newSettings;
            this.book = newBook;
        }
        return new Outcome(newSettings, newBook, problems, List.of());
    }

    public void report(List<ConfigProblem> problems) {
        for (ConfigProblem p : problems) {
            if (p.severity() == ConfigProblem.Severity.ERROR) logger.severe(p.render());
            else logger.warning(p.render());
        }
    }

    public Settings settings() {
        return settings;
    }

    public Ritualbook book() {
        return book;
    }

    private void copyResource(String resource, Path to) throws IOException {
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) throw new IOException("bundled " + resource + " missing from the jar");
            Files.createDirectories(to.toAbsolutePath().getParent());
            Files.copy(in, to);
        }
    }
}
