package com.voidmachine.core.journal;

import com.voidmachine.core.outcome.Multiplier;
import com.voidmachine.core.outcome.OutcomeDefinition;
import com.voidmachine.core.outcome.OutcomeEngine;
import com.voidmachine.core.outcome.OutcomeTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileJournalTest {

    @TempDir
    Path dir;

    static JournalRecord record() {
        var def = new OutcomeDefinition("doubled", new Multiplier(2, 1), OutcomeTier.WIN);
        return JournalRecord.forRitual(UUID.randomUUID(), UUID.randomUUID(), "Steve", "altar", "world:1:64:2",
                "default", 1_700_000_000_000L, "minecraft:diamond", new byte[]{10, 20, 30},
                OutcomeEngine.verdictFor(def, 32, 320));
    }

    @Test
    void roundTripPreservesEveryField() throws Exception {
        JournalRecord r = record();
        JournalRecord back = JournalCodec.decode(JournalCodec.encode(r));
        assertEquals(r.ritualId(), back.ritualId());
        assertEquals(r.playerId(), back.playerId());
        assertEquals(r.playerName(), back.playerName());
        assertEquals(r.machineId(), back.machineId());
        assertEquals(r.machineLocation(), back.machineLocation());
        assertEquals(r.createdAtMillis(), back.createdAtMillis());
        assertArrayEquals(r.itemTemplate(), back.itemTemplate());
        assertEquals(r.inputAmount(), back.inputAmount());
        assertEquals(r.rewardAmount(), back.rewardAmount());
        assertEquals(r.multiplier(), back.multiplier());
        assertEquals(r.tier(), back.tier());
        assertEquals(r.kind(), back.kind());
    }

    @Test
    void createLoadDelete() throws Exception {
        FileJournal j = new FileJournal(dir);
        JournalRecord r = record();
        j.create(r);
        assertThrows(JournalException.class, () -> j.create(r), "duplicate create must fail");
        Journal.LoadResult load = j.loadAll();
        assertEquals(1, load.records().size());
        assertTrue(j.delete(r.ritualId()));
        assertFalse(j.delete(r.ritualId()));
        assertEquals(0, j.loadAll().records().size());
    }

    @Test
    void corruptFilesAreQuarantinedNotDeleted() throws Exception {
        FileJournal j = new FileJournal(dir);
        JournalRecord r = record();
        j.create(r);
        Path file = dir.resolve(r.ritualId() + FileJournal.EXTENSION);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 3] ^= 0x5A; // flip bits in the body
        Files.write(file, bytes);
        Files.writeString(dir.resolve(UUID.randomUUID() + FileJournal.EXTENSION), "garbage", StandardCharsets.UTF_8);

        Journal.LoadResult load = j.loadAll();
        assertEquals(0, load.records().size());
        assertEquals(2, load.quarantined().size());
        assertTrue(load.quarantined().get(0).reason().length() > 5);
        assertFalse(Files.exists(file));
        assertEquals(2, j.listQuarantine().size(), "quarantined files must be kept for admins");
    }

    @Test
    void renamedRecordIsRejected() throws Exception {
        FileJournal j = new FileJournal(dir);
        JournalRecord r = record();
        j.create(r);
        Files.move(dir.resolve(r.ritualId() + FileJournal.EXTENSION), dir.resolve(UUID.randomUUID() + FileJournal.EXTENSION));
        Journal.LoadResult load = j.loadAll();
        assertEquals(0, load.records().size());
        assertEquals(1, load.quarantined().size());
    }

    @Test
    void uncommittedTempFilesAreRemoved() throws Exception {
        FileJournal j = new FileJournal(dir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(UUID.randomUUID() + FileJournal.EXTENSION + ".tmp"), "half a record");
        Journal.LoadResult load = j.loadAll();
        assertEquals(1, load.abandonedTemps());
        assertEquals(0, load.records().size());
    }

    @Test
    void replaceRequiresExistingRecord() throws Exception {
        FileJournal j = new FileJournal(dir);
        var consumedDef = new OutcomeDefinition("consumed", Multiplier.ZERO, OutcomeTier.LOSS);
        JournalRecord r = JournalRecord.forRitual(UUID.randomUUID(), UUID.randomUUID(), "Alex", "altar", "w:0:0:0",
                "default", 0, "minecraft:emerald", new byte[]{1}, OutcomeEngine.verdictFor(consumedDef, 8, 320));
        assertThrows(JournalException.class, () -> j.replace(r.asRefund("admin")));
        j.create(r);
        j.replace(r.asRefund("admin"));
        JournalRecord back = j.loadAll().records().getFirst();
        assertEquals(2, back.revision());
        assertEquals(8, back.rewardAmount());
        assertTrue(back.note().contains("original=consumed"));
    }

    @Test
    void probeSucceedsOnWritableDirectory() throws Exception {
        new FileJournal(dir.resolve("nested")).probe();
    }

    @Test
    void probeFailsWhenRootIsAFile() throws Exception {
        Path notADir = dir.resolve("file");
        Files.writeString(notADir, "x");
        assertThrows(JournalException.class, () -> new FileJournal(notADir).probe());
    }
}
