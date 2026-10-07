package com.voidmachine.i18n;

import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.paper.ritual.RitualService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled languages are complete and consistent: same keys in both, every tag valid, every key the
 * code uses (literally or by composition) present.
 */
class LanguageFilesTest {

    private static final List<String> LANGUAGES = List.of("en", "he");
    private static final Set<String> ROOTS = Set.of("generic", "help", "offering", "ritual", "verdict", "outcomes", "rarity",
            "jackpot", "announce", "claims", "recovery", "stats", "top", "chamber", "machine", "ambient");
    private static final Pattern LITERAL = Pattern.compile("\"([a-z]+(?:\\.[a-z0-9-]+)+)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?:Placeholder\\.(?:unparsed|component|parsed)|num|amount)\\(\"([a-z_-]+)\"");
    private static final Pattern RAW_TAG = Pattern.compile("<[a-z_#/:!-][^<>]*>");

    static Map<String, Object> flat(String lang) throws IOException {
        try (InputStream in = LanguageFilesTest.class.getClassLoader().getResourceAsStream("lang/" + lang + ".yml")) {
            if (in == null) throw new IOException("missing lang/" + lang + ".yml");
            Object tree = new Yaml(new SafeConstructor(new LoaderOptions())).load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Object> out = new TreeMap<>();
            flatten("", tree, out);
            return out;
        }
    }

    private static void flatten(String prefix, Object node, Map<String, Object> out) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                flatten(prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else {
            out.put(prefix, node);
        }
    }

    private static List<String> sources() throws IOException {
        return sources(Path.of("src/main/java"));
    }

    private static List<String> sources(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) out.add(Files.readString(f));
        }
        return out;
    }

    /** Player-facing code: the Paper layer without configuration loading and migration (their literals are config keys). */
    private static List<String> playerFacingSources() throws IOException {
        List<String> out = new ArrayList<>();
        Path paper = Path.of("src/main/java/com/voidmachine/paper");
        try (Stream<Path> files = Files.walk(paper)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(paper.resolve("config")) && !p.startsWith(paper.resolve("migration"))).toList()) {
                out.add(Files.readString(f));
            }
        }
        return out;
    }

    @Test
    void languagesHaveTheSameKeysAndShapes() throws IOException {
        Map<String, Object> en = flat("en");
        Map<String, Object> he = flat("he");
        assertEquals(new TreeSet<>(en.keySet()), new TreeSet<>(he.keySet()), "en and he must define the same keys");
        for (String key : en.keySet()) {
            assertEquals(en.get(key) instanceof List, he.get(key) instanceof List, key + ": text vs list mismatch");
        }
    }

    @Test
    void everyTextRendersWithoutUnknownTags() throws IOException {
        Set<String> placeholders = new TreeSet<>();
        for (String src : sources()) {
            Matcher m = PLACEHOLDER.matcher(src);
            while (m.find()) placeholders.add(m.group(1));
        }
        placeholders.add("prefix");
        TagResolver.Builder rb = TagResolver.builder();
        for (String p : placeholders) rb.tag(p, Tag.selfClosingInserting(Component.text("X")));
        TagResolver resolver = rb.build();
        MiniMessage mm = MiniMessage.miniMessage();
        List<String> bad = new ArrayList<>();
        for (String lang : LANGUAGES) {
            for (Map.Entry<String, Object> e : flat(lang).entrySet()) {
                List<String> texts = new ArrayList<>();
                if (e.getValue() instanceof List<?> l) l.forEach(o -> texts.add(String.valueOf(o)));
                else texts.add(String.valueOf(e.getValue()));
                for (String t : texts) {
                    String plain = PlainTextComponentSerializer.plainText().serialize(mm.deserialize(t, resolver));
                    if (RAW_TAG.matcher(plain).find()) bad.add(lang + ":" + e.getKey() + " -> " + plain);
                }
            }
        }
        assertEquals(List.of(), bad, "unknown tags or placeholders (known placeholders: " + placeholders + ")");
    }

    @Test
    void everyKeyTheCodeUsesExists() throws IOException {
        Set<String> used = new TreeSet<>();
        for (String src : playerFacingSources()) {
            Matcher m = LITERAL.matcher(src);
            while (m.find()) {
                String key = m.group(1);
                if (key.matches(".*\\.(json|jsonl|yml)$")) continue; // file names
                if (ROOTS.contains(key.substring(0, key.indexOf('.')))) used.add(key);
            }
        }
        assertTrue(used.size() > 50, "the scan found the literal keys (" + used.size() + ")");
        // Keys the code composes at run time.
        for (RitualService.BeginResult r : RitualService.BeginResult.values()) {
            if (r != RitualService.BeginResult.ACCEPTED) used.add("offering.result." + kebab(r));
        }
        for (RitualService.BeginResult r : EnumSet.of(RitualService.BeginResult.UNAVAILABLE, RitualService.BeginResult.CONFIG_INVALID,
                RitualService.BeginResult.NO_PERMISSION, RitualService.BeginResult.PROFILE_PERMISSION, RitualService.BeginResult.CREATIVE,
                RitualService.BeginResult.WORLD_BLOCKED, RitualService.BeginResult.MACHINE_DISABLED, RitualService.BeginResult.MACHINE_BUSY,
                RitualService.BeginResult.MACHINE_RESTING, RitualService.BeginResult.PLAYER_COOLDOWN, RitualService.BeginResult.ALREADY_IN_RITUAL,
                RitualService.BeginResult.TOO_MANY_RITUALS, RitualService.BeginResult.TOO_MANY_UNCLAIMED, RitualService.BeginResult.PROFILE_MISSING)) {
            used.add("offering.button." + kebab(r)); // everything precheck can return
        }
        for (OutcomeTier tier : OutcomeTier.values()) {
            String t = tier.name().toLowerCase(Locale.ROOT);
            for (String part : List.of("bar", "title", "subtitle", "message")) used.add("verdict.tier." + t + "." + part);
            used.add("ritual.narrate.verdict." + t);
        }
        for (String outcome : List.of("consumed", "tithe", "returned", "doubled", "tripled", "jackpot")) used.add("outcomes." + outcome + ".name");
        for (String variant : List.of("void_ascension", "the_devourer", "black_star", "heart_of_the_void", "null_crown")) {
            for (String part : List.of("bar", "title", "lore")) used.add("jackpot." + variant + "." + part);
        }
        for (String rarity : List.of("common", "uncommon", "rare", "legendary")) used.add("rarity." + rarity);
        for (String refusal : List.of("empty", "blocked", "container", "unstackable", "marked")) used.add("offering.refused." + refusal);

        for (String lang : LANGUAGES) {
            Map<String, Object> keys = flat(lang);
            List<String> missing = used.stream().filter(k -> !keys.containsKey(k)).toList();
            assertEquals(List.of(), missing, lang + " is missing keys the code uses");
        }
    }

    @Test
    void hebrewIsActuallyHebrew() throws IOException {
        Map<String, Object> he = flat("he");
        long hebrew = he.values().stream().map(String::valueOf).filter(v -> v.codePoints().anyMatch(c -> c >= 0x0590 && c <= 0x05FF)).count();
        assertTrue(hebrew > he.size() * 0.8, "most Hebrew texts contain Hebrew letters (" + hebrew + "/" + he.size() + ")");
    }

    private static String kebab(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
