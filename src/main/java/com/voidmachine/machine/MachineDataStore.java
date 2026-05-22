/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.machine;

import com.voidmachine.VoidMachinePlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

/**
 * Loads and saves registered machine locations to {@code machines.yml}.
 *
 * <h3>File layout</h3>
 * <pre>{@code
 * machines:
 *   main_machine:
 *     world: world
 *     x: 100
 *     y: 64
 *     z: -200
 *     profile: default
 *   dark_altar:
 *     world: world_nether
 *     x: 0
 *     y: 48
 *     z: 0
 *     profile: brutal
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * All methods are synchronous and intended to be called from the main thread
 * or from {@code onEnable}/{@code onDisable} (before player connections open).
 * Do not call from async context.
 */
public final class MachineDataStore {

    private static final String FILE_NAME = "machines.yml";

    private final File file;
    private final Logger logger;

    public MachineDataStore(@NotNull VoidMachinePlugin plugin) {
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        this.logger = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    /**
     * Load all machines from disk.
     *
     * <p>Machines referencing worlds that are not currently loaded are skipped
     * with a warning. This is expected when a world is temporarily unloaded;
     * the entry is preserved in the file and will load on the next restart.</p>
     *
     * @return mutable list of successfully loaded machines (may be empty)
     */
    @NotNull
    public List<MachineBlock> load() {
        List<MachineBlock> result = new ArrayList<>();

        if (!file.exists()) {
            return result; // fresh install — no machines registered yet
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection machines = cfg.getConfigurationSection("machines");
        if (machines == null) {
            return result;
        }

        for (String name : machines.getKeys(false)) {
            ConfigurationSection s = machines.getConfigurationSection(name);
            if (s == null) {
                logger.warning("[MachineDataStore] Entry '" + name + "' is not a section — skipping.");
                continue;
            }

            String worldName = s.getString("world");
            if (worldName == null || worldName.isBlank()) {
                logger.warning("[MachineDataStore] Machine '" + name + "' has no world set — skipping.");
                continue;
            }

            // Validate world is currently loaded.
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                logger.warning("[MachineDataStore] Machine '" + name
                        + "' references world '" + worldName
                        + "' which is not loaded — skipping (will retry on next start).");
                continue;
            }

            int x = s.getInt("x", 0);
            int y = s.getInt("y", 64);
            int z = s.getInt("z", 0);
            String profile = s.getString("profile", "default");

            result.add(new MachineBlock(name, worldName, x, y, z, profile));
            logger.info("[MachineDataStore] Loaded machine '" + name
                    + "' at " + worldName + ':' + x + ':' + y + ':' + z
                    + " (profile: " + profile + ')');
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    /**
     * Overwrite {@code machines.yml} with the current set of registered machines.
     *
     * <p>Called from the main thread after any registration or deregistration
     * so the file always reflects the live registry.</p>
     *
     * @param machines the current collection from {@link MachineRegistry#all()}
     */
    public void save(@NotNull Collection<MachineBlock> machines) {
        YamlConfiguration cfg = new YamlConfiguration();

        for (MachineBlock m : machines) {
            String path = "machines." + m.name();
            cfg.set(path + ".world",   m.worldName());
            cfg.set(path + ".x",       m.x());
            cfg.set(path + ".y",       m.y());
            cfg.set(path + ".z",       m.z());
            cfg.set(path + ".profile", m.configProfile());
        }

        try {
            cfg.save(file);
        } catch (IOException e) {
            logger.severe("[MachineDataStore] Failed to save machines.yml: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Returns the location of the backing file (for admin info commands).
     */
    @NotNull
    public File file() {
        return file;
    }
}
