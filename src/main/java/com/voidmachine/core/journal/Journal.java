package com.voidmachine.core.journal;

import java.util.List;
import java.util.UUID;

/**
 * Durable store of the Void's obligations. Every mutating method returns only after the change is
 * durable (or throws). Implementations must be safe to call from one dedicated I/O thread; callers
 * never touch the journal from the server main thread except during startup and shutdown.
 */
public interface Journal {

    /** Creates a new record. Fails if a record with the same id already exists. */
    void create(JournalRecord record) throws JournalException;

    /** Replaces an existing record (admin actions only). The revision must increase. */
    void replace(JournalRecord record) throws JournalException;

    /** Deletes a record. Returns {@code false} if it did not exist. */
    boolean delete(UUID ritualId) throws JournalException;

    /** Loads every record. Unreadable files are quarantined and reported, never deleted. */
    LoadResult loadAll() throws JournalException;

    /** Verifies the storage is writable and durable right now (write + fsync + delete a probe file). */
    void probe() throws JournalException;

    record LoadResult(List<JournalRecord> records, List<Quarantined> quarantined, int abandonedTemps) {
    }

    record Quarantined(String fileName, String reason) {
    }
}
