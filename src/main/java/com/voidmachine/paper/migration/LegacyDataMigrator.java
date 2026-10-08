package com.voidmachine.paper.migration;

import com.voidmachine.core.journal.FileJournal;
import com.voidmachine.core.journal.JournalException;
import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.migration.V1Checkpoint;
import com.voidmachine.core.stats.StatsBook;
import com.voidmachine.paper.config.Yamls;
import com.voidmachine.paper.item.ItemCodec;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Converts V1 item-holding data into V2 journal records, so nothing a V1 server owed is lost:
 *
 * <ul>
 *   <li>{@code checkpoints/tx-*.dat} in CAPTURED/ANIMATING state (item taken, outcome never saved)
 *       become CLAIM records returning the offering — exactly what V1 recovery would have done;</li>
 *   <li>DELIVERING checkpoints (possibly partially delivered by V1) become REVIEW records: never paid
 *       automatically, an admin decides;</li>
 *   <li>{@code pending_deliveries.yml} entries become CLAIM records (or REVIEW for V1's admin-review entries).</li>
 * </ul>
 *
 * Record ids are derived from the source entry, so a migration interrupted by a crash can be re-run
 * without creating duplicate claims. Source files are moved to {@code backup/v1/} only after every
 * record is durable.
 */
public final class LegacyDataMigrator {

    private final Path dataDir;
    private final Path backupDir;
    private final FileJournal journal;
    private final Logger logger;

    public LegacyDataMigrator(Path dataDir, Path backupDir, FileJournal journal, Logger logger) {
        this.dataDir = dataDir;
        this.backupDir = backupDir;
        this.journal = journal;
        this.logger = logger;
    }

    public record Report(int claims, int reviews, int unreadable, List<String> notes) {
        public boolean any() {
            return claims + reviews + unreadable > 0 || !notes.isEmpty();
        }
    }

    public Report run(StatsBook stats) {
        int claims = 0;
        int reviews = 0;
        int unreadable = 0;
        List<String> notes = new ArrayList<>();
        Path marker = dataDir.resolve(".v1-migrated");
        boolean hasLegacy = Files.isDirectory(dataDir.resolve("checkpoints")) || Files.exists(dataDir.resolve("pending_deliveries.yml"))
                || Files.exists(dataDir.resolve("global_stats.yml"));
        if (!hasLegacy) return new Report(0, 0, 0, notes);
        if (Files.exists(marker)) {
            // Converted once already. Converting again could recreate claims that were since paid.
            notes.add("V1 data files are still present but were already migrated; they are ignored. Move them out of plugins/VoidMachine/.");
            return new Report(0, 0, 0, notes);
        }
        boolean journalFailed = false;

        Path checkpoints = dataDir.resolve("checkpoints");
        if (Files.isDirectory(checkpoints)) {
            boolean allOk = true;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(checkpoints, "tx-*.dat")) {
                for (Path f : files) {
                    try {
                        V1Checkpoint cp = V1Checkpoint.parse(Files.readAllBytes(f));
                        ItemStack stack = ItemStack.deserializeBytes(cp.itemBytes());
                        UUID id = UUID.nameUUIDFromBytes(("v1-checkpoint:" + f.getFileName()).getBytes(StandardCharsets.UTF_8));
                        JournalRecord r = cp.needsAdminDecision()
                                ? JournalRecord.review(id, cp.playerId(), cp.playerName(), cp.capturedAt(), ItemCodec.key(stack),
                                ItemCodec.encodeTemplate(stack), stack.getAmount(),
                                "v1-delivering;v1-outcome=" + cp.outcomeName() + ";v1-output=" + cp.outputAmount())
                                : JournalRecord.claim(id, cp.playerId(), cp.playerName(), cp.capturedAt(), ItemCodec.key(stack),
                                ItemCodec.encodeTemplate(stack), stack.getAmount(), "v1-interrupted-ritual-refund");
                        if (createIdempotent(r)) {
                            if (cp.needsAdminDecision()) reviews++;
                            else claims++;
                        }
                    } catch (IOException | RuntimeException e) {
                        unreadable++;
                        allOk = false;
                        notes.add("V1 checkpoint " + f.getFileName() + " could not be converted (" + e.getMessage()
                                + "); it stays in plugins/VoidMachine/checkpoints/ for manual review");
                    } catch (JournalException e) {
                        allOk = false;
                        journalFailed = true;
                        notes.add("journal write failed while migrating " + f.getFileName() + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                notes.add("cannot read V1 checkpoints: " + e.getMessage());
                allOk = false;
            }
            if (allOk) moveToBackup(checkpoints, "checkpoints", notes);
        }

        Path pending = dataDir.resolve("pending_deliveries.yml");
        if (Files.exists(pending)) {
            boolean allOk = true;
            try {
                Map<String, Object> tree = Yamls.read(pending);
                Object root = tree.get("pending");
                if (root instanceof Map<?, ?> players) {
                    for (Map.Entry<?, ?> pe : players.entrySet()) {
                        UUID player;
                        try {
                            player = UUID.fromString(String.valueOf(pe.getKey()));
                        } catch (IllegalArgumentException e) {
                            unreadable++;
                            allOk = false;
                            continue;
                        }
                        if (!(pe.getValue() instanceof List<?> entries)) continue;
                        int index = 0;
                        for (Object o : entries) {
                            index++;
                            if (!(o instanceof Map<?, ?> m)) continue;
                            try {
                                ItemStack stack = ItemStack.deserializeBytes(Base64.getDecoder().decode(String.valueOf(m.get("item"))));
                                String reason = String.valueOf(m.get("reason"));
                                String name = String.valueOf(m.get("name"));
                                long epoch = m.get("epoch") instanceof Number n ? n.longValue() : 0L;
                                UUID id = UUID.nameUUIDFromBytes(("v1-pending:" + player + ":" + index + ":" + epoch).getBytes(StandardCharsets.UTF_8));
                                JournalRecord r = reason.startsWith("DELIVERING_STATE_ADMIN_REVIEW")
                                        ? JournalRecord.review(id, player, name, epoch, ItemCodec.key(stack), ItemCodec.encodeTemplate(stack),
                                        stack.getAmount(), "v1-pending;" + reason)
                                        : JournalRecord.claim(id, player, name, epoch, ItemCodec.key(stack), ItemCodec.encodeTemplate(stack),
                                        stack.getAmount(), "v1-pending;" + reason);
                                if (createIdempotent(r)) {
                                    if (r.kind() == com.voidmachine.core.journal.RecordKind.REVIEW) reviews++;
                                    else claims++;
                                }
                            } catch (RuntimeException e) {
                                unreadable++;
                                allOk = false;
                                notes.add("a V1 pending delivery for " + player + " could not be converted (" + e.getMessage() + ")");
                            } catch (JournalException e) {
                                allOk = false;
                                journalFailed = true;
                                notes.add("journal write failed while migrating pending deliveries: " + e.getMessage());
                            }
                        }
                    }
                }
            } catch (IOException e) {
                notes.add("pending_deliveries.yml could not be read: " + e.getMessage());
                allOk = false;
            }
            if (allOk) moveToBackup(pending, "pending_deliveries.yml", notes);
        }

        if (journalFailed) {
            notes.add("V1 migration is incomplete because the journal could not be written; it is retried at the next start.");
            return new Report(claims, reviews, unreadable, notes);
        }
        Path globalStats = dataDir.resolve("global_stats.yml");
        if (Files.exists(globalStats)) {
            try {
                Map<String, Object> g = Yamls.read(globalStats);
                Map<String, Long> outcomes = new LinkedHashMap<>();
                outcomes.put("consumed", num(g.get("destroyed")));
                outcomes.put("returned", num(g.get("returned")));
                outcomes.put("doubled", num(g.get("doubled")));
                outcomes.put("tripled", num(g.get("tripled")));
                outcomes.put("jackpot", num(g.get("jackpots")));
                stats.importLegacy(num(g.get("total-sacrifices")), outcomes, num(g.get("items-consumed")));
                moveToBackup(globalStats, "global_stats.yml", notes);
                notes.add("V1 lifetime statistics were imported.");
            } catch (IOException e) {
                notes.add("global_stats.yml could not be imported: " + e.getMessage());
            }
        }
        try {
            Files.writeString(marker, "V1 data migrated at " + java.time.Instant.now() + System.lineSeparator());
        } catch (IOException e) {
            notes.add("could not write the migration marker (" + e.getMessage() + "); move remaining V1 files out of the plugin folder");
        }
        if (claims + reviews > 0) {
            notes.add("Converted " + claims + " V1 owed item stack(s) into claims (paid automatically on the player's next join) and "
                    + reviews + " ambiguous V1 record(s) into admin reviews (see /vm admin pending).");
        }
        return new Report(claims, reviews, unreadable, notes);
    }

    private boolean createIdempotent(JournalRecord r) throws JournalException {
        try {
            journal.create(r);
            return true;
        } catch (JournalException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) return false;
            throw e;
        }
    }

    private void moveToBackup(Path source, String name, List<String> notes) {
        try {
            Files.createDirectories(backupDir);
            Path dest = backupDir.resolve(name);
            if (Files.exists(dest)) dest = backupDir.resolve(name + "." + System.currentTimeMillis());
            Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            notes.add("could not move " + name + " to backup/v1/ (" + e.getMessage() + "); it is ignored from now on");
            logger.warning("Legacy file " + name + " could not be moved: " + e.getMessage());
        }
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
