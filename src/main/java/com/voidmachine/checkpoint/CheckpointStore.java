/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.checkpoint;

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.core.Outcome;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Write-Ahead Log (WAL) checkpoint store for in-flight transactions.
 *
 * <h3>Safety guarantee</h3>
 * The checkpoint is written — and {@code fsync}'d to disk — <em>before</em> the
 * item is removed from the player's hand. On any crash after the write, a file
 * exists and the item can be recovered on the next server start.
 *
 * <h3>Filename format</h3>
 * {@code checkpoints/tx-<playerUUID>-<capturedAtEpoch>.dat}
 *
 * <p>Including the epoch makes stale files easy to identify during forensic review
 * and leaves room for a future multi-tx-per-player design without filename collisions.</p>
 *
 * <h3>Binary format — version 2</h3>
 * <pre>
 *   byte    FORMAT_VERSION (2)
 *   byte    state code  (1=CAPTURED, 2=ANIMATING, 3=DELIVERING)
 *   UTF     playerUuid (UUID.toString(), 36 chars)
 *   UTF     playerName
 *   UTF     machineLocationKey  ("worldName:x:y:z")
 *   UTF     machineProfile      (e.g. "default")
 *   long    capturedAtEpoch     (System.currentTimeMillis() at capture)
 *   int     itemByteLength
 *   byte[]  itemBytes           (ItemStack.serializeAsBytes() — full NBT)
 *   byte    outcomeOrdinal      (-1 = not yet resolved; otherwise Outcome.ordinal())
 *   int     outputAmount        (0 if not yet resolved)
 * </pre>
 *
 * <p><strong>Warning:</strong> The {@code outcomeOrdinal} field is encoded as
 * {@link Outcome#ordinal()}. Do not reorder the {@link Outcome} enum values.</p>
 *
 * <h3>Caller contract (enforced by ItemCaptureService)</h3>
 * <ol>
 *   <li>{@link #writeSync} — BEFORE item removed. If this returns false, abort.</li>
 *   <li>Item removed from player hand ({@code setItemInMainHand(AIR)}).</li>
 *   <li>Machine locked, animation starts.</li>
 *   <li>{@link #markAnimating} — best-effort, non-fatal on failure.</li>
 *   <li>Outcome resolved.</li>
 *   <li>{@link #markDelivering} — MUST succeed; abort if it fails.</li>
 *   <li>Items delivered to player inventory.</li>
 *   <li>{@link #deleteAsync} — after delivery confirmed.</li>
 * </ol>
 */
public final class CheckpointStore {

    private static final byte FORMAT_VERSION = 2;
    private static final String PREFIX = "tx-";
    private static final String SUFFIX = ".dat";

    /** Length of UUID.toString() output — always 36 characters. */
    private static final int UUID_STR_LEN = 36;

    /**
     * Persisted state of an in-flight transaction.
     *
     * <ul>
     *   <li>{@link #CAPTURED}   — item removed, animation not started.</li>
     *   <li>{@link #ANIMATING}  — animation running.</li>
     *   <li>{@link #DELIVERING} — outcome committed; delivery in progress.</li>
     * </ul>
     *
     * Recovery: CAPTURED and ANIMATING → return item. DELIVERING → admin review
     * (may have been partially delivered before the crash).
     */
    public enum State {
        CAPTURED((byte) 1),
        ANIMATING((byte) 2),
        DELIVERING((byte) 3);

        public final byte code;

        State(byte code) { this.code = code; }

        @Nullable
        public static State fromCode(byte code) {
            for (State s : values()) {
                if (s.code == code) return s;
            }
            return null;
        }
    }

    // -------------------------------------------------------------------------

    private final File checkpointDir;
    private final VoidMachinePlugin plugin;
    private final Logger logger;

    public CheckpointStore(@NotNull VoidMachinePlugin plugin) {
        this.plugin        = plugin;
        this.logger        = plugin.getLogger();
        this.checkpointDir = new File(plugin.getDataFolder(), "checkpoints");

        if (!checkpointDir.exists() && !checkpointDir.mkdirs()) {
            logger.severe("[CheckpointStore] Could not create checkpoints directory at "
                    + checkpointDir.getAbsolutePath());
        }
    }

    // =========================================================================
    //  Write (synchronous, main thread — BEFORE item removal)
    // =========================================================================

    /**
     * Write a new CAPTURED-state checkpoint to disk with {@code fsync}.
     *
     * <p>This is the <em>first</em> write for a transaction. The filename is
     * generated from the player UUID and the current epoch millis. Subsequent
     * state updates ({@link #markAnimating}, {@link #markDelivering}) rewrite the
     * same file at the same path.</p>
     *
     * <p>Must be called — and must return {@code true} — before the item is removed
     * from the player's inventory. If it returns {@code false}, abort the
     * transaction and do NOT remove the item.</p>
     *
     * @return {@code true} on success; {@code false} on I/O failure
     */
    public boolean writeSync(@NotNull UUID playerUuid,
                             @NotNull String playerName,
                             @NotNull String machineLocationKey,
                             @NotNull String machineProfile,
                             @NotNull ItemStack sacrifice,
                             long capturedAtEpoch) {
        File file = newCheckpointFile(playerUuid, capturedAtEpoch);
        return writeTo(file, playerUuid, playerName, machineLocationKey, machineProfile,
                sacrifice, capturedAtEpoch, State.CAPTURED, null, 0);
    }

    /**
     * Transition the checkpoint state from {@link State#CAPTURED} to
     * {@link State#ANIMATING}. Best-effort — a failure here is not fatal because
     * both CAPTURED and ANIMATING map to the same recovery action (return item).
     */
    public void markAnimating(@NotNull UUID playerUuid) {
        updateState(playerUuid, State.ANIMATING, true /* best-effort */);
    }

    /**
     * Transition the checkpoint state to {@link State#DELIVERING}.
     *
     * <p>This transition is critical. The caller <strong>must</strong> call this
     * and confirm it returned {@code true} before transferring any items to the
     * player's inventory. If it returns {@code false}, do NOT deliver; call
     * {@link #deleteAsync(UUID)} and abort (return item).</p>
     *
     * @param outcome       the resolved outcome (written to checkpoint for admin forensics)
     * @param outputAmount  resolved output stack size
     * @return {@code true} on success; {@code false} on I/O failure (abort delivery)
     */
    public boolean markDelivering(@NotNull UUID playerUuid,
                                  @NotNull Outcome outcome,
                                  int outputAmount) {
        CheckpointEntry existing = read(playerUuid);
        if (existing == null) {
            logger.warning("[CheckpointStore] markDelivering: no checkpoint for " + playerUuid);
            return false;
        }
        File file = findCheckpointFile(playerUuid);
        if (file == null) {
            logger.warning("[CheckpointStore] markDelivering: file not found for " + playerUuid);
            return false;
        }
        return writeTo(file, existing.playerUuid(), existing.playerName(),
                existing.machineLocationKey(), existing.machineProfile(),
                existing.sacrifice(), existing.capturedAtEpoch(),
                State.DELIVERING, outcome, outputAmount);
    }

    // =========================================================================
    //  Delete (asynchronous)
    // =========================================================================

    /**
     * Delete the checkpoint file asynchronously. Call only after the transaction
     * has reached COMPLETED or FAILED and any item refund has been confirmed.
     */
    public void deleteAsync(@NotNull UUID playerUuid) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            File file = findCheckpointFile(playerUuid);
            if (file == null) return; // already deleted — no-op
            if (!file.delete()) {
                logger.warning("[CheckpointStore] Could not delete checkpoint for " + playerUuid
                        + " at " + file.getName());
            }
        });
    }

    // =========================================================================
    //  Read
    // =========================================================================

    /**
     * Read the checkpoint for a player. Returns {@code null} if not found or unreadable.
     */
    @Nullable
    public CheckpointEntry read(@NotNull UUID playerUuid) {
        File file = findCheckpointFile(playerUuid);
        if (file == null) return null;
        return readFile(file);
    }

    /**
     * Read all checkpoint files present in the directory.
     * Used by {@link StartupRecovery} during {@code onEnable}.
     */
    @NotNull
    public List<CheckpointEntry> readAll() {
        List<CheckpointEntry> entries = new ArrayList<>();

        File[] files = checkpointDir.listFiles((dir, name) ->
                name.startsWith(PREFIX) && name.endsWith(SUFFIX));
        if (files == null) return entries;

        for (File file : files) {
            CheckpointEntry entry = readFile(file);
            if (entry != null) {
                entries.add(entry);
            }
        }

        return entries;
    }

    // =========================================================================
    //  Internal — file resolution
    // =========================================================================

    /**
     * Generate a new checkpoint filename for the initial write.
     * Format: {@code tx-<uuid>-<epoch>.dat}
     */
    @NotNull
    private File newCheckpointFile(@NotNull UUID uuid, long epoch) {
        return new File(checkpointDir, PREFIX + uuid.toString() + '-' + epoch + SUFFIX);
    }

    /**
     * Find an existing checkpoint file for a player UUID by scanning the directory.
     * Returns {@code null} if not found. If multiple files match (should never happen),
     * logs a warning and returns the most recently modified one.
     */
    @Nullable
    private File findCheckpointFile(@NotNull UUID uuid) {
        String uuidPrefix = PREFIX + uuid.toString() + '-';
        File[] matches = checkpointDir.listFiles((dir, name) ->
                name.startsWith(uuidPrefix) && name.endsWith(SUFFIX));

        if (matches == null || matches.length == 0) return null;

        if (matches.length > 1) {
            logger.warning("[CheckpointStore] Multiple checkpoint files for " + uuid
                    + " — using most recently modified.");
            Arrays.sort(matches, Comparator.comparingLong(File::lastModified).reversed());
        }

        return matches[0];
    }

    // =========================================================================
    //  Internal — state update
    // =========================================================================

    private void updateState(@NotNull UUID playerUuid, @NotNull State newState,
                             boolean bestEffort) {
        CheckpointEntry existing = read(playerUuid);
        if (existing == null) {
            if (!bestEffort) {
                logger.warning("[CheckpointStore] updateState(" + newState
                        + "): no checkpoint for " + playerUuid);
            }
            return;
        }
        File file = findCheckpointFile(playerUuid);
        if (file == null) {
            if (!bestEffort) {
                logger.warning("[CheckpointStore] updateState(" + newState
                        + "): file not found for " + playerUuid);
            }
            return;
        }
        boolean ok = writeTo(file, existing.playerUuid(), existing.playerName(),
                existing.machineLocationKey(), existing.machineProfile(),
                existing.sacrifice(), existing.capturedAtEpoch(),
                newState, existing.outcome(), existing.outputAmount());
        if (!ok && !bestEffort) {
            logger.warning("[CheckpointStore] updateState(" + newState
                    + ") write failed for " + playerUuid);
        }
    }

    // =========================================================================
    //  Internal — binary I/O
    // =========================================================================

    /**
     * Write the full checkpoint record to {@code file} with {@code fsync}.
     * Creates or overwrites the file. Returns {@code true} on success.
     */
    private boolean writeTo(@NotNull File file,
                            @NotNull UUID playerUuid,
                            @NotNull String playerName,
                            @NotNull String machineLocationKey,
                            @NotNull String machineProfile,
                            @NotNull ItemStack sacrifice,
                            long capturedAtEpoch,
                            @NotNull State state,
                            @Nullable Outcome outcome,
                            int outputAmount) {
        byte[] itemBytes;
        try {
            itemBytes = sacrifice.serializeAsBytes();
        } catch (Exception e) {
            logger.severe("[CheckpointStore] Cannot serialize sacrifice for "
                    + playerUuid + ": " + e.getMessage());
            return false;
        }

        try (FileOutputStream fos = new FileOutputStream(file, false);
             DataOutputStream dos = new DataOutputStream(fos)) {

            dos.writeByte(FORMAT_VERSION);
            dos.writeByte(state.code);
            dos.writeUTF(playerUuid.toString());
            dos.writeUTF(playerName);
            dos.writeUTF(machineLocationKey);
            dos.writeUTF(machineProfile);
            dos.writeLong(capturedAtEpoch);
            dos.writeInt(itemBytes.length);
            dos.write(itemBytes);
            // Outcome: -1 if not yet resolved; Outcome.ordinal() otherwise.
            dos.writeByte(outcome != null ? outcome.ordinal() : -1);
            dos.writeInt(outputAmount);
            dos.flush();

            // Force OS write buffers to the storage device.
            fos.getFD().sync();

        } catch (IOException e) {
            logger.severe("[CheckpointStore] Write FAILED for "
                    + playerUuid + ": " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            file.delete(); // clean up partial write
            return false;
        }

        return true;
    }

    /**
     * Read and parse a checkpoint file. Returns {@code null} on any error.
     */
    @Nullable
    private CheckpointEntry readFile(@NotNull File file) {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {

            byte version = dis.readByte();
            if (version != FORMAT_VERSION) {
                logger.warning("[CheckpointStore] Unknown format version " + version
                        + " in " + file.getName() + " — skipping.");
                return null;
            }

            byte stateCode = dis.readByte();
            State state = State.fromCode(stateCode);
            if (state == null) {
                logger.warning("[CheckpointStore] Unknown state code " + stateCode
                        + " in " + file.getName() + " — skipping.");
                return null;
            }

            UUID uuid      = UUID.fromString(dis.readUTF());
            String name    = dis.readUTF();
            String machineLoc = dis.readUTF();
            String machineProfile = dis.readUTF();
            long capturedAt = dis.readLong();

            int itemLen = dis.readInt();
            if (itemLen <= 0 || itemLen > 1_048_576) {
                logger.warning("[CheckpointStore] Suspicious item length " + itemLen
                        + " in " + file.getName() + " — skipping.");
                return null;
            }
            byte[] itemBytes = new byte[itemLen];
            dis.readFully(itemBytes);
            ItemStack sacrifice = ItemStack.deserializeBytes(itemBytes);

            byte outcomeCode = dis.readByte();
            Outcome outcome  = null;
            if (outcomeCode != -1) {
                Outcome[] values = Outcome.values();
                if (outcomeCode >= 0 && outcomeCode < values.length) {
                    outcome = values[outcomeCode];
                } else {
                    logger.warning("[CheckpointStore] Unknown outcome ordinal " + outcomeCode
                            + " in " + file.getName());
                }
            }
            int outputAmount = dis.readInt();

            return new CheckpointEntry(uuid, name, machineLoc, machineProfile,
                    capturedAt, sacrifice, state, outcome, outputAmount);

        } catch (Exception e) {
            logger.severe("[CheckpointStore] Failed to read " + file.getName()
                    + ": " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    //  CheckpointEntry record
    // =========================================================================

    /**
     * Immutable snapshot of a persisted checkpoint.
     *
     * @param playerUuid         UUID of the sacrificing player
     * @param playerName         display name at time of capture (for logging)
     * @param machineLocationKey location key of the machine ("worldName:x:y:z")
     * @param machineProfile     config profile name
     * @param capturedAtEpoch    epoch millis at capture (also embedded in filename)
     * @param sacrifice          full-NBT item clone
     * @param state              persisted state (CAPTURED / ANIMATING / DELIVERING)
     * @param outcome            resolved outcome, or {@code null} if not yet resolved
     * @param outputAmount       resolved output amount (0 if not yet resolved)
     */
    public record CheckpointEntry(
            @NotNull UUID playerUuid,
            @NotNull String playerName,
            @NotNull String machineLocationKey,
            @NotNull String machineProfile,
            long capturedAtEpoch,
            @NotNull ItemStack sacrifice,
            @NotNull State state,
            @Nullable Outcome outcome,
            int outputAmount
    ) {}
}
