package com.voidmachine.core.config;

import com.voidmachine.core.outcome.Multiplier;
import com.voidmachine.core.outcome.OutcomeDefinition;
import com.voidmachine.core.outcome.OutcomeTable;
import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.spectator.CrowdModel;
import com.voidmachine.core.spectator.SpectatorTier;
import com.voidmachine.core.timeline.FakeoutPlan;
import com.voidmachine.core.timeline.Pacing;
import com.voidmachine.core.util.Ids;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and validates {@code config.yml}. Never throws for bad input: every problem is collected with
 * its path, the expected value, the value found and a fix, and {@link Result#settings()} is empty if
 * any error was found (VoidMachine then refuses new rituals until an admin fixes the file).
 */
public final class SettingsLoader {

    public static final String FILE = "config.yml";

    /** What the loader needs to know about {@code rituals.yml}. */
    public interface Catalog {
        Optional<Pacing> pacing(String id);

        boolean hasTheme(String id);

        /** Longest jackpot reveal hold of a theme, in ticks (0 if none). */
        int longestJackpotHold(String themeId);
    }

    public record Result(Optional<Settings> settings, List<ConfigProblem> problems) {
        public boolean ok() {
            return settings.isPresent();
        }
    }

    private SettingsLoader() {
    }

    public static Result load(Map<String, Object> tree, GameRegistry registry, Catalog catalog) {
        Problems p = new Problems(FILE);
        Node root = Node.root(tree, p);

        int version = root.integer("config-version", 0, 0, 99);
        if (version != Settings.CONFIG_VERSION) {
            p.error("config-version", "unsupported configuration version", String.valueOf(Settings.CONFIG_VERSION), version,
                    "VoidMachine migrates V1 files automatically; if this file was edited by hand, compare it with the default config.yml");
        }

        Settings.Language language = language(root.section("language"));
        Settings.Machines machines = machines(root.section("machines"), registry);
        Settings.Offering offering = offering(root.section("offering"), registry);
        Settings.Limits limits = limits(root.section("limits"));
        Settings.Cooldowns cooldowns = cooldowns(root.section("cooldowns"));
        Map<String, OutcomeDefinition> outcomes = outcomes(root.section("outcomes"));
        Map<String, Settings.Profile> profiles = profiles(root.section("profiles"), outcomes, catalog);
        Settings.Presentation presentation = presentation(root.section("presentation"));
        Settings.Spectators spectators = spectators(root.section("spectators"));
        Settings.Ambient ambient = ambient(root.section("ambient"));
        Settings.Delivery delivery = delivery(root.section("delivery"));
        Settings.Recovery recovery = recovery(root.section("recovery"));
        Settings.Logging logging = logging(root.section("logging"));
        Settings.Stats stats = stats(root.section("stats"));

        // Cross-field rules.
        if (limits.maxRewardAmount() < offering.maxAmount()) {
            p.error("limits.max-reward-amount", "is smaller than offering.max-amount, so a 'returned' verdict could not return everything",
                    ">= " + offering.maxAmount(), limits.maxRewardAmount(), "raise limits.max-reward-amount or lower offering.max-amount");
        }
        for (Settings.Profile profile : profiles.values()) {
            catalog.pacing(profile.pacing()).ifPresent(pacing -> {
                for (String problem : pacing.problems(limits.maxRitualDurationTicks(), catalog.longestJackpotHold(profile.theme()))) {
                    p.error("profiles." + profile.id() + ".pacing", "pacing '" + pacing.id() + "' (rituals.yml): " + problem);
                }
            });
        }

        root.reportUnknownKeys();
        if (p.hasErrors()) return new Result(Optional.empty(), p.all());
        return new Result(Optional.of(new Settings(language, machines, offering, limits, cooldowns, outcomes, profiles,
                presentation, spectators, ambient, delivery, recovery, logging, stats)), p.all());
    }

    // ------------------------------------------------------------------------------------------

    private static Settings.Language language(Node n) {
        String def = n.choice("default", "he", List.of("he", "en"));
        return new Settings.Language(def, n.bool("use-client-language", true), n.bool("bedrock-rtl-fix", true));
    }

    private static Settings.Machines machines(Node n, GameRegistry registry) {
        String core = n.string("core-block", "RESPAWN_ANCHOR").trim().toUpperCase(Locale.ROOT);
        if (!registry.isBlock(core)) {
            n.problems().error(n.pathOf("core-block"), "is not a placeable block", "a block material such as RESPAWN_ANCHOR",
                    core, "use a block that has no inventory, e.g. RESPAWN_ANCHOR, LODESTONE, CRYING_OBSIDIAN");
            core = "RESPAWN_ANCHOR";
        }
        return new Settings.Machines(core,
                n.integer("max-machines", 10, 1, 500),
                new LinkedHashSet<>(n.strings("allowed-worlds", List.of())),
                new LinkedHashSet<>(n.strings("blocked-worlds", List.of())),
                n.bool("allow-creative", false),
                n.integer("interact-cooldown-ms", 400, 0, 10_000));
    }

    private static Settings.Offering offering(Node n, GameRegistry registry) {
        Set<String> blocked = new LinkedHashSet<>();
        List<String> raw = n.strings("blocked-materials", List.of());
        for (int i = 0; i < raw.size(); i++) {
            String m = raw.get(i).trim().toUpperCase(Locale.ROOT);
            if (!registry.isItem(m)) {
                n.problems().warning(n.pathOf("blocked-materials") + "[" + i + "]", "unknown item material (ignored)",
                        "an item material such as SHULKER_BOX", raw.get(i), "check the spelling; removed items can be deleted from the list");
                continue;
            }
            blocked.add(m);
        }
        Set<String> pdc = new LinkedHashSet<>();
        for (String k : n.strings("blocked-pdc-keys", List.of("voidmachine:locked"))) {
            if (!k.matches("[a-z0-9._-]+:[a-z0-9/._-]+")) {
                n.problems().error(n.pathOf("blocked-pdc-keys"), "is not a namespaced key", "namespace:key", k, null);
            } else {
                pdc.add(k);
            }
        }
        return new Settings.Offering(
                n.integer("max-amount", 64, 1, 99),
                blocked,
                n.bool("block-filled-containers", true),
                n.bool("block-unstackable", false),
                pdc);
    }

    private static Settings.Limits limits(Node n) {
        return new Settings.Limits(
                n.integer("max-reward-amount", 320, 1, 6_400),
                n.integer("max-active-rituals", 4, 1, 50),
                n.integer("max-unclaimed-per-player", 5, 1, 100),
                n.integer("max-ritual-duration-ticks", 900, 200, 6_000),
                n.integer("max-spectators-per-ritual", 32, 0, 200),
                n.integer("max-particles-per-tick", 1_200, 0, 20_000),
                n.integer("max-packets-per-tick", 400, 0, 10_000),
                n.integer("max-display-entities-per-ritual", 3, 0, 8));
    }

    private static Settings.Cooldowns cooldowns(Node n) {
        return new Settings.Cooldowns(n.integer("player-seconds", 8, 0, 86_400), n.integer("machine-seconds", 0, 0, 86_400));
    }

    /** Built-in outcomes used when the section is missing. */
    public static Map<String, OutcomeDefinition> defaultOutcomes() {
        Map<String, OutcomeDefinition> m = new LinkedHashMap<>();
        m.put("consumed", new OutcomeDefinition("consumed", Multiplier.ZERO, OutcomeTier.LOSS));
        m.put("tithe", new OutcomeDefinition("tithe", new Multiplier(1, 2), OutcomeTier.PARTIAL));
        m.put("returned", new OutcomeDefinition("returned", Multiplier.ONE, OutcomeTier.NEUTRAL));
        m.put("doubled", new OutcomeDefinition("doubled", new Multiplier(2, 1), OutcomeTier.WIN));
        m.put("tripled", new OutcomeDefinition("tripled", new Multiplier(3, 1), OutcomeTier.GREAT));
        m.put("jackpot", new OutcomeDefinition("jackpot", new Multiplier(5, 1), OutcomeTier.JACKPOT));
        return m;
    }

    private static Map<String, OutcomeDefinition> outcomes(Node n) {
        if (!n.present()) return defaultOutcomes();
        Map<String, OutcomeDefinition> out = new LinkedHashMap<>();
        for (String id : n.keys()) {
            Node o = n.section(id, false);
            if (!Ids.isValid(id)) {
                n.problems().error(n.pathOf(id), "invalid outcome id", Ids.RULE, id, null);
                continue;
            }
            Optional<Multiplier> mult = o.parsed("multiplier", Multiplier::parse, "a multiplier such as 0, 0.5, 1, 2 or 1/2");
            if (!o.has("multiplier")) {
                n.problems().error(o.pathOf("multiplier"), "is required", "a multiplier such as 0, 0.5, 1, 2", null, null);
            }
            String tierRaw = o.string("tier", "");
            Optional<OutcomeTier> tier = OutcomeTier.parse(tierRaw);
            if (tier.isEmpty()) {
                n.problems().error(o.pathOf("tier"), "is not a valid tier", "one of loss, partial, neutral, win, great, jackpot", tierRaw, null);
            }
            if (mult.isPresent() && tier.isPresent()) {
                Optional<String> bad = tier.get().incompatibilityWith(mult.get());
                if (bad.isPresent()) {
                    n.problems().error(n.pathOf(id), bad.get(), null, null, "change the tier or the multiplier so they agree");
                } else {
                    out.put(id, new OutcomeDefinition(id, mult.get(), tier.get()));
                }
            }
        }
        if (out.isEmpty()) n.problems().error(n.path(), "no valid outcomes are defined", "at least one outcome", null, null);
        return out;
    }

    private static Map<String, Settings.Profile> profiles(Node n, Map<String, OutcomeDefinition> outcomes, Catalog catalog) {
        Map<String, Settings.Profile> out = new LinkedHashMap<>();
        if (!n.present() || n.keys().isEmpty()) {
            n.problems().error(n.path(), "at least one machine profile is required", "a 'default' profile", null,
                    "copy the profiles section from the default config.yml");
            return out;
        }
        for (String id : n.keys()) {
            Node pn = n.section(id, false);
            if (!Ids.isValid(id)) {
                n.problems().error(n.pathOf(id), "invalid profile id", Ids.RULE, id, null);
                continue;
            }
            Node weights = pn.section("weights", false);
            if (!weights.present()) {
                n.problems().error(pn.pathOf("weights"), "is required", "a section of outcome: weight", null, null);
                continue;
            }
            Map<OutcomeDefinition, Double> table = new LinkedHashMap<>();
            boolean bad = false;
            for (String outcomeId : weights.keys()) {
                double w = weights.decimal(outcomeId, 0, 0, OutcomeTable.MAX_TOTAL_WEIGHT);
                OutcomeDefinition def = outcomes.get(outcomeId);
                if (def == null) {
                    n.problems().error(weights.pathOf(outcomeId), "refers to an outcome that is not defined", "one of " + outcomes.keySet(),
                            outcomeId, "define it under 'outcomes' or remove it here");
                    bad = true;
                    continue;
                }
                table.put(def, w);
            }
            String theme = pn.string("theme", "void");
            if (!catalog.hasTheme(theme)) {
                n.problems().error(pn.pathOf("theme"), "is not defined in rituals.yml", "a theme id from rituals.yml", theme, null);
                bad = true;
            }
            String pacing = pn.string("pacing", "standard");
            if (catalog.pacing(pacing).isEmpty()) {
                n.problems().error(pn.pathOf("pacing"), "is not defined in rituals.yml", "a pacing id from rituals.yml", pacing, null);
                bad = true;
            }
            String permission = pn.string("permission", "").trim();
            if (!permission.isEmpty() && !permission.matches("[a-zA-Z0-9._-]+")) {
                n.problems().error(pn.pathOf("permission"), "is not a valid permission node", "e.g. voidmachine.profile.vip", permission, null);
                bad = true;
            }
            if (bad || table.isEmpty()) {
                if (table.isEmpty()) n.problems().error(pn.pathOf("weights"), "has no outcomes", "at least one outcome with a weight > 0", null, null);
                continue;
            }
            try {
                out.put(id, new Settings.Profile(id, OutcomeTable.of(id, table), theme, pacing, permission));
            } catch (IllegalArgumentException e) {
                n.problems().error(pn.pathOf("weights"), e.getMessage(), "non-negative finite weights, at least one above 0", null, null);
            }
        }
        if (!out.isEmpty() && !out.containsKey("default")) {
            n.problems().warning(n.path(), "there is no 'default' profile; '/vm admin create' needs an explicit profile name");
        }
        return out;
    }

    private static Settings.Presentation presentation(Node n) {
        Node f = n.section("fakeouts");
        Set<FakeoutPlan.Pattern> patterns = EnumSet.noneOf(FakeoutPlan.Pattern.class);
        for (String s : f.strings("patterns", List.of("false-loss", "escalation"))) {
            String norm = s.trim().toLowerCase(Locale.ROOT);
            Optional<FakeoutPlan.Pattern> match = EnumSet.allOf(FakeoutPlan.Pattern.class).stream().filter(x -> x.id().equals(norm)).findFirst();
            if (match.isEmpty()) {
                f.problems().error(f.pathOf("patterns"), "unknown fakeout pattern", "false-loss or escalation", s, null);
            } else {
                patterns.add(match.get());
            }
        }
        Settings.Fakeouts fakeouts = new Settings.Fakeouts(
                f.bool("enabled", true),
                f.decimal("chance", 0.08, 0, 1),
                f.integer("min-rituals-between-per-player", 4, 0, 1000),
                f.integer("global-cooldown-seconds", 90, 0, 86_400),
                patterns);
        Node a = n.section("announce");
        Map<String, Settings.AnnounceScope> announce = new HashMap<>();
        for (String tier : List.of("win", "great", "jackpot")) {
            String def = switch (tier) {
                case "jackpot" -> "server";
                case "great" -> "area";
                default -> "none";
            };
            announce.put(tier, Settings.AnnounceScope.valueOf(a.choice(tier, def, List.of("none", "area", "server")).toUpperCase(Locale.ROOT)));
        }
        return new Settings.Presentation(
                n.bool("ritual-chamber-gui", false),
                n.bool("tether-player", true),
                n.decimal("tether-radius", 1.5, 0.5, 8),
                n.bool("spectator-bossbar", true),
                n.bool("narrate-to-spectators", true),
                fakeouts,
                Map.copyOf(announce),
                a.integer("server-cooldown-seconds", 30, 0, 3_600));
    }

    private static Settings.Spectators spectators(Node n) {
        double inner = n.decimal("inner-radius", 6, 1, 64);
        double near = n.decimal("near-radius", 14, 1, 96);
        double far = n.decimal("far-radius", 28, 1, 128);
        SpectatorTier.Radii radii;
        try {
            radii = new SpectatorTier.Radii(inner, near, far);
        } catch (IllegalArgumentException e) {
            n.problems().error(n.path(), "radii must increase", "inner-radius < near-radius < far-radius",
                    inner + " / " + near + " / " + far, null);
            radii = new SpectatorTier.Radii(6, 14, 28);
        }
        Node c = n.section("crowd");
        int min = c.integer("min-for-bonus", 2, 1, 50);
        double max = c.decimal("max-intensity", 1.6, 1.0, 3.0);
        int full = c.integer("full-at", 6, 1, 100);
        if (full < min) {
            c.problems().error(c.pathOf("full-at"), "is below min-for-bonus", ">= " + min, full, null);
            full = min;
        }
        return new Settings.Spectators(radii, n.integer("refresh-ticks", 10, 2, 100), new CrowdModel(min, max, full));
    }

    private static Settings.Ambient ambient(Node n) {
        Node v = n.section("void-events");
        int min = v.integer("min-interval-minutes", 10, 1, 1440);
        int max = v.integer("max-interval-minutes", 45, 1, 1440);
        if (max < min) {
            v.problems().error(v.pathOf("max-interval-minutes"), "is below min-interval-minutes", ">= " + min, max, null);
            max = min;
        }
        return new Settings.Ambient(n.bool("enabled", true), n.decimal("radius", 24, 4, 96), v.bool("enabled", true), min, max);
    }

    private static Settings.Delivery delivery(Node n) {
        String overflow = n.choice("overflow", "hold", List.of("hold", "drop"));
        return new Settings.Delivery(Settings.Overflow.valueOf(overflow.toUpperCase(Locale.ROOT)), n.integer("retry-seconds", 5, 1, 600));
    }

    private static Settings.Recovery recovery(Node n) {
        return new Settings.Recovery(n.integer("unverified-record-retention-days", 0, 0, 3_650));
    }

    private static Settings.Logging logging(Node n) {
        String level = n.choice("level", "info", List.of("quiet", "info", "debug"));
        Node a = n.section("audit");
        return new Settings.Logging(Settings.LogLevel.valueOf(level.toUpperCase(Locale.ROOT)), a.bool("enabled", true),
                a.integer("retention-days", 30, 0, 3_650));
    }

    private static Settings.Stats stats(Node n) {
        return new Settings.Stats(n.bool("enabled", true), n.integer("save-interval-seconds", 60, 10, 3_600));
    }
}
