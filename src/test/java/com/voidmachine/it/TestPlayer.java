package com.voidmachine.it;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;

/**
 * A player whose {@link #saveData()} behaves like Paper's: it writes inventory and persistent data
 * together (here: into a snapshot that stands in for {@code <uuid>.dat}), and a failure is silent.
 * {@link #loadSaved()} restores what a server would read from disk after a crash or restart.
 */
public final class TestPlayer extends PlayerMock {

    private final NamespacedKey ledgerKey;
    private ItemStack[] savedContents = new ItemStack[41];
    private String savedLedger;
    /** When true, saves return normally but nothing reaches "disk" (Paper logs and swallows I/O errors). */
    public boolean saveSilentlyFails;
    public int saves;

    public TestPlayer(ServerMock server, String name, NamespacedKey ledgerKey) {
        this(server, name, UUID.nameUUIDFromBytes(("test-player:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), ledgerKey);
    }

    public TestPlayer(ServerMock server, String name, UUID id, NamespacedKey ledgerKey) {
        super(server, name, id);
        this.ledgerKey = ledgerKey;
    }

    @Override
    public void saveData() {
        saves++;
        if (!saveSilentlyFails) persist();
    }

    /** Writes the current state to the stand-in data file. */
    public void persist() {
        savedContents = copy(getInventory().getContents());
        savedLedger = getPersistentDataContainer().get(ledgerKey, PersistentDataType.STRING);
    }

    /** Replaces the in-memory state with the last saved state (what the server loads on join after a crash). */
    public void loadSaved() {
        getInventory().setContents(copy(savedContents));
        if (savedLedger == null) {
            getPersistentDataContainer().remove(ledgerKey);
        } else {
            getPersistentDataContainer().set(ledgerKey, PersistentDataType.STRING, savedLedger);
        }
    }

    public String ledger() {
        return getPersistentDataContainer().get(ledgerKey, PersistentDataType.STRING);
    }

    public String savedLedger() {
        return savedLedger;
    }

    private static ItemStack[] copy(ItemStack[] in) {
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) out[i] = in[i] == null ? null : in[i].clone();
        return out;
    }
}
