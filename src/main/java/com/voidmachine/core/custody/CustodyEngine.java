package com.voidmachine.core.custody;

import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.ledger.PlayerLedger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The item-safety protocol. Every rule that decides whether an item is taken, owed or paid lives here.
 *
 * <h2>Invariants</h2>
 * <ol>
 *   <li>An offering is removed from an inventory only after its journal record is durable.</li>
 *   <li>Removal and the ledger capture mark are applied in the same tick and saved in the same
 *       player-file write, so the saved file either has both or neither.</li>
 *   <li>Every payout and the matching ledger increment are applied in the same tick and saved in the
 *       same write.</li>
 *   <li>A record with a positive reward is deleted only when a ledger <em>loaded from disk</em> proves the
 *       full reward was paid ({@link Mode#FRESH_LOAD}). An in-memory ledger is never proof, because
 *       player saves can fail silently.</li>
 * </ol>
 * Together these make recovery idempotent: running it any number of times, after a crash at any
 * point, yields exactly one of "player kept the item" or "player received the verdict".
 *
 * <p>Stateless; all methods run on the server main thread.</p>
 */
public final class CustodyEngine {

    private CustodyEngine() {
    }

    // ------------------------------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------------------------------

    public enum CaptureResult {
        /** Item removed and capture mark written. The ritual is live. */
        CAPTURED,
        /** The slot no longer holds the offering; nothing was taken. */
        SLOT_CHANGED,
        /** The player's ledger is unreadable; nothing was taken. */
        LEDGER_CORRUPT,
        /** Defensive: a capture mark for this ritual already exists; nothing was taken. */
        ALREADY_CAPTURED
    }

    /**
     * Takes the offering. <b>Precondition:</b> {@code record} is already durable in the journal.
     */
    public static <T> CaptureResult capture(CustodyPlayer<T> player, JournalRecord record, T template, int slot) {
        PlayerLedger ledger;
        try {
            ledger = PlayerLedger.decode(player.readLedger());
        } catch (PlayerLedger.LedgerFormatException e) {
            return CaptureResult.LEDGER_CORRUPT;
        }
        if (ledger.contains(record.ritualId())) return CaptureResult.ALREADY_CAPTURED;
        if (!player.takeFromSlot(slot, template, record.inputAmount())) return CaptureResult.SLOT_CHANGED;
        ledger.markCaptured(record.ritualId(), record.rewardAmount());
        player.writeLedger(ledger.encode());
        player.save();
        return CaptureResult.CAPTURED;
    }

    // ------------------------------------------------------------------------------------------
    // Payout
    // ------------------------------------------------------------------------------------------

    public enum PayoutStatus {
        /** Everything owed has now been paid (possibly by this call). */
        SETTLED,
        /** Some or nothing was paid; the rest waits for inventory space. */
        HELD,
        /** Ritual record requires a capture mark but the ledger has none — nothing is owed. */
        NOT_CAPTURED,
        /** Ledger unreadable; nothing was paid. */
        LEDGER_CORRUPT
    }

    public record PayoutResult(PayoutStatus status, int given, int paidTotal, int remaining) {
    }

    /**
     * Pays as much of the remaining reward as fits (and at most {@code maxNow}).
     */
    public static <T> PayoutResult pay(CustodyPlayer<T> player, JournalRecord record, T template, int maxNow) {
        PlayerLedger ledger;
        try {
            ledger = PlayerLedger.decode(player.readLedger());
        } catch (PlayerLedger.LedgerFormatException e) {
            return new PayoutResult(PayoutStatus.LEDGER_CORRUPT, 0, 0, record.rewardAmount());
        }
        PlayerLedger.Entry entry = ledger.get(record.ritualId());
        if (entry == null && record.requiresCaptureMark()) {
            return new PayoutResult(PayoutStatus.NOT_CAPTURED, 0, 0, 0);
        }
        int paid = entry == null ? 0 : entry.paid();
        int remaining = record.rewardAmount() - paid;
        if (remaining <= 0) {
            return new PayoutResult(PayoutStatus.SETTLED, 0, paid, 0);
        }
        int room = Math.max(0, player.capacityFor(template));
        int toGive = Math.min(remaining, Math.min(room, Math.max(0, maxNow)));
        int given = 0;
        if (toGive > 0) {
            given = Math.max(0, Math.min(toGive, player.give(template, toGive)));
        }
        if (given > 0) {
            // Items and the paid counter change in the same tick and reach disk in the same save.
            ledger.recordPaid(record.ritualId(), paid + given, record.rewardAmount());
            player.writeLedger(ledger.encode());
            player.save();
        }
        int left = remaining - given;
        return new PayoutResult(left == 0 ? PayoutStatus.SETTLED : PayoutStatus.HELD, given, paid + given, left);
    }

    /** Removes a ledger entry after its record has been deleted. Persisted with the next save. */
    public static <T> boolean forget(CustodyPlayer<T> player, UUID ritualId) {
        try {
            PlayerLedger ledger = PlayerLedger.decode(player.readLedger());
            if (!ledger.contains(ritualId)) return false;
            ledger.remove(ritualId);
            player.writeLedger(ledger.encode());
            return true;
        } catch (PlayerLedger.LedgerFormatException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reconciliation
    // ------------------------------------------------------------------------------------------

    /** Where the ledger used for a decision came from. */
    public enum Mode {
        /** Read from the player's data file during join: it proves what was durably saved. */
        FRESH_LOAD,
        /** The live in-memory ledger (plugin reload, in-session): not proof of durability. */
        LIVE
    }

    public enum Action {
        /** The capture never reached the saved player file; the player still has the item. Delete the record. */
        DISCARD_NOT_CAPTURED,
        /** Items are still owed: pay {@code remaining}. */
        PAY,
        /** Fully paid and proven by a loaded ledger: delete the record, then forget the ledger entry. */
        FINALIZE,
        /** Fully paid according to the live ledger only: keep the record until a fresh load proves it. */
        AWAIT_VERIFICATION,
        /** Ledger says more was paid than owed (should be impossible): finalize, alert admins. */
        FINALIZE_OVERPAID
    }

    public record Decision(JournalRecord record, Action action, int paid, int remaining) {
    }

    /** Pure decision for one record. */
    public static Decision decide(JournalRecord record, PlayerLedger.Entry entry, Mode mode) {
        if (entry == null) {
            if (record.requiresCaptureMark()) {
                return new Decision(record, Action.DISCARD_NOT_CAPTURED, 0, 0);
            }
            if (record.rewardAmount() == 0) {
                return new Decision(record, Action.FINALIZE, 0, 0);
            }
            return new Decision(record, Action.PAY, 0, record.rewardAmount());
        }
        int remaining = record.rewardAmount() - entry.paid();
        if (remaining > 0) return new Decision(record, Action.PAY, entry.paid(), remaining);
        if (record.rewardAmount() == 0) {
            // Nothing was ever owed: no durability question to answer.
            return new Decision(record, Action.FINALIZE, entry.paid(), 0);
        }
        if (mode == Mode.LIVE) return new Decision(record, Action.AWAIT_VERIFICATION, entry.paid(), 0);
        return new Decision(record, remaining == 0 ? Action.FINALIZE : Action.FINALIZE_OVERPAID, entry.paid(), 0);
    }

    /**
     * Full reconciliation plan for one player.
     *
     * @param records     every journal record of this player
     * @param ledger      the player's ledger
     * @param mode        where the ledger came from
     * @param activeIds   rituals currently owned by a running ritual in memory; never touched here
     */
    public static Plan plan(Collection<JournalRecord> records, PlayerLedger ledger, Mode mode, Set<UUID> activeIds) {
        List<Decision> decisions = new ArrayList<>();
        Map<UUID, PlayerLedger.Entry> unmatched = new HashMap<>(ledger.asMap());
        for (JournalRecord record : records) {
            unmatched.remove(record.ritualId());
            if (activeIds.contains(record.ritualId())) continue;
            decisions.add(decide(record, ledger.get(record.ritualId()), mode));
        }
        List<UUID> forgettable = new ArrayList<>();
        List<PlayerLedger.Entry> orphans = new ArrayList<>();
        for (PlayerLedger.Entry e : unmatched.values()) {
            if (activeIds.contains(e.ritualId())) continue;
            if (e.settled()) {
                // Record already deleted after a verified payout; the entry is just leftover bookkeeping.
                forgettable.add(e.ritualId());
            } else {
                // Owed items without a journal record: the record was lost or deleted externally.
                // Keep the entry as evidence and alert; never pretend it is settled.
                orphans.add(e);
            }
        }
        return new Plan(List.copyOf(decisions), List.copyOf(forgettable), List.copyOf(orphans));
    }

    public record Plan(List<Decision> decisions, List<UUID> forgettableEntries, List<PlayerLedger.Entry> orphanEntries) {
        public boolean isEmpty() {
            return decisions.isEmpty() && forgettableEntries.isEmpty() && orphanEntries.isEmpty();
        }
    }
}
