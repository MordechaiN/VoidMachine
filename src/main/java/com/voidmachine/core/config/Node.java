package com.voidmachine.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Typed, problem-reporting view of a parsed YAML section ({@code Map<String, Object>}).
 *
 * <p>Every accessor validates type and range and reports a precise {@link ConfigProblem} instead of
 * throwing; on error a safe default is returned so the rest of the file is still checked and the
 * admin sees every problem at once. Keys that are never read are reported as unknown (likely typos).</p>
 */
public final class Node {

    private final String path;
    private final Map<String, Object> values;
    private final Problems problems;
    private final Set<String> read = new LinkedHashSet<>();
    private final List<Node> children = new ArrayList<>();
    private final boolean present;
    private boolean warnMissing = true;

    private Node(String path, Map<String, Object> values, Problems problems, boolean present) {
        this.path = path;
        this.values = values;
        this.problems = problems;
        this.present = present;
    }

    public static Node root(Map<String, Object> values, Problems problems) {
        return at("", values, problems);
    }

    /** A detached node whose problems are reported under {@code path} (e.g. list elements). */
    public static Node at(String path, Map<String, Object> values, Problems problems) {
        return new Node(path, values == null ? Map.of() : values, problems, values != null);
    }

    public String path() {
        return path;
    }

    /** Optional-heavy sections (cues, scripts): missing keys silently use their defaults. */
    public Node quiet() {
        this.warnMissing = false;
        return this;
    }

    public boolean present() {
        return present;
    }

    public Problems problems() {
        return problems;
    }

    public String pathOf(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    /** Raw value, marking the key as read. */
    public Object raw(String key) {
        read.add(key);
        return values.get(key);
    }

    /** Child section. A missing section yields an empty node (defaults apply) and a warning if {@code warnIfMissing}. */
    public Node section(String key, boolean warnIfMissing) {
        Object v = raw(key);
        if (v == null) {
            if (warnIfMissing) {
                problems.warning(pathOf(key), "section is missing; defaults are used", "a section", null,
                        "copy it from the default config.yml");
            }
            Node n = new Node(pathOf(key), Map.of(), problems, false);
            children.add(n);
            return n;
        }
        if (!(v instanceof Map<?, ?> m)) {
            problems.error(pathOf(key), "must be a section", "a section of settings", v, null);
            Node n = new Node(pathOf(key), Map.of(), problems, false);
            children.add(n);
            return n;
        }
        Node n = new Node(pathOf(key), stringKeys(m), problems, true);
        children.add(n);
        return n;
    }

    public Node section(String key) {
        return section(key, true);
    }

    public int integer(String key, int def, int min, int max) {
        Object v = raw(key);
        if (v == null) {
            missing(key, def);
            return def;
        }
        long value;
        if (v instanceof Number num && isIntegral(num)) {
            value = num.longValue();
        } else if (v instanceof String s && s.trim().matches("-?\\d+")) {
            value = Long.parseLong(s.trim());
        } else {
            problems.error(pathOf(key), "must be a whole number", "a whole number between " + min + " and " + max, v, null);
            return def;
        }
        if (value < min || value > max) {
            problems.error(pathOf(key), "is out of range", "between " + min + " and " + max, v, null);
            return (int) Math.max(min, Math.min(max, value));
        }
        return (int) value;
    }

    public double decimal(String key, double def, double min, double max) {
        Object v = raw(key);
        if (v == null) {
            missing(key, def);
            return def;
        }
        double value;
        if (v instanceof Number num) {
            value = num.doubleValue();
        } else if (v instanceof String s) {
            try {
                value = Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                problems.error(pathOf(key), "must be a number", "a number between " + min + " and " + max, v, null);
                return def;
            }
        } else {
            problems.error(pathOf(key), "must be a number", "a number between " + min + " and " + max, v, null);
            return def;
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            problems.error(pathOf(key), "must be a finite number", "a number between " + min + " and " + max, v, null);
            return def;
        }
        if (value < min || value > max) {
            problems.error(pathOf(key), "is out of range", "between " + min + " and " + max, v, null);
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }

    public boolean bool(String key, boolean def) {
        Object v = raw(key);
        if (v == null) {
            missing(key, def);
            return def;
        }
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.equals("true") || t.equals("yes") || t.equals("on")) return true;
            if (t.equals("false") || t.equals("no") || t.equals("off")) return false;
        }
        problems.error(pathOf(key), "must be true or false", "true or false", v, null);
        return def;
    }

    public String string(String key, String def) {
        Object v = raw(key);
        if (v == null) {
            missing(key, def);
            return def;
        }
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            problems.error(pathOf(key), "must be a single value", "text", v, null);
            return def;
        }
        return String.valueOf(v);
    }

    /** One of {@code allowed} (case-insensitive). */
    public String choice(String key, String def, List<String> allowed) {
        String v = string(key, def);
        String norm = v.trim().toLowerCase(Locale.ROOT);
        for (String a : allowed) if (a.equals(norm)) return a;
        problems.error(pathOf(key), "is not a valid choice", "one of " + allowed, v, null);
        return def;
    }

    public List<String> strings(String key, List<String> def) {
        Object v = raw(key);
        if (v == null) {
            missing(key, def);
            return def;
        }
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Object e = list.get(i);
                if (e == null || e instanceof Map<?, ?> || e instanceof List<?>) {
                    problems.error(pathOf(key) + "[" + i + "]", "must be a single value", "text", e, null);
                } else {
                    out.add(String.valueOf(e));
                }
            }
            return out;
        }
        if (v instanceof String s && s.isBlank()) return List.of();
        problems.error(pathOf(key), "must be a list", "a list, e.g. [a, b]", v, "write it as [value] or as an indented '- value' list");
        return def;
    }

    /** Parses with {@code parser}; a thrown {@link IllegalArgumentException} becomes an error with its message. */
    public <T> Optional<T> parsed(String key, Function<String, T> parser, String expected) {
        Object v = raw(key);
        if (v == null) return Optional.empty();
        try {
            return Optional.ofNullable(parser.apply(String.valueOf(v)));
        } catch (IllegalArgumentException e) {
            problems.error(pathOf(key), e.getMessage(), expected, v, null);
            return Optional.empty();
        }
    }

    /** Reports keys present in this section (recursively) that no accessor read. */
    public void reportUnknownKeys() {
        if (!present) return;
        for (String k : values.keySet()) {
            if (!read.contains(k)) {
                problems.warning(pathOf(k), "unknown setting (ignored)", null, null,
                        "check the spelling against the default file; removed settings are listed in MIGRATION.md");
            }
        }
        for (Node c : children) c.reportUnknownKeys();
    }

    /** Marks a key as consumed without reading it (e.g. free-form sub-maps handled elsewhere). */
    public void consume(String key) {
        read.add(key);
    }

    private void missing(String key, Object def) {
        if (present && warnMissing) {
            problems.warning(pathOf(key), "not set; using default " + def, null, null, null);
        }
    }

    private static boolean isIntegral(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) return true;
        double d = n.doubleValue();
        return d == Math.rint(d) && !Double.isInfinite(d);
    }

    public static Map<String, Object> stringKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }
}
