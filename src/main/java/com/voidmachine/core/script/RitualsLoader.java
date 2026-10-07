package com.voidmachine.core.script;

import com.voidmachine.core.config.ConfigProblem;
import com.voidmachine.core.config.GameRegistry;
import com.voidmachine.core.config.Node;
import com.voidmachine.core.config.Problems;
import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.spectator.SpectatorTier;
import com.voidmachine.core.timeline.Pacing;
import com.voidmachine.core.timeline.Phase;
import com.voidmachine.core.util.Ids;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and validates {@code rituals.yml}. Unknown sounds in the {@code minecraft} namespace and
 * unknown particles are errors; sounds in other namespaces are allowed (resource packs) with a warning.
 */
public final class RitualsLoader {

    public static final String FILE = "rituals.yml";
    public static final int FORMAT = 1;
    static final List<String> BOSSBAR_COLORS = List.of("pink", "blue", "red", "green", "yellow", "purple", "white");

    private static final Map<String, Phase> PHASE_KEYS = Map.of(
            "awaken", Phase.AWAKEN, "capture", Phase.CAPTURE, "stir", Phase.STIR, "ramp", Phase.RAMP,
            "instability", Phase.INSTABILITY, "silence", Phase.SILENCE, "destabilize", Phase.DESTABILIZE,
            "aftermath", Phase.AFTERMATH);

    public record Result(Optional<Ritualbook> book, List<ConfigProblem> problems) {
    }

    private RitualsLoader() {
    }

    public static Result load(Map<String, Object> tree, GameRegistry registry) {
        Problems p = new Problems(FILE);
        Node root = Node.root(tree, p);
        int format = root.integer("format", 0, 0, 99);
        if (format != FORMAT) {
            p.error("format", "unsupported rituals.yml format", String.valueOf(FORMAT), format, "replace the file with the default one");
        }
        Map<String, Pacing> pacings = pacings(root.section("pacing"));
        Map<String, Theme> themes = new LinkedHashMap<>();
        Node tn = root.section("themes");
        for (String id : tn.keys()) {
            if (!Ids.isValid(id)) {
                p.error(tn.pathOf(id), "invalid theme id", Ids.RULE, id, null);
                tn.consume(id);
                continue;
            }
            themes.put(id, theme(id, tn.section(id, false), registry));
        }
        if (themes.isEmpty()) p.error("themes", "no themes are defined", "at least one theme (the default is 'void')", null, null);
        if (pacings.isEmpty()) p.error("pacing", "no pacing presets are defined", "at least one preset (the default is 'standard')", null, null);
        root.reportUnknownKeys();
        if (p.hasErrors()) return new Result(Optional.empty(), p.all());
        return new Result(Optional.of(new Ritualbook(pacings, themes)), p.all());
    }

    private static Map<String, Pacing> pacings(Node n) {
        Map<String, Pacing> out = new LinkedHashMap<>();
        for (String id : n.keys()) {
            Node s = n.section(id, false);
            if (!Ids.isValid(id)) {
                n.problems().error(n.pathOf(id), "invalid pacing id", Ids.RULE, id, null);
                continue;
            }
            int[] stir = range(s, "stir", 30, 46);
            int[] inst = range(s, "instability", 28, 60);
            int[] sil = range(s, "silence", 20, 34);
            out.put(id, new Pacing(id,
                    s.integer("awaken", 16, 0, 400), s.integer("capture", 24, 0, 400),
                    stir[0], stir[1], s.integer("ramp", 56, 0, 1200), inst[0], inst[1], sil[0], sil[1],
                    s.integer("false-reveal", 40, 0, 400), s.integer("destabilize", 26, 0, 400),
                    s.integer("reveal", 60, 0, 1200), s.integer("aftermath", 50, 0, 1200)));
        }
        return out;
    }

