package com.voidmachine.core.custody;

import com.voidmachine.core.journal.FileJournal;
import com.voidmachine.core.journal.Journal;
import com.voidmachine.core.journal.JournalException;
import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.ledger.PlayerLedger;
import com.voidmachine.core.outcome.Multiplier;
import com.voidmachine.core.outcome.OutcomeDefinition;
import com.voidmachine.core.outcome.OutcomeEngine;
import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.outcome.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Crashes the ritual at every possible step — including in the middle of journal writes and player
 * saves, and again in the middle of recovery — and proves the only possible end states are:
 *
 * <pre>
 *   items = before                      (the capture never reached the saved player file), or
 *   items = before - offered + reward   (the verdict was fully applied)
 * </pre>
 *
 * with no journal records and no ledger entries left behind.
 */
class CrashConsistencyTest {

    @TempDir
    Path dir;

    private static final int SLOT = 0;

    record Scenario(String name, String outcome, OutcomeTier tier, Multiplier mult, int stack, int offer,
                    int room, boolean failCaptureSave, boolean failPayoutSave) {
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("consumed", "consumed", OutcomeTier.LOSS, Multiplier.ZERO, 64, 64, 2000, false, false),
            new Scenario("returned", "returned", OutcomeTier.NEUTRAL, Multiplier.ONE, 64, 32, 2000, false, false),
            new Scenario("tithe", "tithe", OutcomeTier.PARTIAL, new Multiplier(1, 2), 10, 7, 2000, false, false),
            new Scenario("doubled", "doubled", OutcomeTier.WIN, new Multiplier(2, 1), 64, 64, 2000, false, false),
            new Scenario("jackpot-tight-room", "jackpot", OutcomeTier.JACKPOT, new Multiplier(5, 1), 64, 64, 70, false, false),
            new Scenario("jackpot-no-room", "jackpot", OutcomeTier.JACKPOT, new Multiplier(5, 1), 64, 64, 0, false, false),
            new Scenario("doubled-capture-save-fails", "doubled", OutcomeTier.WIN, new Multiplier(2, 1), 16, 16, 2000, true, false),
            new Scenario("doubled-payout-save-fails", "doubled", OutcomeTier.WIN, new Multiplier(2, 1), 16, 16, 2000, false, true),
            new Scenario("consumed-capture-save-fails", "consumed", OutcomeTier.LOSS, Multiplier.ZERO, 5, 5, 2000, true, false)
    );

    @Test
    void everyCrashPointConverges() throws Exception {
        int cases = 0;
        for (Scenario s : SCENARIOS) {
            int totalSteps = countSteps(s);
            for (int crashAt = 1; crashAt <= totalSteps + 1; crashAt++) {
                // Crash once in the live flow, and also crash at every point of the first recovery.
                for (int recoveryCrashAt = 0; recoveryCrashAt <= 12; recoveryCrashAt++) {
                    runCase(s, crashAt, recoveryCrashAt);
                    cases++;
                }
            }
        }
        assertTrue(cases > 500, "expected a large crash matrix, ran " + cases);
    }

    @Test
    void randomizedCrashesConverge() throws Exception {
        SplittableRandom rnd = new SplittableRandom(0xC0FFEE);
        for (int i = 0; i < 400; i++) {
            Scenario base = SCENARIOS.get(rnd.nextInt(SCENARIOS.size()));
            Scenario s = new Scenario(base.name() + "#" + i, base.outcome(), base.tier(), base.mult(),
                    base.stack(), base.offer(), rnd.nextInt(0, 400), rnd.nextBoolean(), rnd.nextBoolean());
            int steps = countSteps(s);
            runCase(s, 1 + rnd.nextInt(steps + 1), rnd.nextInt(0, 15));
        }
    }

