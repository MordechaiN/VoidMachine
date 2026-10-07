package com.voidmachine.bench;

import com.voidmachine.core.journal.FileJournal;
import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.outcome.Multiplier;
import com.voidmachine.core.outcome.OutcomeDefinition;
import com.voidmachine.core.outcome.OutcomeEngine;
import com.voidmachine.core.outcome.OutcomeTable;
import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.outcome.RandomSource;
import com.voidmachine.core.outcome.Verdict;
import com.voidmachine.core.text.BidiText;
import com.voidmachine.it.Harness;
import com.voidmachine.it.TestPlayer;
import com.voidmachine.paper.diag.TickProfiler;
import com.voidmachine.paper.machine.Machine;
import org.bukkit.Material;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Measured, not estimated. Run with {@code gradle benchmark}. Numbers depend on the machine; the
 * report prints the environment next to them.
 *
 * <p>The tick benchmarks run on MockBukkit: they measure VoidMachine's own CPU work per server tick
 * (scheduling, budget, audience, cue decisions, text rendering). Packet encoding and network I/O of a
 * real server are not included.</p>
 */
@Tag("benchmark")
class Benchmarks {

    private static void header(String title) {
        System.out.printf(Locale.ROOT, "%n=== %s ===%n%s, %d cores, Java %s%n", title, System.getProperty("os.name"),
                Runtime.getRuntime().availableProcessors(), System.getProperty("java.version"));
    }

    private static String pct(long[] sorted, double p) {
        int i = (int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1);
        return String.format(Locale.ROOT, "%.3f", sorted[Math.max(0, i)] / 1e6);
    }

    @Test
    void directorTickCost() {
        header("Ritual director: CPU per server tick");
        int[][] scenarios = {{1, 20}, {4, 20}, {8, 40}};
        for (int[] sc : scenarios) {
            int rituals = sc[0];
            int players = sc[1];
            try (Harness h = new Harness(c -> c.replace("max-active-rituals: 4", "max-active-rituals: " + rituals))) {
                List<Machine> machines = new ArrayList<>();
                for (int i = 0; i < rituals; i++) machines.add(h.machine("b" + i, "t-jackpot", i * 12, 64, 0));
                List<TestPlayer> owners = new ArrayList<>();
                for (int i = 0; i < players; i++) {
                    TestPlayer p = h.player("B" + i, machines.get(i % rituals));
                    p.teleport(machines.get(i % rituals).location().clone().add(1 + (i % 5), 1, 2 + (i % 3)));
                    if (i < rituals) owners.add(p);
                }
                // Warm-up round (JIT), then the measured round.
                for (int round = 0; round < 2; round++) {
                    for (int i = 0; i < rituals; i++) {
                        TestPlayer p = owners.get(i);
                        Harness.hold(p, Material.DIAMOND, 4);
                        h.offerHeld(p, machines.get(i));
                    }
                    h.awaitNoRituals();
                    if (round == 0) h.rt().director().profiler().reset();
                }
                TickProfiler prof = h.rt().director().profiler();
                System.out.printf(Locale.ROOT, "%d concurrent jackpot rituals, %d players: avg %.3f ms, p99 %.3f ms, max %.3f ms over %d ticks"
                                + " (budget thinned %d particles)%n",
                        rituals, players, prof.averageMillis(), prof.percentileMillis(0.99), prof.maxMillis(), prof.runs(),
                        h.rt().budget().deniedParticles());
            }
        }
    }

    @Test
    void ambientTickCost() {
        header("Ambient idle animation: CPU per run");
        try (Harness h = new Harness()) {
            List<Machine> machines = new ArrayList<>();
            for (int i = 0; i < 10; i++) machines.add(h.machine("a" + i, "t-loss", i * 16, 64, 0));
            for (int i = 0; i < 20; i++) h.player("A" + i, machines.get(i % 10));
            h.ticks(3000);
            TickProfiler prof = h.rt().ambient().profiler();
            System.out.printf(Locale.ROOT, "10 machines, 20 players nearby: avg %.3f ms, max %.3f ms over %d runs (%d machines animated)%n",
                    prof.averageMillis(), prof.maxMillis(), prof.runs(), h.rt().ambient().animatedMachines());
        }
    }

