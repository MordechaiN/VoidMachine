package com.voidmachine.core.migration;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * A V1 write-ahead checkpoint ({@code plugins/VoidMachine/checkpoints/tx-*.dat}, binary format 2).
 *
 * <p>V1 states: 1 = CAPTURED, 2 = ANIMATING (item taken, outcome never persisted → V2 refunds it),
 * 3 = DELIVERING (V1 may have partially delivered → V2 holds it for an admin decision).</p>
 *
 * @param itemBytes {@code ItemStack#serializeAsBytes()} of the whole V1 stack (amount included)
 */
public record V1Checkpoint(int state, UUID playerId, String playerName, String machineLocation, String profile,
                           long capturedAt, byte[] itemBytes, int outcomeOrdinal, int outputAmount) {

    public static final int CAPTURED = 1;
    public static final int ANIMATING = 2;
    public static final int DELIVERING = 3;

    /** V1 outcome enum order (the file stores ordinals). */
    private static final java.util.List<String> V1_OUTCOMES = java.util.List.of("consumed", "returned", "doubled", "tripled", "jackpot");

    /** Defensive copy, like the journal's records. */
    @Override
    public byte[] itemBytes() {
        return itemBytes.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof V1Checkpoint c && state == c.state && capturedAt == c.capturedAt && outcomeOrdinal == c.outcomeOrdinal
                && outputAmount == c.outputAmount && playerId.equals(c.playerId) && playerName.equals(c.playerName)
                && machineLocation.equals(c.machineLocation) && profile.equals(c.profile) && java.util.Arrays.equals(itemBytes, c.itemBytes);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(state, playerId, capturedAt, outcomeOrdinal, outputAmount) * 31 + java.util.Arrays.hashCode(itemBytes);
    }

    @Override
    public String toString() {
        return "V1Checkpoint[state=" + state + ", player=" + playerName + ", item=" + itemBytes.length + " bytes, outcome=" + outcomeName() + "]";
    }

    public boolean needsAdminDecision() {
        return state == DELIVERING;
    }

    public String outcomeName() {
        return outcomeOrdinal >= 0 && outcomeOrdinal < V1_OUTCOMES.size() ? V1_OUTCOMES.get(outcomeOrdinal) : "unknown";
    }

    /**
     * Reads a V1 checkpoint file.
     *
     * @throws IOException with a reason if the bytes are not a valid V1 checkpoint
     */
    public static V1Checkpoint parse(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte version = in.readByte();
            if (version != 2) throw new IOException("unsupported V1 checkpoint version " + version);
            byte state = in.readByte();
            if (state < CAPTURED || state > DELIVERING) throw new IOException("unknown V1 state " + state);
            UUID player = UUID.fromString(in.readUTF());
            String name = in.readUTF();
            String machine = in.readUTF();
            String profile = in.readUTF();
            long captured = in.readLong();
            int len = in.readInt();
            if (len <= 0 || len > 1_048_576) throw new IOException("bad item length " + len);
            byte[] item = new byte[len];
            in.readFully(item);
            byte outcome = in.readByte();
            int output = in.readInt();
            return new V1Checkpoint(state, player, name, machine, profile, captured, item, outcome, output);
        } catch (IllegalArgumentException e) {
            throw new IOException("corrupt V1 checkpoint: " + e.getMessage(), e);
        }
    }
}
