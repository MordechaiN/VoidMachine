package com.voidmachine.core.machine;

import com.voidmachine.core.config.ConfigProblem;
import com.voidmachine.core.config.Node;
import com.voidmachine.core.config.Problems;
import com.voidmachine.core.util.Ids;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code machines.yml} model. Reading is lenient (a broken entry is reported and kept on disk by the
 * writer's caller, never silently dropped); writing produces the V2 layout. V1 files (no
 * {@code format} key, names as keys) are converted transparently.
 */
public final class MachineFile {

    public static final String FILE = "machines.yml";
    public static final int FORMAT = 2;

    public record Result(List<MachineRecord> machines, List<ConfigProblem> problems, boolean wasV1,
                         Map<String, Object> unreadable) {
    }

    private MachineFile() {
    }

    public static Result read(Map<String, Object> tree) {
        Problems p = new Problems(FILE);
        if (tree == null) return new Result(List.of(), List.of(), false, Map.of());
        boolean v1 = !tree.containsKey("format");
        Object raw = tree.get("machines");
        List<MachineRecord> out = new ArrayList<>();
        Map<String, Object> unreadable = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> machines)) return new Result(out, p.all(), v1, unreadable);
        Set<String> usedIds = new HashSet<>();
        Set<String> usedKeys = new HashSet<>();
        for (Map.Entry<?, ?> e : machines.entrySet()) {
            String key = String.valueOf(e.getKey());
            String path = "machines." + key;
            if (!(e.getValue() instanceof Map<?, ?> m)) {
                p.error(path, "is not a machine section (kept, ignored)");
                unreadable.put(key, e.getValue());
                continue;
            }
            Node n = Node.at(path, Node.stringKeys(m), p).quiet();
            String id = v1 ? Ids.sanitize(key) : key;
            if (id == null || !Ids.isValid(id)) {
                p.error(path, "invalid machine id (kept, ignored)", Ids.RULE, key, "rename the key");
                unreadable.put(key, e.getValue());
                continue;
            }
            String unique = id;
            for (int i = 2; usedIds.contains(unique); i++) unique = (id.length() > 28 ? id.substring(0, 28) : id) + "-" + i;
            String world = n.string("world", "");
            if (world.isBlank()) {
                p.error(path + ".world", "is missing (kept, ignored)");
                unreadable.put(key, e.getValue());
                continue;
            }
            int x = n.integer("x", 0, -30_000_000, 30_000_000);
            int y = n.integer("y", 64, -2048, 4096);
            int z = n.integer("z", 0, -30_000_000, 30_000_000);
            String profileRaw = n.string("profile", "default");
            String profile = Ids.isValid(profileRaw) ? profileRaw : Ids.sanitize(profileRaw);
            if (profile == null) profile = "default";
            MachineRecord rec = new MachineRecord(unique, n.string("name", key), world, x, y, z, profile,
                    n.bool("enabled", true), (long) n.decimal("created-at", 0, 0, Long.MAX_VALUE), n.string("created-by", v1 ? "v1" : "unknown"));
            if (!usedKeys.add(rec.locationKey())) {
                p.error(path, "another machine already occupies " + rec.locationKey() + " (kept, ignored)");
                unreadable.put(key, e.getValue());
                continue;
            }
            usedIds.add(unique);
            out.add(rec);
        }
        return new Result(out, p.all(), v1, unreadable);
    }

    public static Map<String, Object> write(List<MachineRecord> machines, Map<String, Object> unreadable) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        Map<String, Object> ms = new LinkedHashMap<>();
        for (MachineRecord m : machines) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", m.displayName());
            s.put("world", m.world());
            s.put("x", m.x());
            s.put("y", m.y());
            s.put("z", m.z());
            s.put("profile", m.profile());
            s.put("enabled", m.enabled());
            s.put("created-at", m.createdAt());
            s.put("created-by", m.createdBy());
            ms.put(m.id(), s);
        }
        // Entries that could not be read are written back untouched so nothing is ever lost.
        unreadable.forEach((k, v) -> {
            if (!ms.containsKey(k)) ms.put(k, v);
        });
        root.put("machines", ms);
        return root;
    }
}
