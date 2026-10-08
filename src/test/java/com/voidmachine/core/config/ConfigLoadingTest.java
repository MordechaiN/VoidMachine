package com.voidmachine.core.config;

import com.voidmachine.core.machine.MachineFile;
import com.voidmachine.core.machine.MachineRecord;
import com.voidmachine.core.migration.V1ConfigMigration;
import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.script.Ritualbook;
import com.voidmachine.core.script.RitualsLoader;
import com.voidmachine.core.script.Theme;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoadingTest {

    /** A typed YAML mapping, so tests can navigate and mutate trees without unchecked casts. */
    static final class YMap extends LinkedHashMap<String, Object> {
    }

    /** A typed YAML sequence. */
    static final class YList extends ArrayList<Object> {
    }

    static YMap yaml(String resource) throws IOException {
        try (InputStream in = ConfigLoadingTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing resource " + resource);
            Object tree = new Yaml(new SafeConstructor(new LoaderOptions())).load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!(typed(tree) instanceof YMap map)) throw new IOException(resource + " is not a mapping");
            return map;
        }
    }

    private static Object typed(Object node) {
        if (node instanceof Map<?, ?> m) {
            YMap out = new YMap();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), typed(e.getValue()));
            return out;
        }
        if (node instanceof List<?> l) {
            YList out = new YList();
            for (Object o : l) out.add(typed(o));
            return out;
        }
        return node;
    }

    static Ritualbook book() throws IOException {
        RitualsLoader.Result r = RitualsLoader.load(yaml("rituals.yml"), TestRegistry.INSTANCE);
        assertTrue(r.book().isPresent(), () -> "bundled rituals.yml invalid: " + r.problems());
        return r.book().get();
    }

    @Test
    void bundledRitualsAreValidAgainstThe262Registries() throws IOException {
        RitualsLoader.Result r = RitualsLoader.load(yaml("rituals.yml"), TestRegistry.INSTANCE);
        assertEquals(List.of(), r.problems(), "the bundled rituals.yml must load without errors or warnings");
        Theme voidTheme = r.book().orElseThrow().theme("void");
        assertEquals(5, voidTheme.jackpots().size());
        assertTrue(voidTheme.jackpot("null_crown").isPresent());
        for (OutcomeTier t : OutcomeTier.values()) {
            if (t != OutcomeTier.JACKPOT) assertFalse(voidTheme.reveal(t).isEmpty(), "reveal for " + t);
        }
    }

    @Test
    void bundledConfigIsValid() throws IOException {
        SettingsLoader.Result r = SettingsLoader.load(yaml("config.yml"), TestRegistry.INSTANCE, book());
        assertEquals(List.of(), r.problems(), "the bundled config.yml must load without errors or warnings");
        Settings s = r.settings().orElseThrow();
        assertEquals(3, s.profiles().size());
        assertEquals(0.394, s.profiles().get("default").table().expectedReturn(), 1e-9);
        assertEquals(Settings.Overflow.HOLD, s.delivery().overflow());
    }

    @Test
    void invalidConfigsFailLoudlyWithPreciseProblems() throws IOException {
        Ritualbook book = book();
        expectError(book, c -> weights(c).put("consumed", -5), "profiles.default.weights.consumed", "out of range");
        expectError(book, c -> {
            Map<String, Object> w = weights(c);
            w.replaceAll((k, v) -> 0);
        }, "profiles.default.weights", "every outcome weight is 0");
        expectError(book, c -> weights(c).put("mystery", 5), "profiles.default.weights.mystery", "not defined");
        expectError(book, c -> section(c, "profiles", "default").put("theme", "nope"), "profiles.default.theme", "not defined");
        expectError(book, c -> section(c, "profiles", "default").put("pacing", "nope"), "profiles.default.pacing", "not defined");
        expectError(book, c -> section(c, "outcomes", "jackpot").put("multiplier", 0), "outcomes.jackpot", "tier jackpot requires");
        expectError(book, c -> section(c, "outcomes", "doubled").put("multiplier", "lots"), "outcomes.doubled.multiplier", "not a number");
        expectError(book, c -> section(c, "machines").put("core-block", "DIAMOND_SWORD"), "machines.core-block", "not a placeable block");
        expectError(book, c -> section(c, "spectators").put("inner-radius", 50), "spectators", "radii must increase");
        expectError(book, c -> section(c, "limits").put("max-reward-amount", 10), "limits.max-reward-amount", "smaller than offering.max-amount");
        expectError(book, c -> section(c, "limits").put("max-ritual-duration-ticks", 200), "profiles.brutal.pacing", "longest possible ritual");
        expectError(book, c -> section(c, "offering").put("max-amount", 500), "offering.max-amount", "out of range");
        expectError(book, c -> section(c, "delivery").put("overflow", "explode"), "delivery.overflow", "not a valid choice");
        expectError(book, c -> section(c, "ambient", "void-events").put("max-interval-minutes", 1), "ambient.void-events.max-interval-minutes", "below");
        expectError(book, c -> section(c, "presentation", "fakeouts").put("chance", Double.NaN), "presentation.fakeouts.chance", "finite");
        expectError(book, c -> c.put("config-version", 1), "config-version", "unsupported");
        expectError(book, c -> c.put("profiles", Map.of()), "profiles", "at least one machine profile");
    }

    @Test
    void unknownKeysAndMissingSectionsAreWarnings() throws IOException {
        Map<String, Object> c = yaml("config.yml");
        section(c, "machines").put("max-machnes", 5);
        c.remove("stats");
        SettingsLoader.Result r = SettingsLoader.load(c, TestRegistry.INSTANCE, book());
        assertTrue(r.ok(), () -> r.problems().toString());
        assertTrue(r.problems().stream().anyMatch(p -> p.path().equals("machines.max-machnes") && p.message().contains("unknown")));
        assertTrue(r.problems().stream().anyMatch(p -> p.path().equals("stats") && p.message().contains("missing")));
    }

    @Test
    void invalidRitualsAreRejected() throws IOException {
        Map<String, Object> rituals = yaml("rituals.yml");
        Map<String, Object> theme = section(rituals, "themes", "void");
        Map<String, Object> awaken = section(theme, "phases", "awaken");
        if (!(awaken.get("cues") instanceof YList cues)) throw new AssertionError("awaken.cues is not a list");
        cues.add(new LinkedHashMap<>(Map.of("at", 0, "sound", "block.does.not_exist")));
        cues.add(new LinkedHashMap<>(Map.of("at", 0, "particle", "block")));
        cues.add(new LinkedHashMap<>(Map.of("at", 0, "particle", "dust", "color", "purple")));
        cues.add(new LinkedHashMap<>(Map.of("at", 0, "sound", "block.anvil.use", "particle", "portal")));
        cues.add(new LinkedHashMap<>(Map.of("at", 0, "sound", "block.anvil.use", "pitch", "0.1..3")));
        section(rituals, "pacing", "standard").put("stir", List.of(40, 10));
        RitualsLoader.Result r = RitualsLoader.load(rituals, TestRegistry.INSTANCE);
        assertTrue(r.book().isEmpty());
        String all = r.problems().toString();
        assertTrue(all.contains("unknown vanilla sound"), all);
        assertTrue(all.contains("needs data VoidMachine cannot supply"), all);
        assertTrue(all.contains("is not a colour"), all);
        assertTrue(all.contains("exactly one of"), all);
        assertTrue(all.contains("pitch") && all.contains("out of range"), all);
        assertTrue(all.contains("maximum is below minimum"), all);
    }

    @Test
    void v1ConfigMigratesWithIdenticalOdds() throws IOException {
        Map<String, Object> v1 = yaml("v1/config.yml");
        assertTrue(V1ConfigMigration.isV1(v1));
        V1ConfigMigration.Result m = V1ConfigMigration.migrate(v1);
        Map<String, Object> v2 = yaml("config.yml");
        apply(v2, m.assignments());
        SettingsLoader.Result r = SettingsLoader.load(v2, TestRegistry.INSTANCE, book());
        assertTrue(r.ok(), () -> "migrated config invalid: " + r.problems());
        Settings s = r.settings().orElseThrow();
        // V1 'default' profile: destroyed 74, returned 20, doubled 4, tripled 1.8, jackpot 0.2.
        Map<String, Double> p = s.profiles().get("default").table().probabilities();
        assertEquals(0.74, p.get("consumed"), 1e-9);
        assertEquals(0.0, p.get("tithe"), 1e-9);
        assertEquals(0.20, p.get("returned"), 1e-9);
        assertEquals(0.002, p.get("jackpot"), 1e-9);
        assertEquals(0.9, s.profiles().get("brutal").table().probabilities().get("consumed"), 1e-9);
        assertEquals(8, s.cooldowns().playerSeconds());
        assertEquals(99, s.offering().maxAmount(), "V1 allowed 256 per insert, i.e. any single stack; one slot holds at most 99");
        assertEquals(1024, s.limits().maxRewardAmount());
        assertEquals(0.01, s.presentation().fakeouts().chance(), 1e-9);
        assertEquals(2, s.spectators().crowd().minForBonus());
        assertTrue(m.notes().stream().anyMatch(n -> n.contains("now ENFORCED")));
        assertTrue(m.notes().stream().anyMatch(n -> n.contains("max-insert-amount 256")));
    }

    @Test
    void v1MachinesMigrateWithoutLosingAnything() throws IOException {
        MachineFile.Result r = MachineFile.read(yaml("v1/machines.yml"));
        assertTrue(r.wasV1());
        assertEquals(2, r.machines().size());
        MachineRecord dark = r.machines().stream().filter(m -> m.displayName().equals("Dark Altar")).findFirst().orElseThrow();
        assertEquals("dark_altar", dark.id());
        assertEquals("world_nether", dark.world());
        assertEquals(1, r.unreadable().size(), "the broken entry is reported");
        Map<String, Object> written = MachineFile.write(r.machines(), r.unreadable());
        assertTrue(written.get("machines") instanceof Map<?, ?> machines && machines.containsKey("broken"),
                "unreadable entries are written back, never dropped");
        MachineFile.Result again = MachineFile.read(written);
        assertFalse(again.wasV1());
        assertEquals(r.machines(), again.machines());
    }

    // ------------------------------------------------------------------------------------------

    private static void expectError(Ritualbook book, Consumer<Map<String, Object>> mutate, String path, String fragment) throws IOException {
        Map<String, Object> c = yaml("config.yml");
        mutate.accept(c);
        SettingsLoader.Result r = SettingsLoader.load(c, TestRegistry.INSTANCE, book);
        assertFalse(r.ok(), "expected an error at " + path);
        assertTrue(r.problems().stream().anyMatch(p -> p.severity() == ConfigProblem.Severity.ERROR
                        && p.path().equals(path) && p.render().contains(fragment)),
                () -> "expected ERROR at " + path + " containing '" + fragment + "' but got:\n" + String.join("\n",
                        r.problems().stream().map(ConfigProblem::render).toList()));
    }

    static Map<String, Object> section(Map<String, Object> root, String... path) {
        Map<String, Object> cur = root;
        for (String p : path) {
            if (!(cur.get(p) instanceof YMap next)) throw new AssertionError("no mapping at '" + p + "'");
            cur = next;
        }
        return cur;
    }

    static Map<String, Object> weights(Map<String, Object> c) {
        return section(c, "profiles", "default", "weights");
    }

    static void apply(Map<String, Object> tree, Map<String, Object> assignments) {
        for (Map.Entry<String, Object> e : assignments.entrySet()) {
            String[] parts = e.getKey().split("\\.");
            Map<String, Object> cur = tree;
            for (int i = 0; i < parts.length - 1; i++) {
                if (!(cur.computeIfAbsent(parts[i], k -> new YMap()) instanceof YMap next)) {
                    throw new AssertionError("'" + parts[i] + "' is not a mapping");
                }
                cur = next;
            }
            if (e.getValue() == null) cur.remove(parts[parts.length - 1]);
            else cur.put(parts[parts.length - 1], typed(e.getValue()));
        }
    }
}
