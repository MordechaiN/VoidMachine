package com.voidmachine.paper.i18n;

import com.voidmachine.core.config.Settings;
import com.voidmachine.core.text.BidiText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Player-facing text. Every visible string comes from {@code lang/<language>.yml} (MiniMessage).
 *
 * <ul>
 *   <li>Each player sees their own client language when a translation exists, otherwise the default.</li>
 *   <li>Missing keys fall back to the bundled file of the same language, then to English.</li>
 *   <li>Bedrock players receive right-to-left text pre-ordered, because Bedrock draws all text
 *       left-to-right (Java players receive normal logical text; their client orders it).</li>
 * </ul>
 */
public final class Messages {

    public static final List<String> BUNDLED = List.of("he", "en");
    private static final Set<String> RTL_LANGUAGES = Set.of("he", "iw", "ar", "fa", "ur", "yi");

    /** Rendering variant of a viewer: bossbars and broadcasts are built once per variant. */
    public record Variant(String language, boolean visualOrder) {
    }

    private final Plugin plugin;
    private final Path langDir;
    private final BedrockDetector bedrock;
    private final Logger logger;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    private volatile Map<String, Map<String, Object>> languages = Map.of();
    /** Parsed prefix per language (the prefix is part of almost every message). */
    private final Map<String, Component> prefixes = new ConcurrentHashMap<>();
    private volatile Settings.Language settings = new Settings.Language("he", true, true);

    public Messages(Plugin plugin, Path langDir, BedrockDetector bedrock, Logger logger) {
        this.plugin = plugin;
        this.langDir = langDir;
        this.bedrock = bedrock;
        this.logger = logger;
    }

