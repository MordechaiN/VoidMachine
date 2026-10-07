package com.voidmachine.core.script;

/**
 * A value that may ramp over a phase: {@code 0.5} is constant, {@code "0.5..1.2"} interpolates from
 * the start to the end of the phase (by phase progress 0..1).
 */
public record Range(double from, double to) {

    public static Range of(double v) {
        return new Range(v, v);
    }

    public double at(double progress) {
        double p = Math.max(0, Math.min(1, progress));
        return from + (to - from) * p;
    }

    public boolean isConstant() {
        return from == to;
    }

    public double max() {
        return Math.max(from, to);
    }

    public double min() {
        return Math.min(from, to);
    }

    /**
     * Parses {@code "1.5"} or {@code "0.5..1.2"}.
     *
     * @throws IllegalArgumentException if not a number or range
     */
    public static Range parse(String raw) {
        String s = raw.trim();
        int dots = s.indexOf("..");
        try {
            if (dots >= 0) {
                return new Range(Double.parseDouble(s.substring(0, dots).trim()), Double.parseDouble(s.substring(dots + 2).trim()));
            }
            return of(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a number or a range like 0.5..1.2");
        }
    }

    @Override
    public String toString() {
        return isConstant() ? Double.toString(from) : from + ".." + to;
    }
}
