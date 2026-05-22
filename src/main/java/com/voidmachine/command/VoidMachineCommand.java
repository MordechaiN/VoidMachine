/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.command;

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.config.MessageManager;
import com.voidmachine.config.PluginConfig;
import com.voidmachine.machine.MachineBlock;
import com.voidmachine.machine.MachineDataStore;
import com.voidmachine.machine.MachineRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Admin command surface for VoidMachine physical machines.
 *
 * <p>Player interaction is exclusively through the physical machine block
 * (right-click). This command class is for server operators only.</p>
 *
 * <h3>Commands</h3>
 * <pre>
 *   /vm admin create &lt;name&gt; [profile]  — register machine at target block
 *   /vm admin remove &lt;name&gt;           — deregister machine
 *   /vm admin list                     — list all registered machines
 *   /vm reload                         — reload config and messages
 * </pre>
 *
 * <p>All admin subcommands require {@code voidmachine.admin} permission.</p>
 */
public final class VoidMachineCommand implements TabExecutor {

    private final VoidMachinePlugin plugin;
    private final PluginConfig config;
    private final MessageManager messages;
    private final MachineRegistry machineRegistry;
    private final MachineDataStore machineDataStore;

    public VoidMachineCommand(@NotNull VoidMachinePlugin plugin,
                              @NotNull PluginConfig config,
                              @NotNull MessageManager messages,
                              @NotNull MachineRegistry machineRegistry,
                              @NotNull MachineDataStore machineDataStore) {
        this.plugin          = plugin;
        this.config          = config;
        this.messages        = messages;
        this.machineRegistry = machineRegistry;
        this.machineDataStore = machineDataStore;
    }

