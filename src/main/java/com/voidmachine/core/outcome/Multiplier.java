package com.voidmachine.core.outcome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Exact, non-negative rational reward multiplier ({@code numerator / denominator}).
 *
 * <p>Rationals avoid floating-point drift: {@code 0.5} is stored as {@code 1/2}, and the reward for
 * any input is computed with integer arithmetic and rounded <em>down</em> (the Void keeps the
 * remainder).</p>
 */
public record Multiplier(int numerator, int denominator) {

    /** Upper bound on any multiplier. Protects the economy against config typos (e.g. 500 instead of 5). */
    public static final int MAX_FACTOR = 100;

    public static final Multiplier ZERO = new Multiplier(0, 1);
    public static final Multiplier ONE = new Multiplier(1, 1);

    public Multiplier {
        if (denominator < 1) {
            throw new IllegalArgumentException("denominator must be >= 1, got " + denominator);
        }
        if (numerator < 0) {
            throw new IllegalArgumentException("multiplier cannot be negative, got " + numerator + "/" + denominator);
        }
        if ((long) numerator > (long) MAX_FACTOR * denominator) {
            throw new IllegalArgumentException("multiplier " + numerator + "/" + denominator
                    + " exceeds the maximum of " + MAX_FACTOR);
        }
        int g = gcd(numerator, denominator);
        if (g > 1) {
            numerator /= g;
            denominator /= g;
        }
    }

    /**
     * Parses {@code "2"}, {@code "0.5"}, {@code "1/2"} or {@code "x3"}.
     *
     * @throws IllegalArgumentException with a human-readable reason if the value is not a valid multiplier
     */
    public static Multiplier parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("multiplier is missing");
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("x") || s.startsWith("×")) s = s.substring(1).trim();
        if (s.isEmpty()) throw new IllegalArgumentException("multiplier is empty");
        try {
            int slash = s.indexOf('/');
            if (slash >= 0) {
                int n = Integer.parseInt(s.substring(0, slash).trim());
                int d = Integer.parseInt(s.substring(slash + 1).trim());
                return new Multiplier(n, d);
            }
            BigDecimal value = new BigDecimal(s);
            if (value.signum() < 0) throw new IllegalArgumentException("multiplier cannot be negative: " + raw);
            if (value.scale() > 4) throw new IllegalArgumentException("multiplier has more than 4 decimals: " + raw);
            BigDecimal scaled = value.setScale(Math.max(0, value.scale()), RoundingMode.UNNECESSARY);
            int scale = scaled.scale();
            int denominator = (int) Math.pow(10, scale);
            int numerator = scaled.movePointRight(scale).intValueExact();
            return new Multiplier(numerator, denominator);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a number (examples: 2, 0.5, 1/2)");
        }
    }

    /** Reward for {@code amount} items, rounded down. Never overflows for {@code amount <= Integer.MAX_VALUE}. */
    public long apply(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount cannot be negative");
        return Math.multiplyExact(amount, (long) numerator) / denominator;
    }

    public boolean isZero() {
        return numerator == 0;
    }

    public boolean isOne() {
        return numerator == denominator;
    }

    public int compareToOne() {
        return Integer.compare(numerator, denominator);
    }

    /** Plain decimal form for messages: {@code 2}, {@code 0.5}, {@code 1.25}. */
    public String toDisplayString() {
        if (denominator == 1) return Integer.toString(numerator);
        return new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), 4, RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** Canonical storage form ({@code n/d} or {@code n}). Round-trips through {@link #parse(String)}. */
    public String toStorageString() {
        return denominator == 1 ? Integer.toString(numerator) : numerator + "/" + denominator;
    }

    @Override
    public String toString() {
        return "×" + toDisplayString();
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.max(1, a);
    }
}
