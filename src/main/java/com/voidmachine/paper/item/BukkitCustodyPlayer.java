package com.voidmachine.paper.item;

import com.voidmachine.core.config.Settings;
import com.voidmachine.core.custody.CustodyPlayer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link CustodyPlayer} over a live Bukkit player. Only the 36 storage slots (hotbar + main
 * inventory) are used; armour and off-hand are never touched.
 */
public final class BukkitCustodyPlayer implements CustodyPlayer<ItemStack> {

    public static final int STORAGE_SLOTS = 36;

    private final Player player;
    private final NamespacedKey ledgerKey;
    private final NamespacedKey rewardKey;
    private final Settings.Overflow overflow;
    private final UUID ritualId;
    private final Logger logger;

    /**
     * @param ritualId tags dropped rewards (overflow = DROP) so they can be traced; may be {@code null}
     */
    public BukkitCustodyPlayer(Player player, NamespacedKey ledgerKey, NamespacedKey rewardKey,
                               Settings.Overflow overflow, UUID ritualId, Logger logger) {
        this.player = player;
        this.ledgerKey = ledgerKey;
        this.rewardKey = rewardKey;
        this.overflow = overflow;
        this.ritualId = ritualId;
        this.logger = logger;
    }

    @Override
    public UUID id() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public boolean takeFromSlot(int slot, ItemStack template, int amount) {
        if (slot < 0 || slot >= STORAGE_SLOTS || amount < 1) return false;
        PlayerInventory inv = player.getInventory();
        ItemStack current = inv.getItem(slot);
        if (current == null || current.isEmpty() || !current.isSimilar(template) || current.getAmount() < amount) {
            return false;
        }
        int left = current.getAmount() - amount;
        if (left == 0) {
            inv.setItem(slot, null);
        } else {
            ItemStack rest = current.clone();
            rest.setAmount(left);
            inv.setItem(slot, rest);
        }
        return true;
    }

    @Override
    public int capacityFor(ItemStack template) {
        if (overflow == Settings.Overflow.DROP) return Integer.MAX_VALUE;
        return roomFor(player.getInventory(), template);
    }

    /** Free space for {@code template} in the storage slots. */
    public static int roomFor(PlayerInventory inv, ItemStack template) {
        int max = Math.min(template.getMaxStackSize(), inv.getMaxStackSize());
        ItemStack[] storage = inv.getStorageContents();
        long room = 0;
        for (int i = 0; i < Math.min(STORAGE_SLOTS, storage.length); i++) {
            ItemStack s = storage[i];
            if (s == null || s.isEmpty()) room += max;
            else if (s.isSimilar(template) && s.getAmount() < max) room += max - s.getAmount();
        }
        return (int) Math.min(Integer.MAX_VALUE, room);
    }

    /**
     * Places items into the 36 storage slots only (similar stacks first, then empty slots), exactly the
     * space {@link #roomFor} counts — never into armour or off-hand slots.
     */
    @Override
    public int give(ItemStack template, int amount) {
        PlayerInventory inv = player.getInventory();
        int max = Math.max(1, Math.min(template.getMaxStackSize(), inv.getMaxStackSize()));
        int left = amount;
        for (int i = 0; i < STORAGE_SLOTS && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.isEmpty() || !s.isSimilar(template) || s.getAmount() >= max) continue;
            int add = Math.min(left, max - s.getAmount());
            inv.setItem(i, s.asQuantity(s.getAmount() + add));
            left -= add;
        }
        for (int i = 0; i < STORAGE_SLOTS && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && !s.isEmpty()) continue;
            int add = Math.min(left, max);
            inv.setItem(i, template.asQuantity(add));
            left -= add;
        }
        if (left > 0 && overflow == Settings.Overflow.DROP) left -= dropLocked(template, left);
        return amount - left;
    }

    /**
     * Drops at the player's feet, locked to them, never despawning, immune to fire, lava and mobs.
     * Returns how many were dropped: a batch that fails to spawn is not counted, so it stays owed.
     */
    private int dropLocked(ItemStack template, int amount) {
        Location at = player.getLocation();
        int max = Math.max(1, template.getMaxStackSize());
        int dropped = 0;
        while (dropped < amount) {
            int batch = Math.min(amount - dropped, max);
            try {
                player.getWorld().dropItem(at, template.asQuantity(batch), (Item drop) -> {
                    drop.setOwner(player.getUniqueId());
                    drop.setThrower(player.getUniqueId());
                    drop.setUnlimitedLifetime(true);
                    drop.setCanMobPickup(false);
                    drop.setInvulnerable(true);
                    drop.setPickupDelay(0);
                    if (ritualId != null) {
                        drop.getPersistentDataContainer().set(rewardKey, PersistentDataType.STRING, ritualId.toString());
                    }
                });
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not drop a reward for " + player.getName() + "; " + (amount - dropped)
                        + " item(s) stay held and are retried", e);
                break;
            }
            dropped += batch;
        }
        return dropped;
    }

    @Override
    public String readLedger() {
        return player.getPersistentDataContainer().get(ledgerKey, PersistentDataType.STRING);
    }

    @Override
    public void writeLedger(String encoded) {
        if (encoded == null) {
            player.getPersistentDataContainer().remove(ledgerKey);
        } else {
            player.getPersistentDataContainer().set(ledgerKey, PersistentDataType.STRING, encoded);
        }
    }

    @Override
    public void save() {
        player.saveData();
    }
}