    // =========================================================================
    //  Dispatch
    // =========================================================================

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            return cmdReload(sender);
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("admin")) {
            return cmdAdmin(sender, args);
        }
        sendHelp(sender);
        return true;
    }

    // =========================================================================
    //  /vm admin <subcommand>
    // =========================================================================

    private boolean cmdAdmin(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission("voidmachine.admin")) {
            sender.sendMessage(messages.render("generic.no-permission"));
            return true;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> adminCreate(sender, args);
            case "remove" -> adminRemove(sender, args);
            case "list"   -> adminList(sender);
            default       -> { sendAdminHelp(sender); yield true; }
        };
    }

    // ─── /vm admin create <name> [profile] ───────────────────────────────────

    private boolean adminCreate(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[VoidMachine] Must be in-game to place a machine.");
            return true;
        }
        if (args.length < 3) {
            player.sendMessage("§cUsage: /vm admin create <name> [profile]");
            return true;
        }

        String name    = args[2];
        String profile = args.length >= 4 ? args[3] : "default";

        // Reject duplicate name.
        if (machineRegistry.byName(name) != null) {
            player.sendMessage("§c[VoidMachine] A machine named '§e" + name + "§c' already exists.");
            return true;
        }

        // Target block in front of player (up to 5 blocks).
        Block target = player.getTargetBlockExact(5);
        if (target == null || target.getType() == Material.AIR) {
            player.sendMessage("§c[VoidMachine] Look at a block within 5 blocks.");
            return true;
        }

        // If the block isn't already the configured core type, set it.
        Material coreType = config.machineCoreBlock();
        if (target.getType() != coreType) {
            target.setType(coreType);
        }

        MachineBlock machine = new MachineBlock(name, target.getLocation(), profile);
        if (!machineRegistry.register(machine)) {
            player.sendMessage("§c[VoidMachine] Could not register — duplicate location?");
            return true;
        }

        machineDataStore.save(machineRegistry.all());

        player.sendMessage("§a[VoidMachine] Machine '§e" + name + "§a' created at §7"
                + machine.locationKey() + " §a(profile: §e" + profile + "§a).");
        plugin.getLogger().info("[Admin] " + player.getName() + " created machine '"
                + name + "' at " + machine.locationKey() + " profile=" + profile);
        return true;
    }

    // ─── /vm admin remove <name> ──────────────────────────────────────────────

    /**
     * Remove a registered machine.
     *
     * <p>Order of operations:
     * <ol>
     *   <li>Guard: machine must not be locked (active ritual in progress).</li>
     *   <li>Attempt to clear the physical block in the world (set to AIR).
     *       Runs synchronously on the main thread — safe for a command context.
     *       Only clears if the block is still the configured core material;
     *       if it was already changed externally the block is left as-is.</li>
     *   <li>Deregister from in-memory registry.</li>
     *   <li>Persist registry to disk.</li>
     * </ol>
     * The machine is deregistered and persisted regardless of whether the
     * physical block was cleared (the machine becomes inactive immediately).
     * If the world is unloaded or the chunk cannot be accessed, the admin is
     * warned so they can manually clean up the block.
     */
    private boolean adminRemove(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /vm admin remove <name>");
            return true;
        }
        String name = args[2];
        MachineBlock machine = machineRegistry.byName(name);
        if (machine == null) {
            sender.sendMessage("§c[VoidMachine] No machine named '§e" + name + "§c'.");
            return true;
        }
        if (machine.isLocked()) {
            sender.sendMessage("§c[VoidMachine] Cannot remove '§e" + name
                    + "§c' — a ritual is in progress. Wait for it to finish.");
            return true;
        }

        // ── Clear the physical block ──────────────────────────────────────────
        // Synchronous on main thread. Loads the chunk if needed (brief, acceptable
        // for a rare admin command). Only removes if block is still the core material —
        // prevents destroying a block that was manually changed after registration.
        boolean blockCleared = false;
        World world = Bukkit.getWorld(machine.worldName());
        if (world != null) {
            int chunkX = machine.x() >> 4;
            int chunkZ = machine.z() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                world.loadChunk(chunkX, chunkZ);
            }
            Block block = world.getBlockAt(machine.x(), machine.y(), machine.z());
            if (block.getType() == config.machineCoreBlock()) {
                block.setType(Material.AIR);
                blockCleared = true;
            }
        }

        // ── Deregister and persist ────────────────────────────────────────────
        machineRegistry.deregister(machine);
        machineDataStore.save(machineRegistry.all());

        String loc = machine.locationKey();
        if (blockCleared) {
            sender.sendMessage("§a[VoidMachine] Machine '§e" + name
                    + "§a' removed and block cleared at §7" + loc + "§a.");
        } else {
            sender.sendMessage("§a[VoidMachine] Machine '§e" + name
                    + "§a' removed from registry. §7(Block at " + loc
                    + " was not cleared — world unloaded or block already changed.)");
        }
        plugin.getLogger().info("[Admin] " + sender.getName() + " removed machine '"
                + name + "' at " + loc
                + (blockCleared ? " (block cleared)" : " (block not cleared)") + ".");
        return true;
    }

    // ─── /vm admin list ───────────────────────────────────────────────────────

    private boolean adminList(@NotNull CommandSender sender) {
        var all = machineRegistry.all();
        if (all.isEmpty()) {
            sender.sendMessage("§7[VoidMachine] No machines registered.");
            return true;
        }
        sender.sendMessage("§7[VoidMachine] §fRegistered machines §7(" + all.size() + "):");
        for (MachineBlock m : all) {
            String status = m.isLocked() ? "§cACTIVE" : "§8idle";
            sender.sendMessage("  §e" + m.name()
                    + " §8@ §7" + m.locationKey()
                    + " §8[" + m.configProfile() + "] "
                    + status);
        }
        return true;
    }

    // ─── /vm reload ───────────────────────────────────────────────────────────

    private boolean cmdReload(@NotNull CommandSender sender) {
        if (!sender.hasPermission("voidmachine.admin")) {
            sender.sendMessage(messages.render("generic.no-permission"));
            return true;
        }
        boolean ok = plugin.reloadAll();
        sender.sendMessage(ok
                ? "§a[VoidMachine] Configuration reloaded."
                : "§c[VoidMachine] Reload failed — see console.");
        return true;
    }

    // =========================================================================
    //  Help text
    // =========================================================================

    private void sendHelp(@NotNull CommandSender sender) {
        sender.sendMessage("§7[VoidMachine] Commands:");
        sender.sendMessage("  §e/vm admin create <name> [profile] §8— register machine at looked-at block");
        sender.sendMessage("  §e/vm admin remove <name> §8— remove machine");
        sender.sendMessage("  §e/vm admin list §8— list all registered machines");
        sender.sendMessage("  §e/vm reload §8— reload config and messages");
    }

    private void sendAdminHelp(@NotNull CommandSender sender) {
        sender.sendMessage("§7[VoidMachine] Admin commands:");
        sender.sendMessage("  §e/vm admin create <name> [profile]");
        sender.sendMessage("  §e/vm admin remove <name>");
        sender.sendMessage("  §e/vm admin list");
    }

    // =========================================================================
    //  Tab completion
    // =========================================================================

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                       @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("voidmachine.admin")) return List.of();

        if (args.length == 1) {
            return filterPrefix(List.of("admin", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return filterPrefix(List.of("create", "remove", "list"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            if (args[1].equalsIgnoreCase("remove")) {
                return filterPrefix(
                        machineRegistry.all().stream().map(MachineBlock::name).toList(),
                        args[2]);
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("create")) {
            return filterPrefix(List.of("default", "brutal", "unstable"), args[3]);
        }
        return List.of();
    }

    @NotNull
    private static List<String> filterPrefix(@NotNull List<String> options,
                                             @NotNull String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(s);
        }
        return out;
    }
}
