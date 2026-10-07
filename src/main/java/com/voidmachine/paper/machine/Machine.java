package com.voidmachine.paper.machine;

import com.voidmachine.api.MachineView;
import com.voidmachine.core.machine.MachineRecord;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

/**
 * A registered machine plus its live state. Main-thread confined.
 */
public final class Machine {

    private MachineRecord record;
    private UUID activeRitual;
    private long restingUntil;
    private long lastRitualAt;
    private String lastOutcome = "-";
    private long rituals;

    public Machine(MachineRecord record) {
        this.record = record;
    }

    public MachineRecord record() {
        return record;
    }

    void setRecord(MachineRecord record) {
        this.record = record;
    }

    public String id() {
        return record.id();
    }

    public String locationKey() {
        return record.locationKey();
    }

    /** The world if loaded, otherwise {@code null} (the machine is dormant, not deleted). */
    public World world() {
        return Bukkit.getWorld(record.world());
    }

    /** Block location, or {@code null} while the world is not loaded. */
    public Location location() {
        World w = world();
        return w == null ? null : new Location(w, record.x(), record.y(), record.z());
    }

    /** Centre of the block. */
    public Location center() {
        Location l = location();
        return l == null ? null : l.add(0.5, 0.5, 0.5);
    }

    public boolean isChunkLoaded() {
        World w = world();
        return w != null && w.isChunkLoaded(record.x() >> 4, record.z() >> 4);
    }

    /** Block in the world, only if its chunk is already loaded (never loads chunks). */
    public Block blockIfLoaded() {
        if (!isChunkLoaded()) return null;
        return world().getBlockAt(record.x(), record.y(), record.z());
    }

    public boolean isBusy() {
        return activeRitual != null;
    }

    public UUID activeRitual() {
        return activeRitual;
    }

    public void claim(UUID ritualId) {
        if (activeRitual != null) throw new IllegalStateException("machine " + id() + " is busy");
        activeRitual = ritualId;
    }

    public void release(UUID ritualId, long restMillis, String outcome) {
        if (ritualId.equals(activeRitual)) {
            activeRitual = null;
            lastRitualAt = System.currentTimeMillis();
            restingUntil = lastRitualAt + restMillis;
            if (outcome != null) {
                lastOutcome = outcome;
                rituals++;
            }
        }
    }

    public long restingMillis() {
        return Math.max(0, restingUntil - System.currentTimeMillis());
    }

    public long lastRitualAt() {
        return lastRitualAt;
    }

    public String lastOutcome() {
        return lastOutcome;
    }

    public long ritualsThisSession() {
        return rituals;
    }

    public MachineView view() {
        return new MachineView(record.id(), record.displayName(), record.world(), record.x(), record.y(), record.z(),
                record.profile(), record.enabled());
    }
}
