package com.voidmachine.core.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One file per record under {@code root}. Writes go to {@code <id>.vmj.tmp}, are fsync'd, atomically
 * renamed to {@code <id>.vmj}, and the directory is fsync'd so the rename itself survives power loss.
 *
 * <p>A leftover {@code .tmp} file is a write that never committed. Because the offering is only taken
 * after {@link #create} returns, such a file never corresponds to a taken item and is removed at load.</p>
 */
public final class FileJournal implements Journal {

    public static final String EXTENSION = ".vmj";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final Path root;
    private final Path quarantine;

    public FileJournal(Path root) {
        this.root = root;
        this.quarantine = root.resolve("quarantine");
    }

    public Path root() {
        return root;
    }

    public Path quarantineDir() {
        return quarantine;
    }

    @Override
    public synchronized void create(JournalRecord record) throws JournalException {
        Path target = fileFor(record.ritualId());
        if (Files.exists(target)) {
            throw new JournalException("record " + record.ritualId() + " already exists");
        }
        write(record, target, false);
    }

    @Override
    public synchronized void replace(JournalRecord record) throws JournalException {
        Path target = fileFor(record.ritualId());
        if (!Files.exists(target)) {
            throw new JournalException("record " + record.ritualId() + " does not exist");
        }
        write(record, target, true);
    }

    @Override
    public synchronized boolean delete(UUID ritualId) throws JournalException {
        Path target = fileFor(ritualId);
        try {
            boolean existed = Files.deleteIfExists(target);
            if (existed) syncDirectory(root);
            return existed;
        } catch (IOException e) {
            throw new JournalException("cannot delete record " + ritualId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized LoadResult loadAll() throws JournalException {
        ensureDirectories();
        List<JournalRecord> records = new ArrayList<>();
        List<Quarantined> quarantined = new ArrayList<>();
        int temps = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString();
                if (name.endsWith(TEMP_SUFFIX)) {
                    Files.deleteIfExists(file);
                    temps++;
                    continue;
                }
                if (!name.endsWith(EXTENSION)) continue;
                try {
                    long size = Files.size(file);
                    if (size > JournalCodec.MAX_RECORD_BYTES) {
                        throw new JournalCodec.CorruptRecordException("file is " + size + " bytes");
                    }
                    JournalRecord record = JournalCodec.decode(Files.readAllBytes(file));
                    String expectedName = record.ritualId() + EXTENSION;
                    if (!expectedName.equals(name)) {
                        throw new JournalCodec.CorruptRecordException("file name does not match record id " + record.ritualId());
                    }
                    records.add(record);
                } catch (JournalCodec.CorruptRecordException e) {
                    quarantined.add(quarantine(file, e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new JournalException("cannot read journal directory " + root + ": " + e.getMessage(), e);
        }
        if (temps > 0) syncDirectoryQuietly(root);
        return new LoadResult(List.copyOf(records), List.copyOf(quarantined), temps);
    }

    @Override
    public synchronized void probe() throws JournalException {
        ensureDirectories();
        Path probe = root.resolve(".probe" + TEMP_SUFFIX);
        try {
            try (FileChannel ch = FileChannel.open(probe, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(ByteBuffer.wrap(Instant.now().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                ch.force(true);
            }
            Files.delete(probe);
            syncDirectory(root);
        } catch (IOException e) {
            throw new JournalException("journal storage is not writable: " + e.getMessage(), e);
        }
    }

    /** Lists quarantined file names (for diagnostics). */
    public synchronized List<String> listQuarantine() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(quarantine)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(quarantine, "*" + EXTENSION + "*")) {
            for (Path p : stream) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".reason.txt")) out.add(n);
            }
        } catch (IOException ignored) {
            // Diagnostics only; an unreadable quarantine directory is reported as empty.
        }
        return out;
    }

    Path fileFor(UUID id) {
        return root.resolve(id + EXTENSION);
    }

    private void write(JournalRecord record, Path target, boolean replace) throws JournalException {
        ensureDirectories();
        byte[] data = JournalCodec.encode(record);
        Path temp = root.resolve(record.ritualId() + EXTENSION + TEMP_SUFFIX);
        try {
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(data);
                while (buf.hasRemaining()) ch.write(buf);
                ch.force(true);
            }
            try {
                if (replace) {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException e) {
                throw new JournalException("the file system does not support atomic renames: " + root, e);
            }
            syncDirectory(root);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // The temp file is removed at the next load; it never represents a committed record.
            }
            throw new JournalException("cannot write record " + record.ritualId() + ": " + e.getMessage(), e);
        }
    }

    private Quarantined quarantine(Path file, String reason) throws IOException {
        Files.createDirectories(quarantine);
        String stamp = Long.toString(System.currentTimeMillis());
        Path dest = quarantine.resolve(file.getFileName() + "." + stamp);
        Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(quarantine.resolve(dest.getFileName() + ".reason.txt"), reason + System.lineSeparator());
        syncDirectoryQuietly(root);
        return new Quarantined(dest.getFileName().toString(), reason);
    }

    private void ensureDirectories() throws JournalException {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new JournalException("cannot create journal directory " + root + ": " + e.getMessage(), e);
        }
    }

    private static void syncDirectory(Path dir) throws IOException {
        if (WINDOWS) return; // NTFS journals metadata; directories cannot be opened for fsync on Windows.
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        }
    }

    private static void syncDirectoryQuietly(Path dir) {
        try {
            syncDirectory(dir);
        } catch (IOException ignored) {
            // Best effort after a non-critical change (temp cleanup / quarantine).
        }
    }
}
