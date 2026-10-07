package com.voidmachine.core.journal;

import com.voidmachine.core.outcome.Multiplier;
import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.outcome.Verdict;

import java.util.Objects;
import java.util.UUID;

/**
 * One durable obligation of the Void towards a player. Written (fsync) <em>before</em> the offering
 * is taken; never modified afterwards except by an explicit admin action (which bumps
 * {@link #revision()}). How much of {@link #rewardAmount()} has been paid is <em>not</em> stored
 * here — it lives in the player's ledger, saved atomically with their inventory.
 *
 * @param itemTemplate {@code ItemStack#serializeAsBytes()} of a single item ({@code asOne()}), so it
 *                     is valid for any reward size and migrates with Minecraft data versions
 */
public record JournalRecord(
        UUID ritualId,
        int revision,
        RecordKind kind,
        UUID playerId,
        String playerName,
        String machineId,
        String machineLocation,
        String profileId,
        long createdAtMillis,
        String itemKey,
        byte[] itemTemplate,
        int inputAmount,
        String outcomeId,
        OutcomeTier tier,
        Multiplier multiplier,
        int rewardAmount,
        String note
) {

    public JournalRecord {
        Objects.requireNonNull(ritualId, "ritualId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(machineId, "machineId");
        Objects.requireNonNull(machineLocation, "machineLocation");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(itemKey, "itemKey");
        Objects.requireNonNull(itemTemplate, "itemTemplate");
        Objects.requireNonNull(outcomeId, "outcomeId");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(multiplier, "multiplier");
        note = note == null ? "" : note;
        itemTemplate = itemTemplate.clone();
        if (revision < 1) throw new IllegalArgumentException("revision must be >= 1");
        if (itemTemplate.length == 0) throw new IllegalArgumentException("itemTemplate is empty");
        if (inputAmount < 0) throw new IllegalArgumentException("inputAmount must be >= 0");
        if (rewardAmount < 0) throw new IllegalArgumentException("rewardAmount must be >= 0");
        if (kind == RecordKind.RITUAL && inputAmount < 1) {
            throw new IllegalArgumentException("a ritual must offer at least one item");
        }
    }

    /** Builds the record for a freshly sealed ritual. */
    public static JournalRecord forRitual(UUID ritualId, UUID playerId, String playerName,
                                          String machineId, String machineLocation, String profileId,
                                          long now, String itemKey, byte[] itemTemplate, Verdict verdict) {
        return new JournalRecord(ritualId, 1, RecordKind.RITUAL, playerId, playerName,
                machineId, machineLocation, profileId, now, itemKey, itemTemplate.clone(),
                verdict.inputAmount(), verdict.outcomeId(), verdict.tier(), verdict.multiplier(),
                verdict.rewardAmount(), verdict.capped() ? "capped" : "");
    }

    /** Builds an unconditional claim (no capture mark required). */
    public static JournalRecord claim(UUID id, UUID playerId, String playerName, long now,
                                      String itemKey, byte[] itemTemplate, int amount, String note) {
        return new JournalRecord(id, 1, RecordKind.CLAIM, playerId, playerName,
                "-", "-", "-", now, itemKey, itemTemplate.clone(), 0, "claim",
                OutcomeTier.NEUTRAL, Multiplier.ONE, amount, note);
    }

    public boolean requiresCaptureMark() {
        return kind.requiresCaptureMark();
    }

    /**
     * Admin refund: the Void returns the offering instead of its verdict. Only meaningful when the
     * verdict paid less than the offering.
     */
    public JournalRecord asRefund(String byWhom) {
        if (rewardAmount >= inputAmount) {
            throw new IllegalStateException("verdict already returns at least the offering");
        }
        return new JournalRecord(ritualId, revision + 1, kind, playerId, playerName, machineId,
                machineLocation, profileId, createdAtMillis, itemKey, itemTemplate, inputAmount,
                "refund", OutcomeTier.NEUTRAL, Multiplier.ONE, inputAmount,
                (note.isEmpty() ? "" : note + ";") + "refunded-by=" + byWhom + ";original=" + outcomeId);
    }

    /** Defensive copy; records are shared across threads. */
    @Override
    public byte[] itemTemplate() {
        return itemTemplate.clone();
    }

    /** Short id for admin output (first 8 hex chars). */
    public String shortId() {
        return ritualId.toString().substring(0, 8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JournalRecord other)) return false;
        return ritualId.equals(other.ritualId) && revision == other.revision;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ritualId, revision);
    }

    @Override
    public String toString() {
        return "JournalRecord{" + shortId() + " rev" + revision + " " + kind + " player=" + playerName
                + " item=" + itemKey + " in=" + inputAmount + " outcome=" + outcomeId + " reward=" + rewardAmount + '}';
    }
}
