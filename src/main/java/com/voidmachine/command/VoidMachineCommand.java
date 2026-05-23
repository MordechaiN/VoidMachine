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
import com.voidmachine.db.GlobalStats;
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
 *   /vm admin create &lt;name&gt; [profile]  — place machine at target block
 *   /vm admin remove &lt;name&gt;           — deregister machine and clear the block
 *   /vm admin list                     — list all registered machines
 *   /vm stats                          — show lifetime server statistics
 *   /vm reload                         — reload config and messages
 * </pre>
 *
 * <p>All admin subcommands require {@code voidmachine.admin} permission.
 * {@code /vm stats} requires {@code voidmachine.stats} (defaults to all players).</p>
 */
public final class VoidMachineCommand implements TabExecutor {

    private final VoidMachinePlugin plugin;
    private final PluginConfig config;
    private final MessageManager messages;
    private final MachineRegistry machineRegistry;
    private final MachineDataStore machineDataStore;
    private final GlobalStats globalStats;

    public VoidMachineCommand(@NotNull VoidMachinePlugin plugin,
                              @NotNull PluginConfig config,
                              @NotNull MessageManager messages,
                              @NotNull MachineRegistry machineRegistry,
                              @NotNull MachineDataStore machineDataStore,
                              @NotNull GlobalStats globalStats) {
        this.plugin           = plugin;
        this.config           = config;
        this.messages         = messages;
        this.machineRegistry  = machineRegistry;
        this.machineDataStore = machineDataStore;
        this.globalStats      = globalStats;
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
        if (args.length >= 1 && args[0].equalsIgnoreCase("stats")) {
            return cmdStats(sender);
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("admin")) {
            return cmdAdmin(sender, args);
        }
        sendHelp(sender);
        return true;
    }

    // =========================================================================
    //  /vm stats
    // =========================================================================

    private boolean cmdStats(@NotNull CommandSender sender) {
        if (!sender.hasPermission("voidmachine.stats")
                && !sender.hasPermission("voidmachine.admin")) {
            sender.sendMessage(messages.render("generic.no-permission"));
            return true;
        }

        GlobalStats.Snapshot s = globalStats.snapshot();
        long total = s.total();

        sender.sendMessage("§8◈ §fVoidMachine §8— §fLifetime Statistics");
        sender.sendMessage("§8─────────────────────────────");
        sender.sendMessage("  §7Sacrifices   §f" + fmt(total));
        sender.sendMessage("§8─────────────────────────────");
        sender.sendMessage("  §7Destroyed    §c" + fmt(s.destroyed())  + pct(s.destroyed(),  total));
        sender.sendMessage("  §7Returned     §f" + fmt(s.returned())   + pct(s.returned(),   total));
        sender.sendMessage("  §7Doubled      §a" + fmt(s.doubled())    + pct(s.doubled(),    total));
        sender.sendMessage("  §7Tripled      §6" + fmt(s.tripled())    + pct(s.tripled(),    total));
        sender.sendMessage("  §7Jackpots     §d" + fmt(s.jackpots())   + pct(s.jackpots(),   total));
        sender.sendMessage("§8─────────────────────────────");
        sender.sendMessage("  §7Items in     §f" + fmt(s.itemsConsumed()));
        sender.sendMessage("  §7Top offering §f" + s.topItem());
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

    /**
     * Place a VoidMachine at the targeted block position and register it.
     *
     * <h3>Flow</h3>
     * <ol>
     *   <li>Admin looks at any solid block within 5 blocks. That block's position
     *       becomes the RESPAWN_ANCHOR machine.</li>
     *   <li>Plugin checks the position is not a container and not already registered.</li>
     *   <li>The target block is replaced with the configured core material.</li>
     *   <li>Machine is registered and saved.</li>
     * </ol>
     */
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

        // Check machine cap.
        if (machineRegistry.size() >= config.machineMaxRegistered()) {
            player.sendMessage("§c[VoidMachine] Machine cap reached ("
                    + config.machineMaxRegistered() + "). Remove a machine first.");
            return true;
        }

        // The block the admin is looking at becomes the machine core.
        Block core = player.getTargetBlockExact(5);
        if (core == null) {
            player.sendMessage("§c[VoidMachine] Look at a block within 5 blocks.");
            player.sendMessage("§7Tip: place any block at the desired position, then run this command.");
            return true;
        }

        // Containers hold items — refuse to overwrite them.
        if (core.getState() instanceof org.bukkit.block.Container) {
            player.sendMessage("§c[VoidMachine] Cannot place machine on a container.");
            return true;
        }

        // Reject overlap with an existing machine.
        if (machineRegistry.atLocation(core.getLocation()) != null) {
            player.sendMessage("§c[VoidMachine] A machine is already registered at this position.");
            return true;
        }

        // Place the machine block and register.
        core.setType(config.machineCoreBlock());

        MachineBlock machine = new MachineBlock(name, core.getLocation(), profile);
        if (!machineRegistry.register(machine)) {
            player.sendMessage("§c[VoidMachine] Registration failed — check console.");
            return true;
        }

        machineDataStore.save(machineRegistry.all());

        player.sendMessage("§a[VoidMachine] Machine '§e" + name + "§a' placed at §7"
                + machine.locationKey() + " §a(profile: §e" + profile + "§a).");
        plugin.getLogger().info("[Admin] " + player.getName() + " placed machine '"
                + name + "' at " + machine.locationKey() + " profile=" + profile);
        return true;
    }

