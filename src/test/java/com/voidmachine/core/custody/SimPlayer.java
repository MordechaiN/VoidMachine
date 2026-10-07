package com.voidmachine.core.custody;

import java.util.Arrays;
import java.util.UUID;

/**
 * A simulated player with two copies of its state, modelling Minecraft exactly as far as item safety
 * is concerned:
 * <ul>
 *   <li><b>memory</b> — what the live server holds;</li>
 *   <li><b>disk</b> — the last {@code <uuid>.dat} that was written. A save replaces it atomically
 *       with the full memory state (inventory and ledger together), or — when {@link #saveFails} is
 *       set — silently does nothing, like Paper's {@code PlayerDataStorage#save} on an I/O error.</li>
 * </ul>
 * {@link #crash()} discards memory and reloads it from disk, exactly like a server crash followed by a join.
 *
 * <p>Items are modelled as one item type ({@link #ITEM}) in 36 storage slots with a stack size of 64.</p>
 */
final class SimPlayer implements CustodyPlayer<String> {

    static final String ITEM = "diamond";
    static final String OTHER = "dirt";
    static final int SLOTS = 36;
    static final int MAX_STACK = 64;

    private final UUID id = UUID.randomUUID();

    private String[] memType = new String[SLOTS];
    private int[] memCount = new int[SLOTS];
    private String memLedger;

    private String[] diskType = new String[SLOTS];
    private int[] diskCount = new int[SLOTS];
    private String diskLedger;

    boolean saveFails;
    /** Called before every operation; lets a test throw {@link Crash} at an exact step. */
    Runnable beforeStep = () -> { };
    int saves;

    SimPlayer setSlot(int slot, String type, int count) {
        memType[slot] = count == 0 ? null : type;
        memCount[slot] = count;
        return this;
    }

    /** Fills every empty slot with junk, then frees exactly {@code free} spaces for {@link #ITEM}. */
    SimPlayer leaveRoom(int free) {
        for (int i = 0; i < SLOTS; i++) {
            if (memType[i] == null) {
                memType[i] = OTHER;
                memCount[i] = MAX_STACK;
            }
        }
        for (int i = SLOTS - 1; i >= 0 && free > 0; i--) {
            if (!OTHER.equals(memType[i])) continue;
            if (free >= MAX_STACK) {
                memType[i] = null;
                memCount[i] = 0;
                free -= MAX_STACK;
            } else {
                memType[i] = ITEM; // a partial stack leaves exactly `free` spaces
                memCount[i] = MAX_STACK - free;
                free = 0;
            }
        }
        return this;
    }

    /** Freeing inventory space, e.g. the player throws away junk. */
    void clearJunk() {
        for (int i = 0; i < SLOTS; i++) {
            if (OTHER.equals(memType[i])) {
                memType[i] = null;
                memCount[i] = 0;
            }
        }
    }

    void forceSave() {
        diskType = memType.clone();
        diskCount = memCount.clone();
        diskLedger = memLedger;
    }

    void crash() {
        memType = diskType.clone();
        memCount = diskCount.clone();
        memLedger = diskLedger;
    }

    int memItems() {
        return count(memType, memCount);
    }

    int diskItems() {
        return count(diskType, diskCount);
    }

    String diskLedger() {
        return diskLedger;
    }

    private static int count(String[] types, int[] counts) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) if (ITEM.equals(types[i])) n += counts[i];
        return n;
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public String name() {
        return "Sim";
    }

    @Override
    public boolean takeFromSlot(int slot, String template, int amount) {
        beforeStep.run();
        if (!template.equals(memType[slot]) || memCount[slot] < amount) return false;
        memCount[slot] -= amount;
        if (memCount[slot] == 0) memType[slot] = null;
        return true;
    }

    @Override
    public int capacityFor(String template) {
        int room = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (memType[i] == null) room += MAX_STACK;
            else if (template.equals(memType[i])) room += MAX_STACK - memCount[i];
        }
        return room;
    }

    @Override
    public int give(String template, int amount) {
        beforeStep.run();
        int left = amount;
        for (int i = 0; i < SLOTS && left > 0; i++) {
            if (template.equals(memType[i]) && memCount[i] < MAX_STACK) {
                int add = Math.min(left, MAX_STACK - memCount[i]);
                memCount[i] += add;
                left -= add;
            }
        }
        for (int i = 0; i < SLOTS && left > 0; i++) {
            if (memType[i] == null) {
                int add = Math.min(left, MAX_STACK);
                memType[i] = template;
                memCount[i] = add;
                left -= add;
            }
        }
        return amount - left;
    }

    @Override
    public String readLedger() {
        return memLedger;
    }

    @Override
    public void writeLedger(String encoded) {
        beforeStep.run();
        memLedger = encoded;
    }

    @Override
    public void save() {
        beforeStep.run();
        saves++;
        if (saveFails) return;
        forceSave();
    }

    @Override
    public String toString() {
        return "SimPlayer{mem=" + memItems() + " disk=" + diskItems() + " ledger=" + memLedger
                + " diskLedger=" + diskLedger + " types=" + Arrays.toString(memType) + '}';
    }

    /** Thrown to simulate the server dying at an exact point. */
    static final class Crash extends RuntimeException {
        Crash() {
            super(null, null, false, false);
        }
    }
}