    /** Reads {@code [min, max]} or a single number. */
    private static int[] range(Node n, String key, int defMin, int defMax) {
        Object v = n.raw(key);
        if (v == null) return new int[]{defMin, defMax};
        if (v instanceof Number num) return new int[]{num.intValue(), num.intValue()};
        if (v instanceof List<?> l && l.size() == 2 && l.get(0) instanceof Number a && l.get(1) instanceof Number b) {
            if (b.intValue() < a.intValue()) {
                n.problems().error(n.pathOf(key), "maximum is below minimum", "[min, max] with min <= max", v, null);
                return new int[]{a.intValue(), a.intValue()};
            }
            if (a.intValue() < 0) {
                n.problems().error(n.pathOf(key), "cannot be negative", "[min, max] in ticks", v, null);
                return new int[]{defMin, defMax};
            }
            return new int[]{a.intValue(), b.intValue()};
        }
        n.problems().error(n.pathOf(key), "must be ticks or a [min, max] range", "e.g. 40 or [30, 50]", v, null);
        return new int[]{defMin, defMax};
    }

    private static Theme theme(String id, Node n, GameRegistry registry) {
        String bar = n.choice("bossbar-color", "purple", BOSSBAR_COLORS);
        Map<Phase, Script> phases = new EnumMap<>(Phase.class);
        Node pn = n.section("phases");
        for (String key : pn.keys()) {
            Phase phase = PHASE_KEYS.get(key);
            if (phase == null) {
                n.problems().error(pn.pathOf(key), "unknown phase", "one of " + PHASE_KEYS.keySet(), key,
                        "reveal choreography goes under 'reveals', jackpots under 'jackpots'");
                pn.consume(key);
                continue;
            }
            phases.put(phase, script(pn.section(key, false), registry));
        }
        Map<OutcomeTier, Script> reveals = new EnumMap<>(OutcomeTier.class);
        Node rn = n.section("reveals");
        for (String key : rn.keys()) {
            Optional<OutcomeTier> tier = OutcomeTier.parse(key);
            if (tier.isEmpty() || tier.get() == OutcomeTier.JACKPOT) {
                n.problems().error(rn.pathOf(key), "unknown reveal tier", "loss, partial, neutral, win or great (jackpots have their own section)", key, null);
                rn.consume(key);
                continue;
            }
            reveals.put(tier.get(), script(rn.section(key, false), registry));
        }
        for (OutcomeTier t : OutcomeTier.values()) {
            if (t != OutcomeTier.JACKPOT && !reveals.containsKey(t)) {
                n.problems().warning(rn.pathOf(t.name().toLowerCase(Locale.ROOT)), "no reveal choreography; this tier reveals with text and sound only");
            }
        }
        List<Theme.Jackpot> jackpots = new ArrayList<>();
        Node jn = n.section("jackpots");
        for (String jid : jn.keys()) {
            Node j = jn.section(jid, false).quiet();
            if (!Ids.isValid(jid)) {
                n.problems().error(jn.pathOf(jid), "invalid jackpot id", Ids.RULE, jid, null);
                continue;
            }
            int hold = j.integer("hold", 120, 40, 1200);
            Script s = script(j, registry);
            for (Cue c : s.cues()) {
                if (c.at() >= hold) {
                    n.problems().warning(j.pathOf("cues"), "a cue at tick " + c.at() + " is past the hold (" + hold + ") and never plays");
                }
            }
            jackpots.add(new Theme.Jackpot(jid, j.decimal("weight", 1, 0, 1000), hold,
                    j.choice("bossbar-color", bar, BOSSBAR_COLORS), s));
        }
        if (jackpots.isEmpty()) {
            n.problems().error(jn.path(), "a theme needs at least one jackpot variant", "a section under jackpots", null, null);
        } else if (jackpots.stream().allMatch(j -> j.weight() <= 0)) {
            n.problems().error(jn.path(), "every jackpot variant has weight 0", "at least one weight above 0", null, null);
        }
        Node an = n.section("ambient", false);
        Script idle = an.present() ? script(an.section("idle", false), registry) : Script.EMPTY;
        Script voidEvent = an.present() ? script(an.section("void-event", false), registry) : Script.EMPTY;
        return new Theme(id, bar, phases, reveals, List.copyOf(jackpots), idle, voidEvent);
    }

