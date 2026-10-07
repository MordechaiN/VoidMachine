package com.voidmachine.paper.diag;

/**
 * Rolling timing of a repeating task (nanoseconds per run). Lets admins see the plugin's real cost on
 * their own server with {@code /vm admin diagnostics} instead of trusting estimates.
 */
public final class TickProfiler {

    private final long[] samples;
    private int next;
    private int filled;
    private long max;
    private long total;
    private long runs;

    public TickProfiler(int window) {
        this.samples = new long[window];
    }

    public void record(long nanos) {
        samples[next] = nanos;
        next = (next + 1) % samples.length;
        if (filled < samples.length) filled++;
        if (nanos > max) max = nanos;
        total += nanos;
        runs++;
    }

    public double averageMillis() {
        if (filled == 0) return 0;
        long sum = 0;
        for (int i = 0; i < filled; i++) sum += samples[i];
        return sum / (double) filled / 1_000_000.0;
    }

    public double percentileMillis(double p) {
        if (filled == 0) return 0;
        long[] copy = new long[filled];
        System.arraycopy(samples, 0, copy, 0, filled);
        java.util.Arrays.sort(copy);
        int idx = (int) Math.min(filled - 1, Math.ceil(p * filled) - 1);
        return copy[Math.max(0, idx)] / 1_000_000.0;
    }

    public double maxMillis() {
        return max / 1_000_000.0;
    }

    public long runs() {
        return runs;
    }

    public double totalMillis() {
        return total / 1_000_000.0;
    }
}
