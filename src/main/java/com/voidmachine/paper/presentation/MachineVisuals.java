package com.voidmachine.paper.presentation;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * The machine's charge glow, shown with client-side block changes only: the world is never modified,
 * so nothing can be left in a wrong state by a crash. Works for Java and Bedrock (Geyser translates
 * block updates). Only applies when the machine block is a respawn anchor.
 */
public final class MachineVisuals {

    private MachineVisuals() {
    }

    public static void showCharge(Location blockLocation, int level, Collection<Player> receivers, Set<UUID> touched) {
        Block block = blockLocation.getBlock();
        if (block.getType() != Material.RESPAWN_ANCHOR || receivers.isEmpty()) return;
        BlockData data = Material.RESPAWN_ANCHOR.createBlockData(d -> ((RespawnAnchor) d).setCharges(Math.max(0, Math.min(4, level))));
        for (Player p : receivers) {
            p.sendBlockChange(blockLocation, data);
            touched.add(p.getUniqueId());
        }
    }

    /** Sends the real block back to every player who saw a fake state. */
    public static void restore(Location blockLocation, Set<UUID> touched) {
        if (touched.isEmpty() || blockLocation.getWorld() == null) return;
        if (!blockLocation.getWorld().isChunkLoaded(blockLocation.getBlockX() >> 4, blockLocation.getBlockZ() >> 4)) {
            touched.clear();
            return;
        }
        BlockData real = blockLocation.getBlock().getBlockData();
        for (UUID id : touched) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && p.getWorld().equals(blockLocation.getWorld())) p.sendBlockChange(blockLocation, real);
        }
        touched.clear();
    }
}
