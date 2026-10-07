package com.voidmachine.paper.machine;

import com.voidmachine.core.config.ConfigProblem;
import com.voidmachine.core.machine.MachineFile;
import com.voidmachine.core.machine.MachineRecord;
import com.voidmachine.paper.config.Yamls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Persists machines to {@code machines.yml}. Writes are atomic (temp file + rename). Entries that
 * cannot be read are written back untouched; machines whose world is not loaded are kept.
 * A V1 file is backed up before it is first rewritten in the V2 layout.
 */
public final class MachineStore {

    private final Path file;
    private final Path backupDir;
    private final Logger logger;
    private Map<String, Object> unreadable = Map.of();

    public MachineStore(Path file, Path backupDir, Logger logger) {
        this.file = file;
        this.backupDir = backupDir;
        this.logger = logger;
    }

    public record Loaded(List<MachineRecord> machines, List<ConfigProblem> problems, boolean migratedFromV1) {
    }

    public Loaded load() throws IOException {
        if (!Files.exists(file)) return new Loaded(List.of(), List.of(), false);
        Map<String, Object> tree = Yamls.read(file);
        MachineFile.Result r = MachineFile.read(tree);
        this.unreadable = r.unreadable();
        if (r.wasV1()) {
            Files.createDirectories(backupDir);
            Path backup = backupDir.resolve("machines.yml");
            if (!Files.exists(backup)) Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
            save(r.machines());
            logger.info("machines.yml converted to the V2 layout (" + r.machines().size() + " machines); V1 file kept in "
                    + backupDir.getFileName() + "/");
        }
        return new Loaded(r.machines(), r.problems(), r.wasV1());
    }

    public void save(List<MachineRecord> machines) throws IOException {
        Yamls.writeAtomically(file, MachineFile.write(new ArrayList<>(machines), unreadable),
                "VoidMachine machines. Manage with /vm admin create|remove|enable|disable|profile.");
    }
}