    private static Script script(Node n, GameRegistry registry) {
        n.quiet();
        int every = n.integer("every", 0, 0, 1200);
        Object raw = n.raw("cues");
        List<Cue> cues = new ArrayList<>();
        if (raw == null) return new Script(every, cues);
        if (!(raw instanceof List<?> list)) {
            n.problems().error(n.pathOf("cues"), "must be a list of cues", "- { at: 0, sound: ... }", raw, null);
            return new Script(every, cues);
        }
        for (int i = 0; i < list.size(); i++) {
            String path = n.pathOf("cues") + "[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> m)) {
                n.problems().error(path, "must be a cue map", "{ at: 0, sound: ... }", list.get(i), null);
                continue;
            }
            Node cn = Node.at(path, Node.stringKeys(m), n.problems()).quiet();
            Optional<Cue> cue = cue(cn, path, registry);
            cue.ifPresent(c -> {
                if (every > 0 && c.at() >= every) {
                    n.problems().warning(path, "offset " + c.at() + " is not below 'every' (" + every + "); the cue never plays");
                }
                cues.add(c);
            });
            reportUnknown(cn, path, n.problems());
        }
        return new Script(every, cues);
    }

    private static Optional<Cue> cue(Node c, String path, GameRegistry registry) {
        Problems p = c.problems();
        int at = c.integer("at", 0, 0, 1200);
        double chance = c.decimal("chance", 1, 0, 1);
        int kinds = (c.has("sound") ? 1 : 0) + (c.has("particle") ? 1 : 0) + (c.has("lightning") ? 1 : 0)
                + (c.has("display") ? 1 : 0) + (c.has("charge") ? 1 : 0);
        if (kinds != 1) {
            p.error(path, "a cue must have exactly one of sound, particle, lightning, display, charge", null, null, null);
            return Optional.empty();
        }
        if (c.has("sound")) {
            String key = namespaced(c.string("sound", ""));
            if (key.startsWith("minecraft:")) {
                if (!registry.soundExists(key)) {
                    p.error(path + ".sound", "unknown vanilla sound", "a sound event such as minecraft:block.beacon.activate", key,
                            "see https://minecraft.wiki/w/Sounds.json for valid names");
                    return Optional.empty();
                }
            } else {
                p.warning(path + ".sound", "custom sound: only players with your resource pack will hear it", null, key, null);
            }
            Range volume = range(c, "volume", Range.of(1), 0, 16);
            Range pitch = range(c, "pitch", Range.of(1), 0.5, 2.0);
            return Optional.of(new Cue.Sound(at, chance, key, volume, pitch, c.decimal("jitter", 0, 0, 0.5),
                    audience(c), channel(c)));
        }
        if (c.has("particle")) {
            String key = namespaced(c.string("particle", ""));
            Optional<GameRegistry.ParticleData> data = registry.particle(key);
            if (data.isEmpty()) {
                p.error(path + ".particle", "unknown particle", "a particle such as minecraft:portal", key, null);
                return Optional.empty();
            }
            if (data.get() == GameRegistry.ParticleData.UNSUPPORTED) {
                p.error(path + ".particle", "this particle needs data VoidMachine cannot supply", "a particle without block data", key, null);
                return Optional.empty();
            }
            double[] spread = spread(c);
            int color = color(c, "color", 0x7C2AD6);
            int toColor = color(c, "to-color", 0x2B0A4F);
            if ((data.get() == GameRegistry.ParticleData.DUST || data.get() == GameRegistry.ParticleData.DUST_TRANSITION
                    || data.get() == GameRegistry.ParticleData.COLOR) && !c.has("color")) {
                p.warning(path + ".color", "particle " + key + " is coloured; using the default purple");
            }
            Range count = range(c, "count", Range.of(8), 0, 500);
            return Optional.of(new Cue.Particle(at, chance, key, data.get(), count, spread[0], spread[1], spread[2],
                    c.decimal("speed", 0.02, 0, 4), enumOf(c, "anchor", Cue.Anchor.class, Cue.Anchor.ABOVE),
                    enumOf(c, "shape", Cue.Shape.class, Cue.Shape.POINT), c.decimal("radius", 1, 0, 8),
                    c.decimal("height", 1.5, 0, 16), c.integer("points", 12, 1, 64), color, toColor,
                    (float) c.decimal("size", 1.2, 0.1, 4), (float) c.decimal("value", 0, 0, 100), audience(c)));
        }
        if (c.has("lightning")) {
            c.bool("lightning", true);
            return Optional.of(new Cue.Lightning(at, chance));
        }
        if (c.has("display")) {
            return Optional.of(new Cue.Display(at, chance, enumOf(c, "display", Cue.DisplayAction.class, Cue.DisplayAction.SPIN)));
        }
        int level = c.integer("charge", 0, 0, 4);
        return Optional.of(new Cue.Charge(at, chance, level, audienceOr(c, SpectatorTier.Audience.ALL)));
    }

    private static String namespaced(String key) {
        String k = key.trim().toLowerCase(Locale.ROOT);
        return k.contains(":") ? k : "minecraft:" + k;
    }

    private static Range range(Node c, String key, Range def, double min, double max) {
        Object v = c.raw(key);
        if (v == null) return def;
        Range r;
        try {
            r = Range.parse(String.valueOf(v));
        } catch (IllegalArgumentException e) {
            c.problems().error(c.pathOf(key), e.getMessage(), "a number or a range like 0.5..1.2", v, null);
            return def;
        }
        if (r.min() < min || r.max() > max) {
            c.problems().error(c.pathOf(key), "is out of range", "between " + min + " and " + max, v, null);
            return def;
        }
        return r;
    }

    private static double[] spread(Node c) {
        Object v = c.raw("spread");
        if (v == null) return new double[]{0.3, 0.3, 0.3};
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d >= 0 && d <= 8) return new double[]{d, d, d};
        }
        if (v instanceof List<?> l && l.size() == 3 && l.stream().allMatch(x -> x instanceof Number)) {
            double[] out = new double[3];
            for (int i = 0; i < 3; i++) out[i] = ((Number) l.get(i)).doubleValue();
            if (out[0] >= 0 && out[1] >= 0 && out[2] >= 0 && out[0] <= 8 && out[1] <= 8 && out[2] <= 8) return out;
        }
        c.problems().error(c.pathOf("spread"), "must be a number or [x, y, z] between 0 and 8", "e.g. 0.4 or [0.4, 1.0, 0.4]", v, null);
        return new double[]{0.3, 0.3, 0.3};
    }

    private static int color(Node c, String key, int def) {
        Object v = c.raw(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        if (s.matches("#[0-9a-fA-F]{6}")) return Integer.parseInt(s.substring(1), 16);
        c.problems().error(c.pathOf(key), "is not a colour", "#rrggbb, e.g. \"#7c2ad6\" (quoted)", v, null);
        return def;
    }

    private static SpectatorTier.Audience audience(Node c) {
        return audienceOr(c, SpectatorTier.Audience.ALL);
    }

    private static SpectatorTier.Audience audienceOr(Node c, SpectatorTier.Audience def) {
        return enumOf(c, "to", SpectatorTier.Audience.class, def);
    }

    private static Cue.Channel channel(Node c) {
        return enumOf(c, "channel", Cue.Channel.class, Cue.Channel.BLOCK);
    }

    private static <E extends Enum<E>> E enumOf(Node c, String key, Class<E> type, E def) {
        Object v = c.raw(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E e : type.getEnumConstants()) if (e.name().equals(s)) return e;
        List<String> names = new ArrayList<>();
        for (E e : type.getEnumConstants()) names.add(e.name().toLowerCase(Locale.ROOT));
        c.problems().error(c.pathOf(key), "is not a valid choice", "one of " + names, v, null);
        return def;
    }

    private static void reportUnknown(Node cue, String path, Problems problems) {
        for (String k : cue.keys()) {
            if (!List.of("at", "chance", "sound", "volume", "pitch", "jitter", "to", "channel", "particle", "count", "spread",
                    "speed", "anchor", "shape", "radius", "height", "points", "color", "to-color", "size", "value",
                    "lightning", "display", "charge").contains(k)) {
                problems.warning(path + "." + k, "unknown cue setting (ignored)");
            }
        }
    }
}