    /**
     * Plugin reload mid-session (LIVE reconciliation against the in-memory ledger), with every player
     * save failing silently, then a crash. A record must never be deleted on the word of memory alone.
     */
    @Test
    void reloadWithSilentSaveFailuresNeverLosesItems() throws Exception {
        for (Scenario base : SCENARIOS) for (boolean captureSaveFails : new boolean[]{false, true}) {
            // The dangerous combination is: capture reached disk, payout did not.
            Scenario s = new Scenario(base.name() + (captureSaveFails ? "/all-saves-fail" : "/payout-save-fails"),
                    base.outcome(), base.tier(), base.mult(), base.stack(),
                    base.offer(), base.room(), captureSaveFails, true);
            Path caseDir = Files.createTempDirectory(dir, "reload");
            SimPlayer p = player(s);
            int before = p.diskItems();
            Verdict verdict = verdict(s);
            Journal journal = new FileJournal(caseDir);
            liveFlow(s, p, journal);

            // Reload: LIVE reconciliation with the in-memory ledger.
            List<JournalRecord> mine = journal.loadAll().records();
            CustodyEngine.Plan plan = CustodyEngine.plan(mine, PlayerLedger.decode(p.readLedger()),
                    CustodyEngine.Mode.LIVE, Set.of());
            for (CustodyEngine.Decision d : plan.decisions()) {
                if (d.action() == CustodyEngine.Action.FINALIZE || d.action() == CustodyEngine.Action.DISCARD_NOT_CAPTURED) {
                    journal.delete(d.record().ritualId());
                }
            }
            p.crash(); // every save failed: disk still holds the state from before the ritual
            p.saveFails = false;
            for (int pass = 0; pass < 4; pass++) {
                recover(p, journal, true);
                p.save();
                p.crash();
            }
            int after = p.diskItems();
            int applied = before - verdict.inputAmount() + verdict.rewardAmount();
            assertTrue(after == before || after == applied,
                    s.name() + ": items " + after + " is neither " + before + " nor " + applied);
            assertEquals(0, journal.loadAll().records().size(), s.name() + ": records left behind");
        }
    }

    private int countSteps(Scenario s) throws Exception {
        Path caseDir = Files.createTempDirectory(dir, "count");
        Counter counter = new Counter(Integer.MAX_VALUE);
        SimPlayer p = player(s);
        p.beforeStep = counter::tick;
        Journal journal = new CountingJournal(new FileJournal(caseDir), counter);
        liveFlow(s, p, journal);
        return counter.steps;
    }

    private void runCase(Scenario s, int crashAt, int recoveryCrashAt) throws Exception {
        String label = s.name() + " crashAt=" + crashAt + " recoveryCrashAt=" + recoveryCrashAt;
        Path caseDir = Files.createTempDirectory(dir, "case");
        SimPlayer p = player(s);
        int before = p.diskItems();
        Verdict verdict = verdict(s);

        Counter counter = new Counter(crashAt);
        p.beforeStep = counter::tick;
        Journal journal = new CountingJournal(new FileJournal(caseDir), counter);
        boolean crashed = false;
        try {
            liveFlow(s, p, journal);
        } catch (SimPlayer.Crash c) {
            crashed = true;
        }
        if (!crashed) {
            // Normal quit: the server saves the player as they leave.
            p.saveFails = false;
            p.beforeStep = () -> { };
            p.save();
        }
        p.crash(); // whatever happened, the next join loads what is on disk
        p.saveFails = false;

        // First recovery, itself crashing at `recoveryCrashAt` (0 = no crash).
        if (recoveryCrashAt > 0) {
            Counter rc = new Counter(recoveryCrashAt);
            p.beforeStep = rc::tick;
            Journal rj = new CountingJournal(new FileJournal(caseDir), rc);
            try {
                recover(p, rj, true);
            } catch (SimPlayer.Crash c) {
                p.crash();
            }
        }

        // Then clean recoveries until nothing changes (bounded).
        p.beforeStep = () -> { };
        Journal clean = new FileJournal(caseDir);
        for (int pass = 0; pass < 6; pass++) {
            recover(p, clean, true);
            p.save();
            p.crash(); // quit + join between passes
        }

        int after = p.diskItems();
        int applied = before - verdict.inputAmount() + verdict.rewardAmount();
        if (after != before && after != applied) {
            fail(label + ": items " + after + " is neither " + before + " (rolled back) nor " + applied + " (applied). " + p);
        }
        assertEquals(0, clean.loadAll().records().size(), label + ": journal records left behind");
        assertTrue(PlayerLedger.decode(p.diskLedger()).isEmpty(), label + ": ledger entries left behind: " + p.diskLedger());
    }

