package com.voidmachine.core.stats;

import com.voidmachine.core.outcome.OutcomeTier;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lifetime statistics. Statistics are observability, never part of transaction correctness.
 *
 * <p>Mutated on the main thread only; {@link #copy()} produces an independent deep copy for the async
 * writer and for command output.</p>
 */
public final class StatsBook {

    /** One completed ritual, as recorded at reveal. */
    public record RitualSummary(UUID playerId, String playerName, String machineId, String itemKey,
                                int input, String outcomeId, OutcomeTier tier, int reward,
                                String jackpotVariant, boolean fakeout, int crowd, int durationTicks,
                                long epochMillis, int hourOfDay) {
    }

    /** A notable result kept for bragging rights. */
    public record Feat(String playerName, String itemKey, int input, int reward, String outcomeId, long epochMillis) {
    }

    public static final class Global {
        long rituals;
        long itemsOffered;
        long itemsReturned;
        long fakeouts;
        long totalDurationTicks;
        long totalCrowd;
        long recoveries;
        long refunds;
        long failures;
        long[] byHour = new long[24];
        Map<String, Long> outcomes = new LinkedHashMap<>();
        Map<String, Long> tiers = new LinkedHashMap<>();
        Map<String, Long> jackpotVariants = new LinkedHashMap<>();
        Map<String, Long> machines = new LinkedHashMap<>();
        Feat biggestWin;
        Feat biggestJackpot;

        public long rituals() { return rituals; }
        public long itemsOffered() { return itemsOffered; }
        public long itemsReturned() { return itemsReturned; }
        public long fakeouts() { return fakeouts; }
        public long recoveries() { return recoveries; }
        public long refunds() { return refunds; }
        public long failures() { return failures; }
        public Map<String, Long> outcomes() { return outcomes; }
        public Map<String, Long> tiers() { return tiers; }
        public Map<String, Long> jackpotVariants() { return jackpotVariants; }
        public Map<String, Long> machines() { return machines; }
        public Optional<Feat> biggestWin() { return Optional.ofNullable(biggestWin); }
        public Optional<Feat> biggestJackpot() { return Optional.ofNullable(biggestJackpot); }

        public double averageDurationSeconds() {
            return rituals == 0 ? 0 : (double) totalDurationTicks / rituals / 20.0;
        }

        public double averageCrowd() {
            return rituals == 0 ? 0 : (double) totalCrowd / rituals;
        }

        public Optional<String> busiestMachine() {
            return machines.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
        }

        public int busiestHour() {
            int best = 0;
            for (int h = 1; h < 24; h++) if (byHour[h] > byHour[best]) best = h;
            return best;
        }

        public long[] byHour() {
            return byHour.clone();
        }

        Global copy() {
            Global g = new Global();
            g.rituals = rituals;
            g.itemsOffered = itemsOffered;
            g.itemsReturned = itemsReturned;
            g.fakeouts = fakeouts;
            g.totalDurationTicks = totalDurationTicks;
            g.totalCrowd = totalCrowd;
            g.recoveries = recoveries;
            g.refunds = refunds;
            g.failures = failures;
            g.byHour = byHour.clone();
            g.outcomes = new LinkedHashMap<>(outcomes);
            g.tiers = new LinkedHashMap<>(tiers);
            g.jackpotVariants = new LinkedHashMap<>(jackpotVariants);
            g.machines = new LinkedHashMap<>(machines);
            g.biggestWin = biggestWin;
            g.biggestJackpot = biggestJackpot;
            return g;
        }
    }

    public static final class PlayerStats {
        String name;
        long rituals;
        long itemsOffered;
        long itemsReturned;
        long jackpots;
        long wins;
        Map<String, Long> outcomes = new LinkedHashMap<>();
        Map<String, Long> machines = new LinkedHashMap<>();
        Feat biggestResult;

        public String name() { return name; }
        public long rituals() { return rituals; }
        public long itemsOffered() { return itemsOffered; }
        public long itemsReturned() { return itemsReturned; }
        public long jackpots() { return jackpots; }
        public long wins() { return wins; }
        public Map<String, Long> outcomes() { return outcomes; }
        public Optional<Feat> biggestResult() { return Optional.ofNullable(biggestResult); }

        public Optional<String> favoriteMachine() {
            return machines.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
        }

        PlayerStats copy() {
            PlayerStats p = new PlayerStats();
            p.name = name;
            p.rituals = rituals;
            p.itemsOffered = itemsOffered;
            p.itemsReturned = itemsReturned;
            p.jackpots = jackpots;
            p.wins = wins;
            p.outcomes = new LinkedHashMap<>(outcomes);
            p.machines = new LinkedHashMap<>(machines);
            p.biggestResult = biggestResult;
            return p;
        }
    }

    Global global = new Global();
    Map<UUID, PlayerStats> players = new HashMap<>();
    private boolean dirty;

    public void record(RitualSummary s) {
        Global g = global;
        g.rituals++;
        g.itemsOffered += s.input();
        g.itemsReturned += s.reward();
        if (s.fakeout()) g.fakeouts++;
        g.totalDurationTicks += Math.max(0, s.durationTicks());
        g.totalCrowd += Math.max(0, s.crowd());
        g.byHour[Math.floorMod(s.hourOfDay(), 24)]++;
        g.outcomes.merge(s.outcomeId(), 1L, Long::sum);
        g.tiers.merge(s.tier().name(), 1L, Long::sum);
        g.machines.merge(s.machineId(), 1L, Long::sum);
        Feat feat = new Feat(s.playerName(), s.itemKey(), s.input(), s.reward(), s.outcomeId(), s.epochMillis());
        if (s.tier() == OutcomeTier.JACKPOT) {
            if (s.jackpotVariant() != null) g.jackpotVariants.merge(s.jackpotVariant(), 1L, Long::sum);
            if (g.biggestJackpot == null || s.reward() > g.biggestJackpot.reward()) g.biggestJackpot = feat;
        }
        if (s.tier().isWin() && (g.biggestWin == null || s.reward() - s.input() > g.biggestWin.reward() - g.biggestWin.input())) {
            g.biggestWin = feat;
        }

        PlayerStats p = players.computeIfAbsent(s.playerId(), k -> new PlayerStats());
        p.name = s.playerName();
        p.rituals++;
        p.itemsOffered += s.input();
        p.itemsReturned += s.reward();
        if (s.tier().isWin()) p.wins++;
        if (s.tier() == OutcomeTier.JACKPOT) p.jackpots++;
        p.outcomes.merge(s.outcomeId(), 1L, Long::sum);
        p.machines.merge(s.machineId(), 1L, Long::sum);
        if (p.biggestResult == null || s.reward() > p.biggestResult.reward()) p.biggestResult = feat;
        dirty = true;
    }

    public void recordRecovery() {
        global.recoveries++;
        dirty = true;
    }

    public void recordRefund() {
        global.refunds++;
        dirty = true;
    }

    public void recordFailure() {
        global.failures++;
        dirty = true;
    }

    /** Imports V1 lifetime counters (one-time migration). */
    public void importLegacy(long rituals, Map<String, Long> outcomeCounts, long itemsOffered) {
        global.rituals += rituals;
        global.itemsOffered += itemsOffered;
        outcomeCounts.forEach((k, v) -> global.outcomes.merge(k, v, Long::sum));
        dirty = true;
    }

    public Global global() {
        return global;
    }

    public Optional<PlayerStats> player(UUID id) {
        return Optional.ofNullable(players.get(id));
    }

    /** Top players by a metric, highest first. */
    public List<Map.Entry<UUID, PlayerStats>> top(Comparator<PlayerStats> metric, int limit) {
        return players.entrySet().stream()
                .sorted(Map.Entry.<UUID, PlayerStats>comparingByValue(metric).reversed())
                .limit(limit)
                .toList();
    }

    public int playerCount() {
        return players.size();
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public StatsBook copy() {
        StatsBook b = new StatsBook();
        b.global = global.copy();
        Map<UUID, PlayerStats> m = new HashMap<>(players.size() * 2);
        players.forEach((k, v) -> m.put(k, v.copy()));
        b.players = m;
        return b;
    }
}
