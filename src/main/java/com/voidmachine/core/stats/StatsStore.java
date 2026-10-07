package com.voidmachine.core.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JSON persistence for {@link StatsBook}: written to a temp file and atomically renamed, so a crash
 * never leaves a half-written file. A file that cannot be parsed is kept aside (never overwritten
 * silently) and statistics start fresh.
 */
public final class StatsStore {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path file;

    public StatsStore(Path file) {
        this.file = file;
    }

    private static final int FORMAT = 1;

    private static final class Document {
        int version = FORMAT;
        StatsBook.Global global;
        Map<String, StatsBook.PlayerStats> players;
    }

    public StatsBook load() throws IOException {
        StatsBook book = new StatsBook();
        if (!Files.exists(file)) return book;
        try {
            Document doc = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Document.class);
            if (doc == null) return book;
            if (doc.version != FORMAT) throw new JsonParseException("unsupported statistics format " + doc.version);
            if (doc.global != null) {
                if (doc.global.byHour == null || doc.global.byHour.length != 24) doc.global.byHour = new long[24];
                if (doc.global.outcomes == null) doc.global.outcomes = new java.util.LinkedHashMap<>();
                if (doc.global.tiers == null) doc.global.tiers = new java.util.LinkedHashMap<>();
                if (doc.global.jackpotVariants == null) doc.global.jackpotVariants = new java.util.LinkedHashMap<>();
                if (doc.global.machines == null) doc.global.machines = new java.util.LinkedHashMap<>();
                book.global = doc.global;
            }
            if (doc.players != null) {
                Map<UUID, StatsBook.PlayerStats> players = new HashMap<>();
                doc.players.forEach((k, v) -> {
                    if (v.outcomes == null) v.outcomes = new java.util.LinkedHashMap<>();
                    if (v.machines == null) v.machines = new java.util.LinkedHashMap<>();
                    players.put(UUID.fromString(k), v);
                });
                book.players = players;
            }
            return book;
        } catch (JsonParseException | IllegalArgumentException e) {
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            throw new IOException("statistics file was unreadable and has been moved to " + aside.getFileName()
                    + "; statistics restart from zero (" + e.getMessage() + ")", e);
        }
    }

    /** Writes a snapshot. Call with a {@link StatsBook#copy()} from a background thread. */
    public void save(StatsBook snapshot) throws IOException {
        Document doc = new Document();
        doc.global = snapshot.global;
        Map<String, StatsBook.PlayerStats> players = new HashMap<>();
        snapshot.players.forEach((k, v) -> players.put(k.toString(), v));
        doc.players = players;
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(doc), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
