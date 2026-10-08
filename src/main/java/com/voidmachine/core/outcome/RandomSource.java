package com.voidmachine.core.outcome;

import java.security.SecureRandom;
import java.util.SplittableRandom;

/**
 * Source of randomness. Production uses {@link #secure()}; tests inject {@link #seeded(long)} so
 * every roll is reproducible. Nothing in VoidMachine calls {@code Math.random()} or
 * {@code ThreadLocalRandom} for anything that matters.
 */
public interface RandomSource {

    /** Uniform long in {@code [0, bound)}. */
    long nextLong(long bound);

    /** Uniform int in {@code [0, bound)}. */
    default int nextInt(int bound) {
        return (int) nextLong(bound);
    }

    /** Uniform double in {@code [0, 1)}. */
    double nextDouble();

    /** A fresh 64-bit seed (used to derive per-ritual presentation randomness). */
    long nextSeed();

    /**
     * Cryptographically strong source: outcomes cannot be predicted from server timing or from
     * observing previous results.
     */
    static RandomSource secure() {
        return new Secure(new SecureRandom());
    }

    /** Deterministic source for tests and for presentation streams derived from a ritual seed. */
    static RandomSource seeded(long seed) {
        return new Seeded(new SplittableRandom(seed));
    }

    final class Secure implements RandomSource {
        private final SecureRandom random;

        Secure(SecureRandom random) {
            this.random = random;
        }

        @Override
        public long nextLong(long bound) {
            if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
            return random.nextLong(bound);
        }

        @Override
        public double nextDouble() {
            return random.nextDouble();
        }

        @Override
        public long nextSeed() {
            return random.nextLong();
        }
    }

    final class Seeded implements RandomSource {
        private final SplittableRandom random;

        Seeded(SplittableRandom random) {
            this.random = random;
        }

        @Override
        public long nextLong(long bound) {
            if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
            return random.nextLong(bound);
        }

        @Override
        public double nextDouble() {
            return random.nextDouble();
        }

        @Override
        public long nextSeed() {
            return random.nextLong();
        }
    }
}
