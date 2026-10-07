package com.voidmachine.core.migration;

import com.voidmachine.core.util.Ids;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates a V1 (1.x) {@code config.yml} into V2 setting assignments. Pure: the caller applies the
 * assignments to the bundled V2 template (keeping its comments) and keeps the V1 file as a backup.
 *
 * <p>Economy-relevant values are carried over exactly (profile weights, multipliers, caps), so a
 * migrated server keeps its odds. Settings that no longer exist are reported in {@link Result#notes()}.</p>
 */
public final class V1ConfigMigration {

    public record Result(Map<String, Object> assignments, List<String> notes) {
    }

    private V1ConfigMigration() {
    }

    /** V1 files have no {@code config-version} key. */
    public static boolean isV1(Map<String, Object> tree) {
        return tree != null && !tree.containsKey("config-version")
                && (tree.containsKey("profiles") || tree.containsKey("outcomes") || tree.containsKey("machine")
                || tree.containsKey("world-animation") || tree.containsKey("limits"));
    }

    public static Result migrate(Map<String, Object> v1) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        out.put("config-version", 2);

        if (bool(get(v1, "plugin.debug"), false)) out.put("logging.level", "debug");

        // Multipliers.
        Integer doubled = integer(get(v1, "multipliers.doubled"));
        Integer tripled = integer(get(v1, "multipliers.tripled"));
        Integer jackpot = integer(get(v1, "multipliers.jackpot_x5"));
        if (doubled != null) out.put("outcomes.doubled.multiplier", clampMultiplier(doubled, "doubled", notes));
        if (tripled != null) out.put("outcomes.tripled.multiplier", clampMultiplier(tripled, "tripled", notes));
        if (jackpot != null) out.put("outcomes.jackpot.multiplier", clampMultiplier(jackpot, "jackpot", notes));

        // Profiles: carry weights over exactly; the new 'tithe' outcome gets weight 0 so odds are unchanged.
        Map<String, Object> profiles = section(v1, "profiles");
        Map<String, Object> globalWeights = section(v1, "outcomes");
        if (profiles.isEmpty() && !globalWeights.isEmpty()) {
            profiles = Map.of("default", globalWeights);
            notes.add("V1 had no profiles; the global 'outcomes' weights became the 'default' profile.");
        }
        out.put("profiles", null); // replace the template's profiles entirely
        for (Map.Entry<String, Object> e : profiles.entrySet()) {
            String id = Ids.sanitize(e.getKey());
            if (id == null) {
                notes.add("Profile '" + e.getKey() + "' has no usable name and was skipped.");
                continue;
            }
            if (!id.equals(e.getKey())) notes.add("Profile '" + e.getKey() + "' was renamed to '" + id + "'.");
            Map<String, Object> w = e.getValue() instanceof Map<?, ?> m ? stringKeys(m) : Map.of();
            String base = "profiles." + id + ".weights.";
            out.put(base + "consumed", number(w.get("destroyed"), number(get(globalWeights, "destroyed"), 74)));
            out.put(base + "tithe", 0);
            out.put(base + "returned", number(w.get("returned"), number(get(globalWeights, "returned"), 20)));
            out.put(base + "doubled", number(w.get("doubled"), number(get(globalWeights, "doubled"), 4)));
            out.put(base + "tripled", number(w.get("tripled"), number(get(globalWeights, "tripled"), 1.8)));
            out.put(base + "jackpot", number(w.get("jackpot_x5"), number(get(globalWeights, "jackpot_x5"), 0.2)));
            out.put("profiles." + id + ".theme", "void");
            out.put("profiles." + id + ".pacing", "standard");
            out.put("profiles." + id + ".permission", "");
        }
        if (profiles.isEmpty()) {
            out.remove("profiles");
            notes.add("V1 had no profiles or outcome weights; the V2 default profiles are used.");
        }

        // Limits.
        Integer maxInsert = integer(get(v1, "limits.max-insert-amount"));
        int offeringMax = 64;
        if (maxInsert != null) {
            offeringMax = Math.max(1, Math.min(99, maxInsert));
            if (offeringMax != maxInsert) {
                notes.add("limits.max-insert-amount " + maxInsert + " became offering.max-amount " + offeringMax
                        + " (an offering is one inventory slot, at most 99 items).");
            }
            out.put("offering.max-amount", offeringMax);
        }
        Integer maxReturn = integer(get(v1, "limits.max-return-amount"));
        if (maxReturn != null) {
            int reward = Math.max(offeringMax, Math.min(6_400, maxReturn));
            out.put("limits.max-reward-amount", reward);
            if (reward != maxReturn) notes.add("limits.max-return-amount " + maxReturn + " became limits.max-reward-amount " + reward + ".");
        }
        if (get(v1, "limits.clamp-on-overflow") != null) {
            notes.add("limits.clamp-on-overflow was removed: rewards are always clamped to limits.max-reward-amount.");
        }

        // Cooldowns (V1 configured them but never enforced them on the machine path).
        Integer perPlayer = integer(get(v1, "cooldown.per-player-seconds"));
        if (perPlayer != null) {
            out.put("cooldowns.player-seconds", Math.max(0, perPlayer));
            notes.add("cooldown.per-player-seconds (" + perPlayer + ") is now ENFORCED as cooldowns.player-seconds. V1 did not enforce it.");
        }
        if (positive(get(v1, "cooldown.global-seconds"))) {
            notes.add("cooldown.global-seconds has no V2 equivalent and was dropped (see cooldowns.machine-seconds).");
        }
        if (positive(get(v1, "cooldown.daily-limit"))) {
            notes.add("cooldown.daily-limit is not supported in V2 and was dropped. V1 never enforced it.");
        }

        // Worlds.
        copyList(v1, "worlds.allowed", out, "machines.allowed-worlds");
        copyList(v1, "worlds.blocked", out, "machines.blocked-worlds");

        // Offering blacklist.
        copyList(v1, "blacklist.materials", out, "offering.blocked-materials");
        boolean blockContainers = bool(get(v1, "blacklist.block-containers"), true)
                || bool(get(v1, "blacklist.block-bundles"), true)
                || bool(get(v1, "blacklist.block-block-entity"), true);
        out.put("offering.block-filled-containers", blockContainers);
        List<String> pdc = new ArrayList<>(strings(get(v1, "blacklist.block-marked-pdc-keys")));
        pdc.addAll(strings(get(v1, "blacklist.custom-keys")));
        if (!pdc.isEmpty()) out.put("offering.blocked-pdc-keys", pdc.stream().distinct().toList());

        // Machines.
        Object core = get(v1, "machine.core-material");
        if (core != null) out.put("machines.core-block", String.valueOf(core).toUpperCase(Locale.ROOT));
        Integer maxMachines = integer(get(v1, "machine.max-registered"));
        if (maxMachines != null) out.put("machines.max-machines", Math.max(1, Math.min(500, maxMachines)));
        Integer maxAnim = integer(get(v1, "machine.max-concurrent-animations"));
        if (maxAnim != null) out.put("limits.max-active-rituals", Math.max(1, Math.min(50, maxAnim)));
        Object blockCreative = get(v1, "machine.block-creative");
        if (blockCreative != null) out.put("machines.allow-creative", !bool(blockCreative, true));
        Integer spam = integer(get(v1, "machine.anti-spam-gap-ms"));
        if (spam != null) out.put("machines.interact-cooldown-ms", Math.max(0, Math.min(10_000, spam)));

        // Fakeouts.
        Object fakeEnabled = get(v1, "fakeout.enabled");
        Integer oneIn = integer(get(v1, "fakeout.chance-1-in"));
        if (fakeEnabled != null) out.put("presentation.fakeouts.enabled", bool(fakeEnabled, true) && (oneIn == null || oneIn > 0));
        if (oneIn != null && oneIn > 0) {
            out.put("presentation.fakeouts.chance", Math.min(1.0, 1.0 / oneIn));
        }

        // Atmosphere and crowd.
        Object atmosphere = get(v1, "atmosphere.enabled");
        if (atmosphere != null) out.put("ambient.enabled", bool(atmosphere, true));
        Object voidEvents = get(v1, "void-events.enabled");
        if (voidEvents != null) out.put("ambient.void-events.enabled", bool(voidEvents, true));
        Integer veMin = integer(get(v1, "void-events.min-interval-minutes"));
        Integer veMax = integer(get(v1, "void-events.max-interval-minutes"));
        if (veMin != null) out.put("ambient.void-events.min-interval-minutes", Math.max(1, veMin));
        if (veMax != null) out.put("ambient.void-events.max-interval-minutes", Math.max(veMin == null ? 1 : veMin, veMax));
        Integer crowdMin = integer(get(v1, "crowd-awareness.min-players"));
        if (crowdMin != null) {
            out.put("spectators.crowd.min-for-bonus", Math.max(1, crowdMin - 1));
            notes.add("crowd-awareness.min-players counted the offering player; spectators.crowd.min-for-bonus counts onlookers only ("
                    + Math.max(1, crowdMin - 1) + ").");
        }

        // Announcements.
        List<String> broadcastOn = strings(get(v1, "events.broadcast-on"));
        if (!broadcastOn.isEmpty()) {
            out.put("presentation.announce.great", broadcastOn.contains("TRIPLED") ? "server" : "area");
            out.put("presentation.announce.jackpot", broadcastOn.contains("JACKPOT_X5") ? "server" : "area");
        }

        // Removed subsystems.
        for (String removed : List.of("gui", "animation", "world-animation", "sounds", "particles", "storage", "performance",
                "checkpoint", "crowd-awareness.radius", "crowd-awareness.check-interval-ticks", "events.discord", "events.lightning-on",
                "events.broadcast-title-on", "events.log-every-roll", "plugin.language", "plugin.locale")) {
            if (get(v1, removed) != null) notes.add(removedNote(removed));
        }
        return new Result(out, notes);
    }

    private static String removedNote(String key) {
        return switch (key) {
            case "gui" -> "gui.* belonged to the unused V1 legacy GUI and was removed.";
            case "animation", "world-animation" -> key + ".* timings were replaced by pacing presets in rituals.yml.";
            case "sounds", "particles" -> key + ".* were replaced by themes in rituals.yml.";
            case "storage" -> "storage.* (YAML/MySQL) was only used by the unused V1 legacy GUI. V2 stores data in plugins/VoidMachine.";
            case "checkpoint" -> "checkpoint.* was removed: V2 recovery never needs a redelivery switch.";
            case "events.discord" -> "events.discord (DiscordSRV forwarding) is not part of V2. If you used it, forward "
                    + "RitualRevealEvent/RitualJackpotEvent with a small bridge plugin; see docs/MIGRATION.md.";
            case "plugin.language", "plugin.locale" -> key + " was replaced by language.default and per-player client languages.";
            default -> key + " was removed.";
        };
    }

    // ------------------------------------------------------------------------------------------

    static Object get(Map<String, Object> tree, String path) {
        Object cur = tree;
        for (String part : path.split("\\.", -1)) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    private static Map<String, Object> section(Map<String, Object> tree, String path) {
        Object v = get(tree, path);
        return v instanceof Map<?, ?> m ? stringKeys(m) : Map.of();
    }

    private static Map<String, Object> stringKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static Integer integer(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double number(Object v, double def) {
        if (v instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= 0) return n.doubleValue();
        return def;
    }

    private static boolean positive(Object v) {
        Integer i = integer(v);
        return i != null && i > 0;
    }

    private static boolean bool(Object v, boolean def) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return def;
    }

    private static List<String> strings(Object v) {
        if (!(v instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : l) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    private static void copyList(Map<String, Object> v1, String from, Map<String, Object> out, String to) {
        Object v = get(v1, from);
        if (v instanceof List<?>) out.put(to, strings(v));
    }

    private static int clampMultiplier(int value, String what, List<String> notes) {
        int clamped = Math.max(2, Math.min(100, value));
        if (clamped != value) notes.add("multipliers." + what + " " + value + " was clamped to " + clamped + ".");
        return clamped;
    }
}
