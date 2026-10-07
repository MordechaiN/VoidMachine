package com.voidmachine.core.custody;

import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.ledger.PlayerLedger;
import com.voidmachine.core.outcome.Multiplier;
import com.voidmachine.core.outcome.OutcomeDefinition;
import com.voidmachine.core.outcome.OutcomeEngine;
import com.voidmachine.core.outcome.OutcomeTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustodyEngineTest {

    static JournalRecord ritual(UUID player, int offer, Multiplier m, OutcomeTier tier) {
        var def = new OutcomeDefinition("o", m, tier);
        return JournalRecord.forRitual(UUID.randomUUID(), player, "P", "altar", "w:0:0:0", "default", 0,
                SimPlayer.ITEM, new byte[]{1}, OutcomeEngine.verdictFor(def, offer, 10_000));
    }

    @Test
    void captureRefusesChangedSlotAndTakesNothing() {
        SimPlayer p = new SimPlayer().setSlot(0, SimPlayer.ITEM, 10);
        JournalRecord r = ritual(p.id(), 20, Multiplier.ONE, OutcomeTier.NEUTRAL);
        assertEquals(CustodyEngine.CaptureResult.SLOT_CHANGED, CustodyEngine.capture(p, r, SimPlayer.ITEM, 0));
        assertEquals(10, p.memItems());
        assertEquals(null, p.readLedger());
        JournalRecord other = ritual(p.id(), 5, Multiplier.ONE, OutcomeTier.NEUTRAL);
        assertEquals(CustodyEngine.CaptureResult.SLOT_CHANGED, CustodyEngine.capture(p, other, "emerald", 0));
    }

    @Test
    void captureRefusesCorruptLedgerAndTakesNothing() {
        SimPlayer p = new SimPlayer().setSlot(0, SimPlayer.ITEM, 10);
        p.writeLedger("garbage");
        JournalRecord r = ritual(p.id(), 5, Multiplier.ONE, OutcomeTier.NEUTRAL);
        assertEquals(CustodyEngine.CaptureResult.LEDGER_CORRUPT, CustodyEngine.capture(p, r, SimPlayer.ITEM, 0));
        assertEquals(10, p.memItems());
        CustodyEngine.PayoutResult pr = CustodyEngine.pay(p, r, SimPlayer.ITEM, 99);
        assertEquals(CustodyEngine.PayoutStatus.LEDGER_CORRUPT, pr.status());
    }

    @Test
    void partialPayoutsAccumulateExactly() throws Exception {
        SimPlayer p = new SimPlayer().setSlot(0, SimPlayer.ITEM, 64);
        JournalRecord r = ritual(p.id(), 64, new Multiplier(5, 1), OutcomeTier.JACKPOT);
        assertEquals(CustodyEngine.CaptureResult.CAPTURED, CustodyEngine.capture(p, r, SimPlayer.ITEM, 0));
        p.leaveRoom(100);
        CustodyEngine.PayoutResult first = CustodyEngine.pay(p, r, SimPlayer.ITEM, Integer.MAX_VALUE);
        assertEquals(CustodyEngine.PayoutStatus.HELD, first.status());
        assertEquals(100, first.given());
        assertEquals(220, first.remaining());
        CustodyEngine.PayoutResult limited = CustodyEngine.pay(p, r, SimPlayer.ITEM, 7);
        assertEquals(0, limited.given(), "no room yet");
        p.clearJunk();
        CustodyEngine.PayoutResult capped = CustodyEngine.pay(p, r, SimPlayer.ITEM, 20);
        assertEquals(20, capped.given());
        CustodyEngine.PayoutResult rest = CustodyEngine.pay(p, r, SimPlayer.ITEM, Integer.MAX_VALUE);
        assertEquals(CustodyEngine.PayoutStatus.SETTLED, rest.status());
        assertEquals(320, rest.paidTotal());
        assertEquals(new PlayerLedger.Entry(r.ritualId(), 320, 320), PlayerLedger.decode(p.readLedger()).get(r.ritualId()));
        // Paying again is a no-op.
        assertEquals(0, CustodyEngine.pay(p, r, SimPlayer.ITEM, Integer.MAX_VALUE).given());
    }

    @Test
    void claimsPayWithoutCaptureMark() {
        SimPlayer p = new SimPlayer();
        JournalRecord claim = JournalRecord.claim(UUID.randomUUID(), p.id(), "P", 0, SimPlayer.ITEM, new byte[]{1}, 70, "v1-migration");
        CustodyEngine.PayoutResult res = CustodyEngine.pay(p, claim, SimPlayer.ITEM, Integer.MAX_VALUE);
        assertEquals(70, res.given());
        assertEquals(CustodyEngine.PayoutStatus.SETTLED, res.status());
    }

    @Test
    void ritualWithoutCaptureMarkIsNeverPaid() {
        SimPlayer p = new SimPlayer();
        JournalRecord r = ritual(p.id(), 10, new Multiplier(2, 1), OutcomeTier.WIN);
        assertEquals(CustodyEngine.PayoutStatus.NOT_CAPTURED, CustodyEngine.pay(p, r, SimPlayer.ITEM, 99).status());
        assertEquals(0, p.memItems());
    }

    @Test
    void planSkipsActiveRitualsAndClassifiesLedgerLeftovers() {
        UUID player = UUID.randomUUID();
        JournalRecord active = ritual(player, 5, Multiplier.ONE, OutcomeTier.NEUTRAL);
        JournalRecord notCaptured = ritual(player, 5, Multiplier.ONE, OutcomeTier.NEUTRAL);
        PlayerLedger ledger = PlayerLedger.empty();
        UUID settledOrphan = UUID.randomUUID();
        UUID owedOrphan = UUID.randomUUID();
        ledger.recordPaid(settledOrphan, 10, 10);
        ledger.recordPaid(owedOrphan, 3, 10);
        ledger.markCaptured(active.ritualId(), 5);

        CustodyEngine.Plan plan = CustodyEngine.plan(List.of(active, notCaptured), ledger,
                CustodyEngine.Mode.FRESH_LOAD, Set.of(active.ritualId()));
        assertEquals(1, plan.decisions().size());
        assertEquals(CustodyEngine.Action.DISCARD_NOT_CAPTURED, plan.decisions().getFirst().action());
        assertEquals(List.of(settledOrphan), plan.forgettableEntries());
        assertEquals(1, plan.orphanEntries().size());
        assertEquals(owedOrphan, plan.orphanEntries().getFirst().ritualId());
    }

    @Test
    void decisionTable() {
        UUID player = UUID.randomUUID();
        JournalRecord win = ritual(player, 10, new Multiplier(2, 1), OutcomeTier.WIN);
        JournalRecord loss = ritual(player, 10, Multiplier.ZERO, OutcomeTier.LOSS);
        var live = CustodyEngine.Mode.LIVE;
        var fresh = CustodyEngine.Mode.FRESH_LOAD;
        assertEquals(CustodyEngine.Action.DISCARD_NOT_CAPTURED, CustodyEngine.decide(win, null, fresh).action());
        assertEquals(CustodyEngine.Action.PAY, CustodyEngine.decide(win, new PlayerLedger.Entry(win.ritualId(), 0, 20), fresh).action());
        assertEquals(15, CustodyEngine.decide(win, new PlayerLedger.Entry(win.ritualId(), 5, 20), live).remaining());
        assertEquals(CustodyEngine.Action.AWAIT_VERIFICATION, CustodyEngine.decide(win, new PlayerLedger.Entry(win.ritualId(), 20, 20), live).action());
        assertEquals(CustodyEngine.Action.FINALIZE, CustodyEngine.decide(win, new PlayerLedger.Entry(win.ritualId(), 20, 20), fresh).action());
        assertEquals(CustodyEngine.Action.FINALIZE_OVERPAID, CustodyEngine.decide(win, new PlayerLedger.Entry(win.ritualId(), 25, 20), fresh).action());
        assertEquals(CustodyEngine.Action.FINALIZE, CustodyEngine.decide(loss, new PlayerLedger.Entry(loss.ritualId(), 0, 0), live).action());
        assertTrue(CustodyEngine.forget(new SimPlayer(), UUID.randomUUID()) == false);
    }
}