    /** Loads bundled and server language files. Returns human-readable problems (never throws). */
    public List<String> reload(Settings.Language newSettings) {
        if (newSettings != null) this.settings = newSettings;
        prefixes.clear();
        List<String> problems = new ArrayList<>();
        Map<String, Map<String, Object>> loaded = new HashMap<>();
        for (String lang : BUNDLED) {
            Map<String, Object> bundled = new LinkedHashMap<>();
            try (InputStream in = plugin.getResource("lang/" + lang + ".yml")) {
                if (in != null) flatten("", parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)), bundled);
            } catch (IOException | YAMLException e) {
                problems.add("bundled lang/" + lang + ".yml is unreadable: " + e.getMessage());
            }
            loaded.put(lang, bundled);
        }
        if (Files.isDirectory(langDir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(langDir, "*.yml")) {
                for (Path f : files) {
                    String lang = f.getFileName().toString().replace(".yml", "").toLowerCase(Locale.ROOT);
                    try {
                        Map<String, Object> user = new LinkedHashMap<>();
                        flatten("", parse(Files.readString(f, StandardCharsets.UTF_8)), user);
                        Map<String, Object> merged = new LinkedHashMap<>(loaded.getOrDefault(lang, Map.of()));
                        merged.putAll(user);
                        loaded.put(lang, merged);
                        validate(lang, user, problems);
                    } catch (IOException | YAMLException e) {
                        problems.add("lang/" + f.getFileName() + " is not valid YAML and was ignored: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                problems.add("cannot read " + langDir + ": " + e.getMessage());
            }
        }
        if (!loaded.containsKey(settings.defaultLanguage())) {
            problems.add("language.default '" + settings.defaultLanguage() + "' has no lang file; using English");
        }
        this.languages = Map.copyOf(loaded);
        reportedMissing.clear();
        return problems;
    }

    private void validate(String lang, Map<String, Object> user, List<String> problems) {
        for (Map.Entry<String, Object> e : user.entrySet()) {
            List<String> templates = e.getValue() instanceof List<?> l ? l.stream().map(String::valueOf).toList()
                    : List.of(String.valueOf(e.getValue()));
            for (String t : templates) {
                try {
                    mini.deserialize(t, TagResolver.standard(), Placeholder.unparsed("prefix", ""));
                } catch (ParsingException ex) {
                    problems.add("lang/" + lang + ".yml → " + e.getKey() + ": invalid MiniMessage: " + ex.getMessage());
                }
            }
        }
    }

    private static Map<String, Object> parse(String text) {
        Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        if (o == null) return Map.of();
        if (!(o instanceof Map<?, ?> m)) throw new YAMLException("top level must be a map");
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static void flatten(String prefix, Map<?, ?> in, Map<String, Object> out) {
        for (Map.Entry<?, ?> e : in.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> m) flatten(key, m, out);
            else if (e.getValue() != null) out.put(key, e.getValue());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Language selection
    // ------------------------------------------------------------------------------------------

    public Variant variant(Player player) {
        String lang = languageOf(player);
        boolean visual = settings.bedrockRtlFix() && RTL_LANGUAGES.contains(lang) && bedrock.isBedrock(player);
        return new Variant(lang, visual);
    }

    public String languageOf(Player player) {
        Map<String, Map<String, Object>> langs = languages;
        if (settings.useClientLanguage()) {
            Locale locale = player.locale();
            String code = locale.getLanguage().toLowerCase(Locale.ROOT);
            if (code.equals("iw")) code = "he";
            if (langs.containsKey(code)) return code;
        }
        return defaultLanguage();
    }

    public String defaultLanguage() {
        return languages.containsKey(settings.defaultLanguage()) ? settings.defaultLanguage() : "en";
    }

    public Variant defaultVariant() {
        return new Variant(defaultLanguage(), false);
    }

    // ------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------

    public Component render(Player viewer, String key, TagResolver... resolvers) {
        return render(variant(viewer), key, resolvers);
    }

    public Component render(Variant v, String key, TagResolver... resolvers) {
        String template = template(v.language(), key);
        Component c = deserialize(v.language(), template, resolvers);
        return finish(c, v);
    }

    public List<Component> renderList(Variant v, String key, TagResolver... resolvers) {
        Object raw = lookup(v.language(), key);
        List<String> lines;
        if (raw instanceof List<?> l) lines = l.stream().map(String::valueOf).toList();
        else if (raw != null) lines = List.of(String.valueOf(raw).split("\n"));
        else {
            reportMissing(v.language(), key);
            lines = List.of();
        }
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(finish(deserialize(v.language(), line, resolvers), v));
        return out;
    }

    /** Item names and lore: no italics, which Minecraft adds by default to custom names. */
    public Component itemText(Variant v, String key, TagResolver... resolvers) {
        return render(v, key, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public List<Component> itemLore(Variant v, String key, TagResolver... resolvers) {
        return renderList(v, key, resolvers).stream()
                .map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                .toList();
    }

    public boolean has(String key) {
        return lookup(defaultLanguage(), key) != null || lookup("en", key) != null;
    }

    public void send(Player p, String key, TagResolver... resolvers) {
        p.sendMessage(render(p, key, resolvers));
    }

    public void actionBar(Player p, String key, TagResolver... resolvers) {
        p.sendActionBar(render(p, key, resolvers));
    }

    /** Sends one chat message to many players, rendering it once per language variant (not once per player). */
    public void sendAll(Iterable<? extends Player> players, String key, TagResolver... resolvers) {
        Map<Variant, Component> rendered = new HashMap<>(4);
        for (Player p : players) p.sendMessage(rendered.computeIfAbsent(variant(p), v -> render(v, key, resolvers)));
    }

    /** Action-bar counterpart of {@link #sendAll}. */
    public void actionBarAll(Iterable<? extends Player> players, String key, TagResolver... resolvers) {
        Map<Variant, Component> rendered = new HashMap<>(4);
        for (Player p : players) p.sendActionBar(rendered.computeIfAbsent(variant(p), v -> render(v, key, resolvers)));
    }

    public void title(Player p, String titleKey, String subtitleKey, int fadeIn, int stay, int fadeOut, TagResolver... resolvers) {
        Variant v = variant(p);
        Component title = titleKey == null ? Component.empty() : render(v, titleKey, resolvers);
        Component sub = subtitleKey == null ? Component.empty() : render(v, subtitleKey, resolvers);
        p.showTitle(Title.title(title, sub, Title.Times.times(Duration.ofMillis(fadeIn * 50L),
                Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
    }

    private Component deserialize(String lang, String template, TagResolver... resolvers) {
        TagResolver[] all = new TagResolver[resolvers.length + 2];
        all[0] = TagResolver.standard();
        all[1] = Placeholder.component("prefix", prefix(lang));
        System.arraycopy(resolvers, 0, all, 2, resolvers.length);
        try {
            return mini.deserialize(template, all);
        } catch (ParsingException e) {
            return Component.text(template);
        }
    }

    private Component prefix(String lang) {
        return prefixes.computeIfAbsent(lang, l -> {
            Object raw = lookup(l, "prefix");
            if (raw == null) return Component.empty();
            try {
                return mini.deserialize(String.valueOf(raw));
            } catch (ParsingException e) {
                return Component.empty();
            }
        });
    }

    private Component finish(Component c, Variant v) {
        if (!v.visualOrder()) return c;
        return BidiText.toVisual(c, RTL_LANGUAGES.contains(v.language()));
    }

    private String template(String lang, String key) {
        Object raw = lookup(lang, key);
        if (raw == null) {
            reportMissing(lang, key);
            return key;
        }
        if (raw instanceof List<?> l) return String.join("\n", l.stream().map(String::valueOf).toList());
        return String.valueOf(raw);
    }

    private Object lookup(String lang, String key) {
        Map<String, Map<String, Object>> langs = languages;
        Map<String, Object> m = langs.get(lang);
        Object v = m == null ? null : m.get(key);
        if (v == null && !lang.equals(defaultLanguage())) {
            Map<String, Object> d = langs.get(defaultLanguage());
            v = d == null ? null : d.get(key);
        }
        if (v == null) {
            Map<String, Object> en = langs.get("en");
            v = en == null ? null : en.get(key);
        }
        return v;
    }

    private void reportMissing(String lang, String key) {
        if (reportedMissing.add(lang + ":" + key)) {
            logger.warning("Missing message '" + key + "' (language " + lang + "); add it to lang/" + lang + ".yml");
        }
    }

    public Set<String> languages() {
        return languages.keySet();
    }

    public Settings.Language settings() {
        return settings;
    }
}
