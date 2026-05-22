/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.core;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Atomic unit of a single sacrifice event. Owns the captured item from the
 * moment it leaves the player's hand until delivery is confirmed.
 *
 * <h3>Physical-machine lifecycle (Phase 2+)</h3>
 * <pre>
 *   CAPTURED → ANIMATING → DELIVERING → COMPLETED
 *                                   ↘
 *                              FAILED (from any state)
 * </pre>
 *
 * <ul>
 *   <li>{@link State#CAPTURED}   — item removed from player hand; checkpoint written.</li>
 *   <li>{@link State#ANIMATING}  — animation pipeline running.</li>
 *   <li>{@link State#DELIVERING} — outcome resolved; delivery has begun.
 *       Checkpoint updated to DELIVERING before first item is added to inventory.</li>
 *   <li>{@link State#COMPLETED}  — delivery confirmed; checkpoint deleted.</li>
 *   <li>{@link State#FAILED}     — aborted at any point; item returned or queued.</li>
 * </ul>
 *
 * <h3>Delivery tracking</h3>
 * The transition to {@link State#DELIVERING} assigns a {@link #deliveryId()} and
 * records {@link #deliveryStartedEpoch()}. These allow admin forensics to
 * distinguish "delivery started but server crashed" from "delivery never attempted."
 *
 * <h3>Thread safety</h3>
 * This class is main-thread-only. Do not share instances across threads, and do
 * not pass the sacrifice {@link ItemStack} to async code.
 *
 * <h3>Legacy GUI compatibility</h3>
 * States {@link State#PENDING} and {@link State#PROCESSING} are preserved for the
 * original GUI system ({@code ProcessingService}, {@code GuiManager}). They will be
 * removed when those classes are rewritten in Phase 5. All new code must use the
 * physical-machine lifecycle above.
 */
public final class Transaction {

    // =========================================================================
    //  State
    // =========================================================================

    public enum State {
        // -----------------------------------------------------------------
        // Physical-machine states (Phase 2+)
        // -----------------------------------------------------------------
        /** Item removed from hand; checkpoint fsync'd to disk. */
        CAPTURED,
        /** Animation pipeline running; item locked in this object. */
        ANIMATING,
        /** Outcome resolved; delivery started; checkpoint is in DELIVERING state. */
        DELIVERING,
        /** Delivery confirmed; checkpoint deleted; machine unlocked. */
        COMPLETED,
        /** Aborted at any point; item returned or queued for offline delivery. */
        FAILED,

        // -----------------------------------------------------------------
        // Legacy GUI states — deprecated, used only by old ProcessingService
        // and GuiManager. Remove in Phase 5.
        // -----------------------------------------------------------------
        /** @deprecated GUI artifact — item not yet captured. Use CAPTURED as initial state. */
        @Deprecated PENDING,
        /** @deprecated GUI artifact — use ANIMATING. */
        @Deprecated PROCESSING
    }

    // =========================================================================
    //  Identity
    // =========================================================================

    /** Unique ID for this transaction. Stable for its entire lifetime. */
    private final UUID id;
    private final UUID playerId;
    private final String playerName;

    /**
     * Location key of the machine that accepted this sacrifice
     * ({@code "worldName:x:y:z"}). {@code "legacy-gui"} for GUI-era transactions.
     */
    private final String machineLoc;

    /** Config profile name of the machine (e.g. "default", "brutal"). */
    private final String configProfile;

    /** Epoch millis at which the item was captured. Embedded in checkpoint filename. */
    private final long capturedAtEpoch;

    // =========================================================================
    //  Item
    // =========================================================================

    /**
     * Defensive clone of the sacrificed item. Immutable after construction.
     * Null only for legacy GUI transactions that haven't called {@link #capture} yet.
     */
    @Nullable
    private ItemStack sacrifice;

    // =========================================================================
    //  Outcome
    // =========================================================================

    @Nullable
    private Outcome outcome;

    /** Resolved output stack size after outcome multiplier. 0 for DESTROYED. */
    private int outputAmount;

    // =========================================================================
    //  Delivery tracking
    // =========================================================================

    /**
     * Unique ID assigned at {@link #markDelivering} time. Allows forensic
     * deduplication: if two recovery passes both attempt delivery, the deliveryId
     * lets an admin confirm whether the item was granted twice.
     */
    @Nullable
    private UUID deliveryId;

    /** {@link System#currentTimeMillis()} at the moment delivery was initiated. */
    private long deliveryStartedEpoch;

    /** {@code true} once delivery has been fully confirmed ({@link #markCompleted()}). */
    private boolean deliveryCompleted;

    /** Timestamp of the final terminal state (COMPLETED or FAILED). */
    @Nullable
    private Instant finalizationTimestamp;

    // =========================================================================
    //  State
    // =========================================================================

    private State state;

    // =========================================================================
    //  Constructors
    // =========================================================================

    /**
     * Physical-machine constructor. Use this for all new code.
     *
     * <p>The item <strong>must already have been removed</strong> from the player's
     * hand before this constructor is called. The checkpoint must have been written
     * and fsync'd before the item was removed.</p>
     *
     * @param playerId      UUID of the sacrificing player
     * @param playerName    display name for logging
     * @param sacrifice     the captured item (will be defensively cloned)
     * @param machineLoc    location key of the machine ("worldName:x:y:z")
     * @param configProfile config profile name of the machine
     */
    public Transaction(@NotNull UUID playerId,
                       @NotNull String playerName,
                       @NotNull ItemStack sacrifice,
                       @NotNull String machineLoc,
                       @NotNull String configProfile) {
        this.id            = UUID.randomUUID();
        this.playerId      = Objects.requireNonNull(playerId, "playerId");
        this.playerName    = Objects.requireNonNull(playerName, "playerName");
        this.sacrifice     = Objects.requireNonNull(sacrifice, "sacrifice").clone();
        this.machineLoc    = Objects.requireNonNull(machineLoc, "machineLoc");
        this.configProfile = Objects.requireNonNull(configProfile, "configProfile");
        this.capturedAtEpoch = System.currentTimeMillis();
        this.state         = State.CAPTURED;
    }

    /**
     * Legacy GUI constructor. Do not use in new code.
     *
     * @deprecated Physical machine uses {@link #Transaction(UUID, String, ItemStack, String, String)}.
     */
    @Deprecated
    public Transaction(@NotNull Player player) {
        this.id            = UUID.randomUUID();
        this.playerId      = player.getUniqueId();
        this.playerName    = player.getName();
        this.sacrifice     = null; // set later by capture()
        this.machineLoc    = "legacy-gui";
        this.configProfile = "default";
        this.capturedAtEpoch = System.currentTimeMillis();
        this.state         = State.PENDING;
    }

    // =========================================================================
    //  State transitions — physical-machine lifecycle
    // =========================================================================

    /**
     * Transition from {@link State#CAPTURED} to {@link State#ANIMATING}.
     * Call after the animation pipeline has been successfully started.
     */
    public void markAnimating() {
        requireState(State.CAPTURED, "markAnimating");
        this.state = State.ANIMATING;
    }

    /**
     * Transition from {@link State#ANIMATING} to {@link State#DELIVERING}.
     *
     * <p>The caller <strong>must</strong> have already:</p>
     * <ol>
     *   <li>Rolled the outcome.</li>
     *   <li>Called {@link com.voidmachine.checkpoint.CheckpointStore#markDelivering(UUID)}
     *       and confirmed it returned {@code true}.</li>
     * </ol>
     *
     * <p>If the checkpoint write failed, the caller must call {@link #markFailed()}
     * instead and return the item.</p>
     *
     * @param outcome      the resolved outcome
     * @param outputAmount resolved number of items to return (0 for DESTROYED)
     */
    public void markDelivering(@NotNull Outcome outcome, int outputAmount) {
        requireState(State.ANIMATING, "markDelivering");
        this.outcome               = Objects.requireNonNull(outcome, "outcome");
        this.outputAmount          = outputAmount;
        this.deliveryId            = UUID.randomUUID();
        this.deliveryStartedEpoch  = System.currentTimeMillis();
        this.state                 = State.DELIVERING;
    }

    /**
     * Transition from {@link State#DELIVERING} to {@link State#COMPLETED}.
     * Call only after all items have been added to the player's inventory and
     * the checkpoint has been deleted (or scheduled for deletion).
     */
    public void markCompleted() {
        requireState(State.DELIVERING, "markCompleted");
        this.deliveryCompleted      = true;
        this.finalizationTimestamp  = Instant.now();
        this.state                  = State.COMPLETED;
    }

    /**
     * Transition to {@link State#FAILED} from any non-terminal state.
     * Idempotent: safe to call even if already FAILED or COMPLETED (no-op in that case).
     */
    public void markFailed() {
        if (state == State.COMPLETED || state == State.FAILED) return;
        this.finalizationTimestamp = Instant.now();
        this.state = State.FAILED;
    }

    // =========================================================================
    //  Legacy state transitions (deprecated — GUI system only)
    // =========================================================================

    /**
     * @deprecated GUI lifecycle. Use the physical-machine constructor which starts
     *             at {@link State#CAPTURED} directly.
     */
    @Deprecated
    public void capture(@NotNull ItemStack stack) {
        if (state != State.PENDING) throw new IllegalStateException("Cannot capture from " + state);
        this.sacrifice = Objects.requireNonNull(stack, "stack").clone();
        this.state = State.CAPTURED;
    }

    /**
     * @deprecated GUI lifecycle. Use {@link #markAnimating()}.
     */
    @Deprecated
    public void markProcessing() {
        if (state == State.CAPTURED) { this.state = State.ANIMATING; return; }
        throw new IllegalStateException("markProcessing requires CAPTURED, got " + state);
    }

    /**
     * @deprecated GUI lifecycle. Use {@link #markDelivering(Outcome, int)} + {@link #markCompleted()}.
     */
    @Deprecated
    public void markCompleted(@NotNull Outcome outcome, int outputAmount) {
        if (state == State.PROCESSING || state == State.ANIMATING || state == State.DELIVERING) {
            this.outcome               = outcome;
            this.outputAmount          = outputAmount;
            this.deliveryCompleted     = true;
            this.finalizationTimestamp = Instant.now();
            this.state                 = State.COMPLETED;
            return;
        }
        throw new IllegalStateException("Cannot complete from " + state);
    }

    // =========================================================================
    //  Utility
    // =========================================================================

    /**
     * Returns a clone of the sacrifice with the given stack size.
     * Used when computing refund or reward stacks.
     *
     * @param amount desired stack size (clamped to [0, Integer.MAX_VALUE])
     */
    @NotNull
    public ItemStack copyOfSacrificeWithAmount(int amount) {
        if (sacrifice == null) throw new IllegalStateException("Sacrifice not yet captured");
        ItemStack copy = sacrifice.clone();
        copy.setAmount(Math.max(0, amount));
        return copy;
    }

    // =========================================================================
    //  Accessors
    // =========================================================================

    @NotNull public UUID id()             { return id; }
    @NotNull public UUID playerId()       { return playerId; }
    @NotNull public String playerName()   { return playerName; }
    @NotNull public String machineLoc()   { return machineLoc; }
    @NotNull public String configProfile(){ return configProfile; }
    public long capturedAtEpoch()         { return capturedAtEpoch; }
    @NotNull public State state()         { return state; }

    @Nullable public ItemStack sacrifice()          { return sacrifice; }
    @Nullable public Outcome outcome()              { return outcome; }
    public int outputAmount()                       { return outputAmount; }

    @Nullable public UUID deliveryId()              { return deliveryId; }
    public long deliveryStartedEpoch()              { return deliveryStartedEpoch; }
    public boolean deliveryCompleted()              { return deliveryCompleted; }
    @Nullable public Instant finalizationTimestamp(){ return finalizationTimestamp; }

    /** Convenience: amount of the sacrificed stack (0 if not yet captured). */
    public int inputAmount() {
        return sacrifice == null ? 0 : sacrifice.getAmount();
    }

    // =========================================================================
    //  Internal helpers
    // =========================================================================

    private void requireState(@NotNull State required, @NotNull String method) {
        if (state != required) {
            throw new IllegalStateException(
                    method + " requires state " + required + ", got " + state
                            + " (tx=" + id + ", player=" + playerName + ')');
        }
    }

    @Override
    public String toString() {
        return "Transaction{id=" + id + ", player=" + playerName
                + ", state=" + state + ", machine=" + machineLoc + '}';
    }
}
