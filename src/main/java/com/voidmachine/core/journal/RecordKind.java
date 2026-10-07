package com.voidmachine.core.journal;

/** What a journal record represents. */
public enum RecordKind {
    /**
     * A ritual. The offering is only considered taken if the player's ledger carries a capture mark
     * for this record; without it the capture never reached the player's saved data and the record
     * is discarded on reconciliation (the player still has the item).
     */
    RITUAL,
    /**
     * Items owed unconditionally (migrated V1 deliveries, admin refunds of V1 checkpoints). No
     * capture mark is required: absence of a ledger entry simply means nothing was paid yet.
     */
    CLAIM,
    /**
     * Ambiguous legacy state (a V1 checkpoint that may already have been partially delivered). Never
     * paid automatically: an admin decides with {@code /vm admin refund} or {@code /vm admin release}.
     */
    REVIEW;

    public boolean requiresCaptureMark() {
        return this == RITUAL;
    }

    public boolean automatic() {
        return this != REVIEW;
    }
}
