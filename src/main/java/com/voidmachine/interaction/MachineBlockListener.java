/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.interaction;

import com.voidmachine.config.PluginConfig;
import com.voidmachine.machine.MachineBlock;
import com.voidmachine.machine.MachineRegistry;
import com.voidmachine.transaction.TransactionRegistry;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Protects the registered VoidMachine core block from all forms of destruction
 * and aborts active transactions when a machine's chunk is unloaded.
 *
 * <h3>What is protected</h3>
 * The single RESPAWN_ANCHOR core block registered as the machine.
 * No multiblock structure — the machine is a single iconic block.
 *
 * <h3>Protection mechanisms</h3>
 * <ul>
 *   <li>Player break (survival &amp; creative) — always cancelled.</li>
 *   <li>Entity explosions (creeper, TNT, etc.) — block removed from explosion list.</li>
 *   <li>Block explosions (primed TNT) — same.</li>
 *   <li>Piston push/pull — event cancelled if the core block is in the move list.</li>
 *   <li>Liquid flow — event cancelled if the destination is the core block.</li>
 * </ul>
 * Machines may <strong>only</strong> be removed via {@code /vm admin remove <name>}.
 */
public final class MachineBlockListener implements Listener {

    private final PluginConfig config;
    private final MachineRegistry machineRegistry;
    private final TransactionRegistry txRegistry;
    private final ItemCaptureService captureService;
    private final Logger logger;

    public MachineBlockListener(@NotNull PluginConfig config,
                                @NotNull MachineRegistry machineRegistry,
                                @NotNull TransactionRegistry txRegistry,
                                @NotNull ItemCaptureService captureService,
                                @NotNull Logger logger) {
        this.config          = config;
        this.machineRegistry = machineRegistry;
        this.txRegistry      = txRegistry;
        this.captureService  = captureService;
        this.logger          = logger;
    }

    // =========================================================================
    //  Block break
    // =========================================================================

    /**
     * Prevent ALL player block-break attempts on registered machine blocks.
     * Machines may only be removed via {@code /vm admin remove <name>}.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isMachineBlock(block)) return;

        MachineBlock machine = machineRegistry.atLocation(block.getLocation());
        event.setCancelled(true);
        event.getPlayer().sendMessage(
                "§7[VoidMachine] §cThis is a registered machine. Use §e/vm admin remove "
                + machine.name() + " §cto remove it.");
    }

    // ── Piston protection ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(@NotNull BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (isMachineBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(@NotNull BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (isMachineBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ── Liquid flow protection ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(@NotNull BlockFromToEvent event) {
        Block to = event.getToBlock();
        if (isMachineBlock(to)) {
            event.setCancelled(true);
        }
    }

    // =========================================================================
    //  Explosion protection
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(@NotNull EntityExplodeEvent event) {
        removeRegisteredBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(@NotNull BlockExplodeEvent event) {
        removeRegisteredBlocks(event.blockList());
    }

    // =========================================================================
    //  Chunk unload — abort active transactions on machines in the chunk
    // =========================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(@NotNull ChunkUnloadEvent event) {
        for (UUID playerUuid : txRegistry.snapshot().stream()
                .map(tx -> tx.playerId())
                .toList()) {

            var tx = txRegistry.get(playerUuid);
            if (tx == null) continue;

            MachineBlock machine = machineRegistry.atKey(tx.machineLoc());
            if (machine == null) continue;

            int machChunkX = machine.x() >> 4;
            int machChunkZ = machine.z() >> 4;
            boolean sameWorld = machine.worldName().equals(
                    event.getChunk().getWorld().getName());

            if (sameWorld
                    && machChunkX == event.getChunk().getX()
                    && machChunkZ == event.getChunk().getZ()) {

                logger.warning("[MachineBlock] Chunk unloading with active transaction for "
                        + tx.playerName() + " at " + machine.locationKey()
                        + " — force-aborting.");
                captureService.abortTransaction(playerUuid, "chunk_unloaded");
            }
        }
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /**
     * Remove registered machine blocks from an explosion block list.
     */
    private void removeRegisteredBlocks(@NotNull List<Block> blockList) {
        if (blockList.isEmpty()) return;
        Iterator<Block> it = blockList.iterator();
        while (it.hasNext()) {
            if (isMachineBlock(it.next())) it.remove();
        }
    }

    /**
     * Returns {@code true} if this block is a registered machine core block.
     * Type check first (fast path), then registry lookup.
     */
    private boolean isMachineBlock(@NotNull Block block) {
        if (block.getType() != config.machineCoreBlock()) return false;
        return machineRegistry.atLocation(block.getLocation()) != null;
    }
}