    private void liveFlow(Scenario s, SimPlayer p, Journal journal) throws JournalException {
        Verdict verdict = verdict(s);
        JournalRecord record = JournalRecord.forRitual(UUID.randomUUID(), p.id(), p.name(), "altar", "w:0:64:0",
                "default", 0L, SimPlayer.ITEM, new byte[]{1}, verdict);
        journal.create(record);
        p.saveFails = s.failCaptureSave();
        CustodyEngine.CaptureResult cr = CustodyEngine.capture(p, record, SimPlayer.ITEM, SLOT);
        assertEquals(CustodyEngine.CaptureResult.CAPTURED, cr);
        p.saveFails = s.failPayoutSave();
        CustodyEngine.PayoutResult pr = CustodyEngine.pay(p, record, SimPlayer.ITEM, Integer.MAX_VALUE);
        if (pr.status() == CustodyEngine.PayoutStatus.HELD) {
            p.clearJunk(); // the player makes room; the retry loop pays the rest
            pr = CustodyEngine.pay(p, record, SimPlayer.ITEM, Integer.MAX_VALUE);
        }
        assertEquals(CustodyEngine.PayoutStatus.SETTLED, pr.status());
        if (verdict.rewardAmount() == 0) {
            journal.delete(record.ritualId()); // nothing owed: the record can go right after the reveal
        }
        // Otherwise the record stays until a later join proves the payout reached disk.
    }

    /** What the plugin does when a player joins: reconcile against the ledger loaded from disk. */
    private void recover(SimPlayer p, Journal journal, boolean makeRoom) throws Exception {
        List<JournalRecord> mine = new ArrayList<>();
        for (JournalRecord r : journal.loadAll().records()) if (r.playerId().equals(p.id())) mine.add(r);
        PlayerLedger ledger = PlayerLedger.decode(p.readLedger());
        CustodyEngine.Plan plan = CustodyEngine.plan(mine, ledger, CustodyEngine.Mode.FRESH_LOAD, Set.of());
        assertTrue(plan.orphanEntries().isEmpty(), "unexpected orphan ledger entries " + plan.orphanEntries());
        for (CustodyEngine.Decision d : plan.decisions()) {
            switch (d.action()) {
                case DISCARD_NOT_CAPTURED, FINALIZE -> {
                    journal.delete(d.record().ritualId());
                    CustodyEngine.forget(p, d.record().ritualId());
                }
                case PAY -> {
                    if (makeRoom) p.clearJunk();
                    CustodyEngine.pay(p, d.record(), SimPlayer.ITEM, Integer.MAX_VALUE);
                    // Not deleted yet: proof comes from the next fresh load.
                }
                case AWAIT_VERIFICATION, FINALIZE_OVERPAID -> fail("unexpected " + d);
            }
        }
        for (UUID id : plan.forgettableEntries()) CustodyEngine.forget(p, id);
    }

    private static SimPlayer player(Scenario s) {
        SimPlayer p = new SimPlayer();
        p.setSlot(SLOT, SimPlayer.ITEM, s.stack());
        p.setSlot(5, SimPlayer.ITEM, 3); // unrelated items of the same type must be unaffected
        if (s.room() < 2000) p.leaveRoom(s.room());
        p.forceSave();
        return p;
    }

    private static Verdict verdict(Scenario s) {
        return OutcomeEngine.verdictFor(new OutcomeDefinition(s.outcome(), s.mult(), s.tier()), s.offer(), 10_000);
    }

    private static final class Counter {
        final int crashAt;
        int steps;

        Counter(int crashAt) {
            this.crashAt = crashAt;
        }

        void tick() {
            steps++;
            if (steps == crashAt) throw new SimPlayer.Crash();
        }
    }

    /** Wraps the real file journal with crash points before and after every durable operation. */
    private record CountingJournal(Journal delegate, Counter counter) implements Journal {
        @Override
        public void create(JournalRecord record) throws JournalException {
            counter.tick();
            delegate.create(record);
            counter.tick();
        }

        @Override
        public void replace(JournalRecord record) throws JournalException {
            counter.tick();
            delegate.replace(record);
            counter.tick();
        }

        @Override
        public boolean delete(UUID ritualId) throws JournalException {
            counter.tick();
            boolean r = delegate.delete(ritualId);
            counter.tick();
            return r;
        }

        @Override
        public LoadResult loadAll() throws JournalException {
            return delegate.loadAll();
        }

        @Override
        public void probe() throws JournalException {
            delegate.probe();
        }
    }
}
