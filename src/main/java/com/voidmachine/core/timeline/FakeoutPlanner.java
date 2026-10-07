package com.voidmachine.core.timeline;

import com.voidmachine.core.outcome.OutcomeDefinition;
import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.outcome.RandomSource;
import com.voidmachine.core.outcome.Verdict;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Decides whether a ritual gets a fakeout, and which one. Rate-limited per player and per server,
 * and never repeats the same pattern twice in a row on one machine when an alternative exists, so the
 * theatre stays surprising.
 *
 * <p>Main-thread confined.</p>
 */
public final class FakeoutPlanner {

    public record Settings(boolean enabled, double chance, int minRitualsBetweenPerPlayer,
                           Duration globalCooldown, Set<FakeoutPlan.Pattern> patterns) {
        public Settings {
            patterns = patterns.isEmpty() ? EnumSet.noneOf(FakeoutPlan.Pattern.class) : EnumSet.copyOf(patterns);
        }
    }

    private static final int MAX_TRACKED_PLAYERS = 2048;

    private volatile Settings settings;
    private Instant lastGlobal = Instant.EPOCH;
    private final Map<UUID, Integer> ritualsSinceFakeout = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Integer> eldest) {
            return size() > MAX_TRACKED_PLAYERS;
        }
    };
    private final Map<String, FakeoutPlan.Pattern> lastPatternByMachine = new HashMap<>();

    public FakeoutPlanner(Settings settings) {
        this.settings = settings;
    }

    public void update(Settings settings) {
        this.settings = settings;
    }

    /**
     * @param outcomes the machine profile's outcomes, used to pick a believable decoy
     */
    public Optional<FakeoutPlan> plan(UUID player, String machineId, Verdict verdict,
                                      Collection<OutcomeDefinition> outcomes, RandomSource rnd, Instant now) {
        Settings s = settings;
        int since = ritualsSinceFakeout.getOrDefault(player, Integer.MAX_VALUE / 2);
        ritualsSinceFakeout.put(player, since + 1);
        if (!s.enabled() || s.chance() <= 0) return Optional.empty();
        if (since < s.minRitualsBetweenPerPlayer()) return Optional.empty();
        if (Duration.between(lastGlobal, now).compareTo(s.globalCooldown()) < 0) return Optional.empty();

        List<FakeoutPlan> candidates = new ArrayList<>();
        for (FakeoutPlan.Pattern pattern : s.patterns()) {
            if (!pattern.eligibleFor(verdict.tier())) continue;
            decoyFor(pattern, verdict.tier(), outcomes, rnd).ifPresent(d ->
                    candidates.add(new FakeoutPlan(pattern, d.id(), d.tier())));
        }
        if (candidates.isEmpty()) return Optional.empty();
        if (rnd.nextDouble() >= s.chance()) return Optional.empty();

        FakeoutPlan.Pattern last = lastPatternByMachine.get(machineId);
        List<FakeoutPlan> fresh = candidates.stream().filter(c -> c.pattern() != last).toList();
        List<FakeoutPlan> pool = fresh.isEmpty() ? candidates : fresh;
        FakeoutPlan chosen = pool.get(rnd.nextInt(pool.size()));

        lastPatternByMachine.put(machineId, chosen.pattern());
        lastGlobal = now;
        ritualsSinceFakeout.put(player, 0);
        return Optional.of(chosen);
    }

    /** The decoy must be a real outcome of this machine and strictly worse than the truth. */
    static Optional<OutcomeDefinition> decoyFor(FakeoutPlan.Pattern pattern, OutcomeTier truth,
                                                Collection<OutcomeDefinition> outcomes, RandomSource rnd) {
        List<OutcomeDefinition> pool = new ArrayList<>();
        for (OutcomeDefinition o : outcomes) {
            if (o.tier().rank() >= truth.rank()) continue;
            boolean fits = switch (pattern) {
                case FALSE_LOSS -> o.tier() == OutcomeTier.LOSS;
                case ESCALATION -> o.tier() == OutcomeTier.NEUTRAL || o.tier() == OutcomeTier.WIN || o.tier() == OutcomeTier.GREAT;
            };
            if (fits) pool.add(o);
        }
        if (pool.isEmpty()) return Optional.empty();
        return Optional.of(pool.get(rnd.nextInt(pool.size())));
    }

    /** Forget per-player counters (player quit). */
    public void forget(UUID player) {
        ritualsSinceFakeout.remove(player);
    }

    public void forgetMachine(String machineId) {
        lastPatternByMachine.remove(machineId);
    }
}
