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
    public static final String[] V1_OUTCOMES = {"consumed", "returned", "doubled", "tripled", "jackpot"};

    public boolean needsAdminDecision() {
        return state == DELIVERING;
    }

    public String outcomeName() {
        return outcomeOrdinal >= 0 && outcomeOrdinal < V1_OUTCOMES.length ? V1_OUTCOMES[outcomeOrdinal] : "unknown";
    }

    /**
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
