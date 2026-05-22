/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.interaction;

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.animation.AnimationWatchdog;
import com.voidmachine.audit.AuditLogger;
import com.voidmachine.checkpoint.CheckpointStore;
import com.voidmachine.checkpoint.PendingDeliveryQueue;
import com.voidmachine.config.PluginConfig;
import com.voidmachine.core.Outcome;
import com.voidmachine.core.OutcomeRoller;
import com.voidmachine.core.Transaction;
import com.voidmachine.machine.MachineBlock;
import com.voidmachine.machine.MachineRegistry;
import com.voidmachine.service.BlacklistService;
import com.voidmachine.transaction.TransactionRegistry;
import com.voidmachine.util.ItemValidator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Executes the atomic item-capture sequence and owns the abort path.
 *
 * <h3>Capture sequence</h3>
 * <ol>
 *   <li>Validate main-hand item (empty / blacklist check).</li>
 *   <li>{@link CheckpointStore#writeSync} — crash-safe WAL write with {@code fsync}.
 *       Must succeed <em>before</em> the item is removed from the player's hand.</li>
 *   <li>{@link MachineBlock#tryLock()} — CAS prevents concurrent transactions.</li>
 *   <li>Remove item from hand ({@link Material#AIR}, Geyser-safe).</li>
 *   <li>Create {@link Transaction}, register in {@link TransactionRegistry}.</li>
 *   <li>Arm {@link AnimationWatchdog}.</li>
 *   <li>Fire the {@link #setAfterCapture(Consumer) afterCapture} callback.</li>
 * </ol>
 *
 * <h3>After-capture hook</h3>
 * {@link #setAfterCapture(Consumer)} receives the live {@link Transaction} once the item is
 * safely captured. Defaults to {@link #immediateResolve} (no animation — roll outcome and
 * deliver instantly). Replaced by the animation pipeline in Phase 4.
 *
 * <h3>Abort</h3>
 * {@link #abortTransaction(UUID, String)} is the single entry point for all abort paths:
 * watchdog timeout, block break, chunk unload, admin command. Safe to call from main thread only.
 *
 * <h3>Thread safety</h3>
 * All public methods must be called on the main thread.
 */
public final class ItemCaptureService {

    private final VoidMachinePlugin plugin;
    private final PluginConfig config;
    private final CheckpointStore checkpoints;
    private final TransactionRegistry txRegistry;
    private final AnimationWatchdog watchdog;
    private final BlacklistService blacklist;
    private final AuditLogger audit;
    private final PendingDeliveryQueue deliveryQueue;
    private final MachineRegistry machineRegistry;
    private final OutcomeRoller roller;
    private final Logger logger;

    /**
     * Called with the live Transaction after it reaches {@link Transaction.State#ANIMATING}.
     * Default: {@link #immediateResolve}. Phase 4 replaces this with the animation pipeline.
     */
    private volatile Consumer<Transaction> afterCapture;

    /**
     * Set by {@link #setAnimationPipeline} in Phase 4 bootstrap.
     * When non-null, {@link #abortTransaction} calls
     * {@link com.voidmachine.animation.AnimationPipeline#cancelForPlayer} before
     * cleaning up the transaction, so the animation's display entity and boss bar
     * are removed before the machine is unlocked.
     */
    @Nullable
    private volatile com.voidmachine.animation.AnimationPipeline animationPipeline;

    public ItemCaptureService(@NotNull VoidMachinePlugin plugin,
                              @NotNull PluginConfig config,
                              @NotNull CheckpointStore checkpoints,
                              @NotNull TransactionRegistry txRegistry,
                              @NotNull AnimationWatchdog watchdog,
                              @NotNull BlacklistService blacklist,
                              @NotNull AuditLogger audit,
                              @NotNull PendingDeliveryQueue deliveryQueue,
                              @NotNull MachineRegistry machineRegistry,
                              @NotNull OutcomeRoller roller) {
        this.plugin          = plugin;
        this.config          = config;
        this.checkpoints     = checkpoints;
        this.txRegistry      = txRegistry;
        this.watchdog        = watchdog;
        this.blacklist       = blacklist;
        this.audit           = audit;
        this.deliveryQueue   = deliveryQueue;
        this.machineRegistry = machineRegistry;
        this.roller          = roller;
        this.logger          = plugin.getLogger();
        // Default: no animation — roll and deliver immediately.
        this.afterCapture    = this::immediateResolve;
    }

    // =========================================================================
    //  Public API
    // =========================================================================

    /**
     * Replace the post-capture callback. Call from bootstrap when the animation
     * pipeline is wired up (Phase 4).
     *
     * @param callback receives the transaction in {@link Transaction.State#ANIMATING};
     *                 responsible for eventually calling {@link #completeTransaction}
     *                 or {@link #abortTransaction}
     */
    public void setAfterCapture(@NotNull Consumer<Transaction> callback) {
        this.afterCapture = callback;
    }

    /**
     * Register the animation pipeline so that {@link #abortTransaction} can cancel
     * any running animation before cleaning up the transaction.
     * Call from bootstrap after {@link com.voidmachine.animation.AnimationPipeline}
     * is constructed (Phase 4).
     */
    public void setAnimationPipeline(
            @NotNull com.voidmachine.animation.AnimationPipeline pipeline) {
        this.animationPipeline = pipeline;
    }

    /**
     * Attempt to capture the main-hand item from the player and begin a transaction.
     * Must be called on the <em>main thread</em>.
     *
     * <p>All failure paths send a message to the player and return cleanly.
     * If this method returns without error, a transaction is active and
     * {@link #afterCapture} has been invoked.</p>
     *
     * @param player  the player initiating the ritual
     * @param machine the machine the player right-clicked
     */
    public void capture(@NotNull Player player, @NotNull MachineBlock machine) {
        UUID uuid = player.getUniqueId();

        // ── 1. Validate main-hand item ────────────────────────────────────────

        ItemStack mainHand = player.getInventory().getItemInMainHand();

        if (ItemValidator.isEmpty(mainHand)) {
            player.sendMessage("§7[VoidMachine] §eHold an item in your main hand to sacrifice.");
            return;
        }

        String refusalReason = blacklist.validate(mainHand);
        if (refusalReason != null) {
            player.sendMessage("§7[VoidMachine] §cThat item cannot be sacrificed.");
            logger.info("[Capture] " + player.getName() + " blocked: " + refusalReason);
            return;
        }

        // Clamp input to max-insert-amount (take partial stack, leave remainder).
        int inputAmount = Math.min(mainHand.getAmount(), config.maxInsertAmount());
        ItemStack sacrifice = mainHand.clone();
        sacrifice.setAmount(inputAmount);

        long capturedAtEpoch = System.currentTimeMillis();

        // ── 2. Write checkpoint BEFORE item removal (crash-safe WAL) ─────────

        boolean checkpointOk = checkpoints.writeSync(
                uuid, player.getName(),
                machine.locationKey(), machine.configProfile(),
                sacrifice, capturedAtEpoch);

        if (!checkpointOk) {
            player.sendMessage("§7[VoidMachine] §cThe ritual could not be anchored. Please try again.");
            logger.warning("[Capture] Checkpoint write failed for " + player.getName()
                    + " — transaction aborted before item removal.");
            return;
        }

        // ── 3. Lock the machine (CAS) ─────────────────────────────────────────

        if (!machine.tryLock()) {
            // Checkpoint written but no item removed — safe to delete.
            checkpoints.deleteAsync(uuid);
            player.sendMessage("§7[VoidMachine] §eThe machine is occupied. Wait for the current ritual.");
            return;
        }

        // ═════════════════════════════════════════════════════════════════════
        //  POINT OF NO RETURN — item leaves player's hand after this line.
        // ═════════════════════════════════════════════════════════════════════

        // ── 4. Remove item from hand (Geyser-safe: AIR not null) ─────────────

        if (inputAmount < mainHand.getAmount()) {
            // Partial consumption — reduce the hand stack.
            ItemStack remaining = mainHand.clone();
            remaining.setAmount(mainHand.getAmount() - inputAmount);
            player.getInventory().setItemInMainHand(remaining);
        } else {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
        player.updateInventory(); // sync Bedrock client

        // ── 5. Create Transaction and register ────────────────────────────────

        Transaction tx = new Transaction(uuid, player.getName(), sacrifice,
                machine.locationKey(), machine.configProfile());

        if (!txRegistry.register(tx)) {
            // Should never happen — listener already checked. Defensive path.
            logger.severe("[Capture] TransactionRegistry collision for " + player.getName()
                    + " — returning item via pending queue.");
            machine.unlock();
            checkpoints.deleteAsync(uuid);
            deliveryQueue.add(uuid, player.getName(), sacrifice, "capture_registry_race");
            return;
        }

        // ── 6. Arm watchdog ────────────────────────────────────────────────────

        watchdog.arm(uuid, () -> abortTransaction(uuid, "watchdog_timeout"));

        // ── 7. Advance to ANIMATING ───────────────────────────────────────────

        checkpoints.markAnimating(uuid); // best-effort
        tx.markAnimating();

        // ── 8. Audit + fire callback ──────────────────────────────────────────

        audit.logCapture(uuid, player.getName(),
                machine.locationKey(),
                sacrifice.getType().key().asString(),
                sacrifice.getAmount());

        logger.info("[Capture] " + player.getName() + " sacrificed "
                + sacrifice.getAmount() + "×" + sacrifice.getType().key().asString()
                + " at " + machine.locationKey());

        afterCapture.accept(tx);
    }

    /**
     * Attempt to capture an item provided directly from a staging GUI slot and begin a
     * transaction. Must be called on the <em>main thread</em>.
     *
     * <p>Functionally identical to {@link #capture(Player, MachineBlock)} except the
     * item comes from the caller rather than the player's main hand. The
     * {@code onConsumed} runnable is invoked at the <em>point-of-no-return</em> — after
     * both the checkpoint write and the machine lock succeed — to clear the GUI slot and
     * close the staging inventory.</p>
     *
     * <p>If the checkpoint write <em>or</em> the machine lock fails, {@code onConsumed}
     * is <em>not</em> called; the staging GUI remains open so the player can retry or
     * press ESC.</p>
     *
     * @param player       the player initiating the ritual
     * @param machine      the machine the player chose
     * @param providedItem the item from the staging GUI slot (defensively cloned internally)
     * @param onConsumed   runnable called exactly once at point-of-no-return to close the GUI
     */
    public void captureFromGui(@NotNull Player player,
                               @NotNull MachineBlock machine,
                               @NotNull ItemStack providedItem,
                               @NotNull Runnable onConsumed) {
        UUID uuid = player.getUniqueId();

        // ── 1. Validate provided item ─────────────────────────────────────────

        if (ItemValidator.isEmpty(providedItem)) {
            player.sendMessage("§7[VoidMachine] §ePlace an item in the slot first.");
            return;
        }

        String refusalReason = blacklist.validate(providedItem);
        if (refusalReason != null) {
            player.sendMessage("§7[VoidMachine] §cThe Void refuses this item.");
            logger.info("[CaptureFromGui] " + player.getName() + " blocked: " + refusalReason);
            return;
        }

        int inputAmount = Math.min(providedItem.getAmount(), config.maxInsertAmount());
        ItemStack sacrifice = providedItem.clone();
        sacrifice.setAmount(inputAmount);

        long capturedAtEpoch = System.currentTimeMillis();

        // ── 2. Write checkpoint BEFORE consuming (crash-safe WAL) ────────────

        boolean checkpointOk = checkpoints.writeSync(
                uuid, player.getName(),
                machine.locationKey(), machine.configProfile(),
                sacrifice, capturedAtEpoch);

        if (!checkpointOk) {
            player.sendMessage("§7[VoidMachine] §cThe ritual could not be anchored. Try again.");
            logger.warning("[CaptureFromGui] Checkpoint write failed for " + player.getName()
                    + " — transaction aborted before item consumed.");
            return;
        }

        // ── 3. Lock the machine (CAS) ─────────────────────────────────────────

        if (!machine.tryLock()) {
            checkpoints.deleteAsync(uuid);
            player.sendMessage("§7[VoidMachine] §eThe machine is busy. Wait for the current ritual.");
            return;
        }

        // ═════════════════════════════════════════════════════════════════════
        //  POINT OF NO RETURN — GUI slot is consumed after this line.
        // ═════════════════════════════════════════════════════════════════════

        // ── 4. Consume from GUI (clears staging slot, closes staging inventory) ─

        onConsumed.run();

        // Return excess if maxInsertAmount capped the provided stack.
        if (inputAmount < providedItem.getAmount()) {
            ItemStack remainder = providedItem.clone();
            remainder.setAmount(providedItem.getAmount() - inputAmount);
            giveOrDrop(player, remainder);
            player.updateInventory();
        }

        // ── 5. Create Transaction and register ────────────────────────────────

        Transaction tx = new Transaction(uuid, player.getName(), sacrifice,
                machine.locationKey(), machine.configProfile());

        if (!txRegistry.register(tx)) {
            // Defensive — listener already gated on hasActive; should not occur.
            logger.severe("[CaptureFromGui] TransactionRegistry collision for "
                    + player.getName() + " — queuing refund.");
            machine.unlock();
            checkpoints.deleteAsync(uuid);
            deliveryQueue.add(uuid, player.getName(), sacrifice, "capture_gui_registry_race");
            return;
        }

        // ── 6. Arm watchdog ────────────────────────────────────────────────────

        watchdog.arm(uuid, () -> abortTransaction(uuid, "watchdog_timeout"));

        // ── 7. Advance to ANIMATING ───────────────────────────────────────────

        checkpoints.markAnimating(uuid); // best-effort
        tx.markAnimating();

        // ── 8. Audit + fire callback ──────────────────────────────────────────

        audit.logCapture(uuid, player.getName(),
                machine.locationKey(),
                sacrifice.getType().key().asString(),
                sacrifice.getAmount());

        logger.info("[CaptureFromGui] " + player.getName() + " sacrificed "
                + sacrifice.getAmount() + "×" + sacrifice.getType().key().asString()
                + " at " + machine.locationKey());

        afterCapture.accept(tx);
    }

    /**
     * Abort a transaction, return the item to the player (or queue for offline),
     * unlock the machine, and clean up all state.
     *
     * <p>Idempotent — safe to call even if the transaction has already been resolved.
     * Must be called on the <em>main thread</em>.</p>
     *
     * @param playerUuid UUID of the transaction owner
     * @param reason     short tag written to the audit log and pending-delivery reason
     */
    public void abortTransaction(@NotNull UUID playerUuid, @NotNull String reason) {
        Transaction tx = txRegistry.get(playerUuid);
        if (tx == null) return; // already resolved or never registered

        // Cancel any running animation first (despawns display entity, hides boss bar).
        com.voidmachine.animation.AnimationPipeline pipeline = animationPipeline;
        if (pipeline != null) pipeline.cancelForPlayer(playerUuid);

        // Mark FAILED (idempotent — safe regardless of current state).
        tx.markFailed();

        // Disarm watchdog.
        watchdog.disarm(playerUuid);

        // Deregister (identity-check CAS — guards against stale deregister racing a new tx).
        txRegistry.deregister(tx);

        // Unlock machine.
        MachineBlock machine = machineRegistry.atKey(tx.machineLoc());
        if (machine != null) machine.unlock();

        // Return item.
        ItemStack sacrifice = tx.sacrifice();
        if (sacrifice != null) {
            ItemStack refund = sacrifice.clone();
            Player player = plugin.getServer().getPlayer(playerUuid);
            if (player != null && player.isOnline()) {
                giveOrDrop(player, refund);
                player.updateInventory();
                player.sendMessage("§7[VoidMachine] §fThe machine returns what it took.");
                logger.info("[Abort] " + reason + " — returned "
                        + refund.getAmount() + "×" + refund.getType().key().asString()
                        + " to " + tx.playerName());
            } else {
                deliveryQueue.add(playerUuid, tx.playerName(), refund, reason);
                logger.info("[Abort] " + reason + " — queued refund for offline player "
                        + tx.playerName());
            }
        }

        // Audit.
        String itemType = sacrifice != null ? sacrifice.getType().key().asString() : "unknown";
        audit.logAborted(playerUuid, tx.playerName(),
                tx.machineLoc(), itemType, tx.inputAmount(), reason);

        // Delete checkpoint.
        checkpoints.deleteAsync(playerUuid);
    }

    /**
     * Complete a transaction after the outcome has been delivered.
     * Cleans up registry, watchdog, machine lock, and checkpoint.
     * Must be called on the main thread.
     *
     * <p>The caller is responsible for advancing {@code tx} to
     * {@link Transaction.State#DELIVERING} and then {@link Transaction.State#COMPLETED}
     * before calling this method.</p>
     */
    public void completeTransaction(@NotNull Transaction tx) {
        UUID uuid = tx.playerId();

        watchdog.disarm(uuid);
        txRegistry.deregister(tx);

        MachineBlock machine = machineRegistry.atKey(tx.machineLoc());
        if (machine != null) machine.unlock();

        checkpoints.deleteAsync(uuid);
    }

    // =========================================================================
    //  Default post-capture handler — immediate resolve (no animation)
    //  Phase 4 replaces this via setAfterCapture().
    // =========================================================================

    /**
     * Roll the outcome and deliver immediately, with no animation.
     * Used as the default {@link #afterCapture} callback until Phase 4 wires in
     * the animation pipeline.
     */
    private void immediateResolve(@NotNull Transaction tx) {
        UUID uuid = tx.playerId();

        // Roll outcome using the machine's config profile.
        Outcome outcome       = roller.rollForProfile(tx.configProfile());
        int multiplier        = config.outcomeMultiplier(outcome);
        int rawOutput         = tx.inputAmount() * multiplier;
        int outputAmount      = config.clampOnOverflow()
                ? Math.min(rawOutput, config.maxReturnAmount())
                : rawOutput;

        // Mark DELIVERING in checkpoint BEFORE any delivery (crash safety).
        boolean deliverOk = checkpoints.markDelivering(uuid, outcome, outputAmount);
        if (!deliverOk) {
            logger.severe("[Resolve] markDelivering failed for " + tx.playerName()
                    + " — aborting to avoid phantom delivery.");
            abortTransaction(uuid, "delivering_checkpoint_failed");
            return;
        }

        // Advance transaction to DELIVERING.
        tx.markDelivering(outcome, outputAmount);

        // Deliver reward (if any).
        if (outcome != Outcome.DESTROYED && outputAmount > 0) {
            ItemStack reward = tx.copyOfSacrificeWithAmount(outputAmount);
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                giveOrDrop(player, reward);
                player.updateInventory();
            } else {
                deliveryQueue.add(uuid, tx.playerName(), reward, "offline_during_resolution");
            }
        }

        // Notify player.
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.sendMessage(outcomeMessage(outcome, outputAmount));
        }

        // Advance to COMPLETED.
        tx.markCompleted();

        // Audit.
        audit.logCompleted(uuid, tx.playerName(),
                tx.machineLoc(),
                tx.sacrifice() != null ? tx.sacrifice().getType().key().asString() : "unknown",
                tx.inputAmount(), outcome, outputAmount);

        logger.info("[Resolve] " + tx.playerName() + " → " + outcome.name()
                + " (in=" + tx.inputAmount() + " out=" + outputAmount + ')');

        // Cleanup.
        completeTransaction(tx);
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private void giveOrDrop(@NotNull Player player, @NotNull ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (overflow.isEmpty()) return;
        Location loc = player.getLocation();
        for (ItemStack drop : overflow.values()) {
            loc.getWorld().dropItemNaturally(loc, drop);
        }
    }

    @NotNull
    private static String outcomeMessage(@NotNull Outcome outcome, int outputAmount) {
        return switch (outcome) {
            case DESTROYED  -> "§7[VoidMachine] §8The void swallows your sacrifice without a word.";
            case RETURNED   -> "§7[VoidMachine] §fThe machine returns your offering unchanged.";
            case DOUBLED    -> "§7[VoidMachine] §aThe void amplifies your sacrifice — §l×2§r§a!";
            case TRIPLED    -> "§7[VoidMachine] §6The void surges — §l×3§r§6!";
            case JACKPOT_X5 -> "§7[VoidMachine] §d§lTHE VOID AWAKENS — §l×5§r§d§l! The machine shudders!";
        };
    }
}
