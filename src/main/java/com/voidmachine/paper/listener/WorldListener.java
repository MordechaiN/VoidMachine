package com.voidmachine.paper.listener;

import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.machine.MachineRegistry;
import com.voidmachine.paper.presentation.DisplayService;
import com.voidmachine.paper.ritual.ActiveRitual;
import com.voidmachine.paper.ritual.RitualService;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.logging.Logger;

/**
 * Chunk and world lifecycle. Chunks are never force-loaded: a ritual whose machine unloads is
 * resolved at once (the player receives the sealed verdict); orphaned display entities are swept when
 * a machine chunk's entities load.
 */
public final class WorldListener implements Listener {

    private final MachineRegistry machines;
    private final RitualService rituals;
    private final DisplayService displays;
    private final Logger logger;

    public WorldListener(MachineRegistry machines, RitualService rituals, DisplayService displays, Logger logger) {
        this.machines = machines;
        this.rituals = rituals;
        this.displays = displays;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        Chunk c = event.getChunk();
        if (!machines.hasMachineInChunk(c.getWorld().getName(), c.getX(), c.getZ())) return;
        int removed = displays.sweep(c);
        if (removed > 0) logger.info("Removed " + removed + " orphaned VoidMachine display(s) near a machine in " + c.getWorld().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk c = event.getChunk();
        if (!machines.hasMachineInChunk(c.getWorld().getName(), c.getX(), c.getZ())) return;
        for (ActiveRitual r : rituals.active()) {
            Machine m = r.machine();
            if (m.record().world().equals(c.getWorld().getName()) && (m.record().x() >> 4) == c.getX() && (m.record().z() >> 4) == c.getZ()) {
                rituals.resolveNow(r, "chunk-unloaded");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        String world = event.getWorld().getName();
        for (ActiveRitual r : rituals.active()) {
            if (r.machine().record().world().equals(world)) rituals.resolveNow(r, "world-unloaded");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        long count = machines.all().stream().filter(m -> m.record().world().equals(event.getWorld().getName())).count();
        if (count > 0) logger.info(count + " machine(s) in world '" + event.getWorld().getName() + "' are now awake.");
    }
}
