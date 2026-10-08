package com.voidmachine.paper.item;

import com.voidmachine.core.config.Settings;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.Optional;

/**
 * Decides whether an item may be offered. Returns a refusal reason key (a message key suffix) or empty.
 */
public final class OfferingRules {

    public enum Refusal {
        EMPTY("empty"),
        BLOCKED_MATERIAL("blocked"),
        FILLED_CONTAINER("container"),
        UNSTACKABLE("unstackable"),
        MARKED("marked");

        private final String key;

        Refusal(String key) {
            this.key = key;
        }

        /** Message key: {@code offering.refused.<key>}. */
        public String messageKey() {
            return "offering.refused." + key;
        }
    }

    private OfferingRules() {
    }

    public static Optional<Refusal> check(ItemStack item, Settings.Offering rules) {
        if (item == null || item.isEmpty()) return Optional.of(Refusal.EMPTY);
        if (rules.blockedMaterials().contains(item.getType().name())) return Optional.of(Refusal.BLOCKED_MATERIAL);
        if (rules.blockUnstackable() && item.getMaxStackSize() <= 1) return Optional.of(Refusal.UNSTACKABLE);
        if (!item.hasItemMeta()) return Optional.empty();
        ItemMeta meta = item.getItemMeta();
        if (rules.blockFilledContainers() && isFilledContainer(meta)) return Optional.of(Refusal.FILLED_CONTAINER);
        if (!rules.blockedPdcKeys().isEmpty()) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            for (NamespacedKey k : pdc.getKeys()) {
                if (rules.blockedPdcKeys().contains(k.asString())) return Optional.of(Refusal.MARKED);
            }
        }
        return Optional.empty();
    }

    private static boolean isFilledContainer(ItemMeta meta) {
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) return true;
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()) {
            BlockState state = bsm.getBlockState();
            if (state instanceof Container container) {
                for (ItemStack s : container.getSnapshotInventory().getContents()) {
                    if (s != null && !s.isEmpty()) return true;
                }
            }
        }
        return false;
    }
}
