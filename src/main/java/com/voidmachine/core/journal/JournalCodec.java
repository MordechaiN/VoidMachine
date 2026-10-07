package com.voidmachine.core.journal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.voidmachine.core.outcome.Multiplier;
import com.voidmachine.core.outcome.OutcomeTier;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * On-disk format of a journal record:
 *
 * <pre>
 * VMJ2 &lt;crc32c of body, 8 hex digits&gt;\n
 * {json body}
 * </pre>
 *
 * The checksum detects torn or bit-rotted files; a record that fails it is quarantined, never
 * silently deleted. The body is plain JSON so admins can read it.
 */
public final class JournalCodec {

    public static final String MAGIC = "VMJ2";
    public static final int FORMAT_VERSION = 2;
    /** Hard limit on record size. Item templates are single items; anything larger is corrupt. */
    public static final int MAX_RECORD_BYTES = 1 << 20;

    private JournalCodec() {
    }

    public static byte[] encode(JournalRecord r) {
        JsonObject o = new JsonObject();
        o.addProperty("v", FORMAT_VERSION);
        o.addProperty("id", r.ritualId().toString());
        o.addProperty("revision", r.revision());
        o.addProperty("kind", r.kind().name());
        o.addProperty("player", r.playerId().toString());
        o.addProperty("playerName", r.playerName());
        o.addProperty("machine", r.machineId());
        o.addProperty("location", r.machineLocation());
        o.addProperty("profile", r.profileId());
        o.addProperty("created", r.createdAtMillis());
        o.addProperty("itemKey", r.itemKey());
        o.addProperty("item", Base64.getEncoder().encodeToString(r.itemTemplate()));
        o.addProperty("input", r.inputAmount());
        o.addProperty("outcome", r.outcomeId());
        o.addProperty("tier", r.tier().name());
        o.addProperty("multiplier", r.multiplier().toStorageString());
        o.addProperty("reward", r.rewardAmount());
        o.addProperty("note", r.note());
        byte[] body = o.toString().getBytes(StandardCharsets.UTF_8);
        String header = MAGIC + " " + crcHex(body) + "\n";
        byte[] head = header.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    /**
     * @throws CorruptRecordException with a precise reason when the bytes are not a valid record
     */
    public static JournalRecord decode(byte[] bytes) throws CorruptRecordException {
        if (bytes.length > MAX_RECORD_BYTES) throw new CorruptRecordException("record larger than " + MAX_RECORD_BYTES + " bytes");
        int nl = -1;
        for (int i = 0; i < Math.min(bytes.length, 64); i++) {
            if (bytes[i] == '\n') {
                nl = i;
                break;
            }
        }
        if (nl < 0) throw new CorruptRecordException("missing header line");
        String header = new String(bytes, 0, nl, StandardCharsets.US_ASCII);
        String[] parts = header.split(" ");
        if (parts.length != 2 || !MAGIC.equals(parts[0])) {
            throw new CorruptRecordException("bad header '" + header + "'");
        }
        byte[] body = new byte[bytes.length - nl - 1];
        System.arraycopy(bytes, nl + 1, body, 0, body.length);
        String expected = parts[1].toLowerCase(java.util.Locale.ROOT);
        String actual = crcHex(body);
        if (!expected.equals(actual)) {
            throw new CorruptRecordException("checksum mismatch (expected " + expected + ", got " + actual + ")");
        }
        try {
            JsonObject o = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            int v = req(o, "v").getAsInt();
            if (v != FORMAT_VERSION) throw new CorruptRecordException("unsupported format version " + v);
            Multiplier multiplier = Multiplier.parse(req(o, "multiplier").getAsString());
            OutcomeTier tier = OutcomeTier.parse(req(o, "tier").getAsString())
                    .orElseThrow(() -> new CorruptRecordException("unknown tier"));
            return new JournalRecord(
                    UUID.fromString(req(o, "id").getAsString()),
                    req(o, "revision").getAsInt(),
                    RecordKind.valueOf(req(o, "kind").getAsString()),
                    UUID.fromString(req(o, "player").getAsString()),
                    req(o, "playerName").getAsString(),
                    req(o, "machine").getAsString(),
                    req(o, "location").getAsString(),
                    req(o, "profile").getAsString(),
                    req(o, "created").getAsLong(),
                    req(o, "itemKey").getAsString(),
                    Base64.getDecoder().decode(req(o, "item").getAsString()),
                    req(o, "input").getAsInt(),
                    req(o, "outcome").getAsString(),
                    tier,
                    multiplier,
                    req(o, "reward").getAsInt(),
                    o.has("note") ? o.get("note").getAsString() : "");
        } catch (CorruptRecordException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CorruptRecordException("unreadable body: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static JsonElement req(JsonObject o, String key) throws CorruptRecordException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) throw new CorruptRecordException("missing field '" + key + "'");
        return e;
    }

    static String crcHex(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        return HexFormat.of().toHexDigits((int) crc.getValue());
    }

    /** A record that cannot be trusted. Carries a reason suitable for admin output. */
    public static final class CorruptRecordException extends Exception {
        public CorruptRecordException(String reason) {
            super(reason);
        }
    }
}
