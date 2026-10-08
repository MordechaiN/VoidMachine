package com.voidmachine.core.budget;

/**
 * Hard per-tick caps on presentation output, shared fairly between concurrent rituals.
 *
 * <ul>
 *   <li><b>particles</b> — total particle count requested by spawn calls in one tick (client load).</li>
 *   <li><b>packets</b> — particle and sound packets sent in one tick (server and network load).</li>
 * </ul>
 * When the caps are reached, effects are scaled down or skipped: spectacle degrades, gameplay never does.
 *
 * <p>Main-thread confined. Call {@link #beginTick(int)} once per tick before any grant.</p>
 */
public final class EffectBudget {

    private volatile int maxParticles;
    private volatile int maxPackets;

    private int particlesLeft;
    private int packetsLeft;
    private int shareParticles;
    private int sharePackets;

    private long deniedParticles;
    private long deniedPackets;
    private long grantedParticles;
    private long grantedPackets;

    public EffectBudget(int maxParticlesPerTick, int maxPacketsPerTick) {
        configure(maxParticlesPerTick, maxPacketsPerTick);
    }

    public void configure(int maxParticlesPerTick, int maxPacketsPerTick) {
        if (maxParticlesPerTick < 0 || maxPacketsPerTick < 0) throw new IllegalArgumentException("budgets cannot be negative");
        this.maxParticles = maxParticlesPerTick;
        this.maxPackets = maxPacketsPerTick;
    }

    /** Resets the per-tick allowance; each of {@code consumers} rituals gets an equal share. */
    public void beginTick(int consumers) {
        int n = Math.max(1, consumers);
        particlesLeft = maxParticles;
        packetsLeft = maxPackets;
        shareParticles = maxParticles / n;
        sharePackets = Math.max(1, maxPackets / n);
    }

    /** A per-ritual view that enforces the fair share for this tick. */
    public Allowance allowance() {
        return new Allowance(shareParticles, sharePackets);
    }

    public final class Allowance {
        private int particles;
        private int packets;

        private Allowance(int particles, int packets) {
            this.particles = particles;
            this.packets = packets;
        }

        /**
         * Requests a particle spawn of {@code count} particles to {@code receivers} players.
         *
         * @return the particle count actually allowed (0 = skip the spawn)
         */
        public int particles(int count, int receivers) {
            if (count <= 0 || receivers <= 0) return 0;
            if (packets < receivers || packetsLeft < receivers) {
                deniedPackets += receivers;
                deniedParticles += count;
                return 0;
            }
            int allowed = Math.min(count, Math.min(particles, particlesLeft));
            if (allowed <= 0) {
                deniedParticles += count;
                return 0;
            }
            particles -= allowed;
            particlesLeft -= allowed;
            packets -= receivers;
            packetsLeft -= receivers;
            grantedParticles += allowed;
            grantedPackets += receivers;
            deniedParticles += count - allowed;
            return allowed;
        }

        /** Requests a sound to {@code receivers} players. */
        public boolean sound(int receivers) {
            if (receivers <= 0) return false;
            if (packets < receivers || packetsLeft < receivers) {
                deniedPackets += receivers;
                return false;
            }
            packets -= receivers;
            packetsLeft -= receivers;
            grantedPackets += receivers;
            return true;
        }
    }

    public long deniedParticles() {
        return deniedParticles;
    }

    public long deniedPackets() {
        return deniedPackets;
    }

    public long grantedParticles() {
        return grantedParticles;
    }

    public long grantedPackets() {
        return grantedPackets;
    }

    public int maxParticles() {
        return maxParticles;
    }

    public int maxPackets() {
        return maxPackets;
    }
}
