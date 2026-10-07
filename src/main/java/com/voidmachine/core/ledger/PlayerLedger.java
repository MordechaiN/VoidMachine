package com.voidmachine.core.ledger;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The player's side of every obligation: how many reward items of each ritual have already been put
 * into their inventory. Stored as a string in the player's PersistentDataContainer, which Minecraft
 * writes in the <em>same file, in the same save</em> as the inventory. That makes "items received"
 * and "ledger says paid" one atomic fact, which is what lets recovery decide without guessing.
 *
 * <p>Encoding: {@code v1|<uuid>:<paid>:<reward>|...}. Presence of an entry is the capture mark.</p>
 *
 * <p>Instances are mutable and confined to the server main thread.</p>
 */
public final class PlayerLedger {

    private static final String VERSION = "v1";
    /** Sanity limit; a ledger with more entries is treated as corrupt rather than parsed. */
    public static final int MAX_ENTRIES = 4096;

    private final LinkedHashMap<UUID, Entry> entries;

    private PlayerLedger(LinkedHashMap<UUID, Entry> entries) {
        this.entries = entries;
    }

    public static PlayerLedger empty() {
        return new PlayerLedger(new LinkedHashMap<>());
    }

    /**
     * Parses the stored form. {@code null} or empty means an empty ledger.
     *
     * @throws LedgerFormatException if the value exists but cannot be trusted
     */
    public static PlayerLedger decode(String encoded) throws LedgerFormatException {
        LinkedHashMap<UUID, Entry> map = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) return new PlayerLedger(map);
        String[] parts = encoded.split("\\|", -1);
        if (!VERSION.equals(parts[0])) throw new LedgerFormatException("unknown ledger version '" + parts[0] + "'");
        if (parts.length - 1 > MAX_ENTRIES) throw new LedgerFormatException("ledger has more than " + MAX_ENTRIES + " entries");
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            String[] f = p.split(":", -1);
            if (f.length != 3) throw new LedgerFormatException("malformed entry '" + p + "'");
            try {
                UUID id = UUID.fromString(f[0]);
                int paid = Integer.parseInt(f[1]);
                int reward = Integer.parseInt(f[2]);
                if (paid < 0 || reward < 0) throw new LedgerFormatException("negative amount in '" + p + "'");
                if (map.put(id, new Entry(id, paid, reward)) != null) {
                    throw new LedgerFormatException("duplicate entry for " + id);
                }
            } catch (IllegalArgumentException e) {
                throw new LedgerFormatException("malformed entry '" + p + "': " + e.getMessage());
            }
        }
        return new PlayerLedger(map);
    }

    /** Stored form; {@code null} when empty so the key can be removed from the container. */
    public String encode() {
        if (entries.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(VERSION);
        for (Entry e : entries.values()) {
            sb.append('|').append(e.ritualId()).append(':').append(e.paid()).append(':').append(e.reward());
        }
        return sb.toString();
    }

    public Entry get(UUID ritualId) {
        return entries.get(ritualId);
    }

    public boolean contains(UUID ritualId) {
        return entries.containsKey(ritualId);
    }

    /** Records the capture mark (nothing paid yet). Fails if the ritual is already present. */
    public void markCaptured(UUID ritualId, int reward) {
        if (entries.containsKey(ritualId)) {
            throw new IllegalStateException("ritual " + ritualId + " already has a ledger entry");
        }
        entries.put(ritualId, new Entry(ritualId, 0, reward));
    }

    /** Sets the paid amount for a ritual, creating the entry if needed (claims). */
    public void recordPaid(UUID ritualId, int paid, int reward) {
        if (paid < 0) throw new IllegalArgumentException("paid cannot be negative");
        entries.put(ritualId, new Entry(ritualId, paid, reward));
    }

    public void remove(UUID ritualId) {
        entries.remove(ritualId);
    }

    public Collection<Entry> entries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public Map<UUID, Entry> asMap() {
        return Collections.unmodifiableMap(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** One ledger line. {@code reward} is a copy kept for forensics when the journal record is missing. */
    public record Entry(UUID ritualId, int paid, int reward) {
        public Entry {
            Objects.requireNonNull(ritualId, "ritualId");
        }

        public boolean settled() {
            return paid >= reward;
        }
    }

    /** The stored ledger cannot be trusted; affected obligations must be left untouched for an admin. */
    public static final class LedgerFormatException extends Exception {
        public LedgerFormatException(String message) {
            super(message);
        }
    }
}
