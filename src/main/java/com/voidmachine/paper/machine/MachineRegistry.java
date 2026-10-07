package com.voidmachine.paper.machine;

import com.voidmachine.core.machine.MachineRecord;
import org.bukkit.block.Block;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lookup of registered machines by id, by block and by chunk. Main-thread confined; lookups are O(1).
 */
public final class MachineRegistry {

    private final Map<String, Machine> byId = new LinkedHashMap<>();
    private final Map<String, Machine> byLocation = new HashMap<>();
    /** world name → chunk keys that contain a machine (fast path for chunk/entity events). */
    private final Map<String, Set<Long>> chunks = new HashMap<>();

    public void load(Collection<MachineRecord> records) {
        byId.clear();
        byLocation.clear();
        chunks.clear();
        for (MachineRecord r : records) add(new Machine(r));
    }

    public boolean add(Machine m) {
        if (byId.containsKey(m.id()) || byLocation.containsKey(m.locationKey())) return false;
        byId.put(m.id(), m);
        byLocation.put(m.locationKey(), m);
        chunks.computeIfAbsent(m.record().world(), w -> new HashSet<>()).add(m.record().chunkKey());
        return true;
    }

    public Machine remove(String id) {
        Machine m = byId.remove(id);
        if (m == null) return null;
        byLocation.remove(m.locationKey());
        rebuildChunks();
        return m;
    }

    public void update(Machine m, MachineRecord record) {
        if (!record.locationKey().equals(m.locationKey())) throw new IllegalArgumentException("location cannot change");
        m.setRecord(record);
    }

    public Machine byId(String id) {
        return byId.get(id);
    }

    public Machine at(Block block) {
        return byLocation.get(MachineRecord.key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    public Machine atKey(String locationKey) {
        return byLocation.get(locationKey);
    }

    public boolean hasMachineInChunk(String world, int chunkX, int chunkZ) {
        Set<Long> set = chunks.get(world);
        return set != null && set.contains(MachineRecord.chunkKey(chunkX, chunkZ));
    }

    public Collection<Machine> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }

    private void rebuildChunks() {
        chunks.clear();
        for (Machine m : byId.values()) {
            chunks.computeIfAbsent(m.record().world(), w -> new HashSet<>()).add(m.record().chunkKey());
        }
    }
}