    // ─── /vm admin remove <name> ──────────────────────────────────────────────

    /**
     * Remove a registered machine and restore the core block to AIR.
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

        // Clear the machine block.
        boolean blockCleared = false;
        World world = Bukkit.getWorld(machine.worldName());
        if (world != null) {
            int chunkX = machine.x() >> 4;
            int chunkZ = machine.z() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                world.loadChunk(chunkX, chunkZ);
            }
            Block core = world.getBlockAt(machine.x(), machine.y(), machine.z());
            if (core.getType() == config.machineCoreBlock()) {
                core.setType(Material.AIR);
                blockCleared = true;
            }
        }

        machineRegistry.deregister(machine);
        machineDataStore.save(machineRegistry.all());

        String loc = machine.locationKey();
        if (blockCleared) {
            sender.sendMessage("§a[VoidMachine] Machine '§e" + name
                    + "§a' removed at §7" + loc + "§a.");
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
    //  Formatting helpers
    // =========================================================================

    /** Format a number with thousands separators. */
    private static String fmt(long n) {
        return String.format("%,d", n);
    }

    /** Percentage string, empty when total is zero. */
    private static String pct(long n, long total) {
        if (total == 0) return "";
        return " §8(" + String.format("%.1f%%", (double) n / total * 100) + "§8)";
    }

    // =========================================================================
    //  Help text
    // =========================================================================

    private void sendHelp(@NotNull CommandSender sender) {
        sender.sendMessage("§7[VoidMachine] Commands:");
        sender.sendMessage("  §e/vm admin create <name> [profile] §8— place machine at target block");
        sender.sendMessage("  §e/vm admin remove <name> §8— remove machine and clear the block");
        sender.sendMessage("  §e/vm admin list §8— list all registered machines");
        sender.sendMessage("  §e/vm stats §8— show lifetime server statistics");
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
        if (args.length == 1) {
            List<String> base = new ArrayList<>();
            if (sender.hasPermission("voidmachine.admin")) {
                base.add("admin");
                base.add("reload");
            }
            if (sender.hasPermission("voidmachine.stats")
                    || sender.hasPermission("voidmachine.admin")) {
                base.add("stats");
            }
            return filterPrefix(base, args[0]);
        }
        if (!sender.hasPermission("voidmachine.admin")) return List.of();

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
