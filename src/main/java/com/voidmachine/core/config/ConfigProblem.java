package com.voidmachine.core.config;

/**
 * One configuration problem, phrased for a server admin: where, what was expected, what was found,
 * and how to fix it.
 */
public record ConfigProblem(Severity severity, String file, String path, String message,
                            String expected, String found, String hint) {

    public enum Severity {
        /** The setting cannot be used. VoidMachine refuses new rituals until it is fixed. */
        ERROR,
        /** Suspicious or missing; a safe default is used. */
        WARNING
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(severity).append(' ').append(file).append(" → ").append(path.isEmpty() ? "(root)" : path)
                .append(": ").append(message);
        if (expected != null && !expected.isEmpty()) sb.append(" | expected: ").append(expected);
        if (found != null && !found.isEmpty()) sb.append(" | found: ").append(found);
        if (hint != null && !hint.isEmpty()) sb.append(" | fix: ").append(hint);
        return sb.toString();
    }

    @Override
    public String toString() {
        return render();
    }
}
