package com.voidmachine.core.util;

import java.util.regex.Pattern;

/** Validation for identifiers that end up as file names, YAML keys or command arguments. */
public final class Ids {

    /** Human-readable rule, used verbatim in error messages. */
    public static final String RULE = "1-32 characters: lowercase letters, digits, '_' or '-', starting with a letter or digit";

    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    private Ids() {
    }

    public static boolean isValid(String id) {
        return id != null && VALID.matcher(id).matches();
    }

    /** Returns {@code id} unchanged if valid, otherwise throws with {@link #RULE}. */
    public static String require(String id, String what) {
        if (!isValid(id)) {
            throw new IllegalArgumentException("invalid " + what + " '" + id + "' (" + RULE + ")");
        }
        return id;
    }

    /**
     * Best-effort conversion of a legacy (V1) name into a valid id. Returns {@code null} if nothing
     * usable remains.
     */
    public static String sanitize(String legacy) {
        if (legacy == null) return null;
        StringBuilder out = new StringBuilder();
        for (char c : legacy.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                out.append(c);
            } else if (c == ' ' || c == '.') {
                out.append('_');
            }
            if (out.length() == 32) break;
        }
        while (!out.isEmpty() && (out.charAt(0) == '_' || out.charAt(0) == '-')) out.deleteCharAt(0);
        return out.isEmpty() ? null : out.toString();
    }
}