    @Test
    void journalDurableWriteLatency(@TempDir Path dir) throws Exception {
        header("Journal: durable create (fsync file + directory) and delete");
        FileJournal journal = new FileJournal(dir);
        OutcomeDefinition doubled = new OutcomeDefinition("doubled", Multiplier.parse("2"), OutcomeTier.WIN);
        int n = 300;
        long[] create = new long[n];
        long[] delete = new long[n];
        byte[] template = new byte[220];
        for (int i = 0; i < n; i++) {
            Verdict v = OutcomeEngine.verdictFor(doubled, 16, 320);
            JournalRecord r = JournalRecord.forRitual(UUID.randomUUID(), UUID.randomUUID(), "Bench", "m", "world:0:64:0", "default",
                    System.currentTimeMillis(), "minecraft:diamond", template, v);
            long t0 = System.nanoTime();
            journal.create(r);
            long t1 = System.nanoTime();
            journal.delete(r.ritualId());
            long t2 = System.nanoTime();
            create[i] = t1 - t0;
            delete[i] = t2 - t1;
        }
        Arrays.sort(create);
        Arrays.sort(delete);
        System.out.printf(Locale.ROOT, "create: p50 %s ms, p99 %s ms, max %s ms | delete: p50 %s ms, p99 %s ms (n=%d, off the main thread in the plugin)%n",
                pct(create, 0.5), pct(create, 0.99), pct(create, 1.0), pct(delete, 0.5), pct(delete, 0.99), n);
    }

    @Test
    void outcomeSelectionThroughput() {
        header("Outcome engine: selections per second (SecureRandom)");
        Map<OutcomeDefinition, Double> w = new LinkedHashMap<>();
        w.put(new OutcomeDefinition("consumed", Multiplier.ZERO, OutcomeTier.LOSS), 64.0);
        w.put(new OutcomeDefinition("returned", Multiplier.ONE, OutcomeTier.NEUTRAL), 20.0);
        w.put(new OutcomeDefinition("doubled", Multiplier.parse("2"), OutcomeTier.WIN), 4.0);
        w.put(new OutcomeDefinition("jackpot", Multiplier.parse("5"), OutcomeTier.JACKPOT), 0.2);
        OutcomeTable table = OutcomeTable.of("bench", w);
        OutcomeEngine engine = new OutcomeEngine(RandomSource.secure());
        for (int i = 0; i < 200_000; i++) engine.decide(table, 16, 320);
        int n = 1_000_000;
        long t0 = System.nanoTime();
        long sink = 0;
        for (int i = 0; i < n; i++) sink += engine.decide(table, 16, 320).rewardAmount();
        long ns = System.nanoTime() - t0;
        System.out.printf(Locale.ROOT, "%,.0f decisions/s (%.0f ns each; checksum %d)%n", n / (ns / 1e9), (double) ns / n, sink);
    }

    @Test
    void bidiReorderCost() {
        header("Bedrock Hebrew reordering: cost per message");
        String text = "הריק מחזיר 32 × Diamond שהוא שמר בשבילך (מכונה main_machine).";
        for (int i = 0; i < 100_000; i++) BidiText.toVisual(text, true);
        int n = 500_000;
        long t0 = System.nanoTime();
        int sink = 0;
        for (int i = 0; i < n; i++) sink += BidiText.toVisual(text, true).length();
        long ns = System.nanoTime() - t0;
        System.out.printf(Locale.ROOT, "%.0f ns per %d-char message (checksum %d)%n", (double) ns / n, text.length(), sink);
    }
}
