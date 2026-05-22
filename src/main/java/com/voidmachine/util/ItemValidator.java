/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.util;

import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Nullable;

/**
 * Pure, stateless helpers used to decide whether an item is safe to consume.
 *
 * <p>Every check returns either a {@code String} reason or {@code null} when the
 * item is acceptable, so callers can surface a human-readable refusal to the
 * player.</p>
 */
public final class ItemValidator {

    private ItemValidator() {}

    /** Empty (null or AIR) check. */
    public static boolean isEmpty(@Nullable ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    /** True if the item is a container or otherwise has block-entity state. */
    public static boolean hasBlockEntityState(@Nullable ItemStack stack) {
        if (isEmpty(stack)) return false;
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof BlockStateMeta bsm)) return false;
        if (!bsm.hasBlockState()) return false;
        BlockState state = bsm.getBlockState();
        if (state instanceof ShulkerBox) return true;
        return state instanceof BlockInventoryHolder;
    }

    /** True if the item is a bundle holding contents. */
    public static boolean isNonEmptyBundle(@Nullable ItemStack stack) {
        if (isEmpty(stack)) return false;
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof BundleMeta bm)) return false;
        return bm.hasItems();
    }

    /** True if the item has any plugin-owned NamespacedKey marker matching the given key. */
    public static boolean hasPdcKey(@Nullable ItemStack stack, String namespaceKey) {
        if (isEmpty(stack) || namespaceKey == null || namespaceKey.isBlank()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey k : pdc.getKeys()) {
            String composite = k.getNamespace() + ":" + k.getKey();
            if (composite.equalsIgnoreCase(namespaceKey)) return true;
        }
        return false;
    }

    /** Best-effort detection of container-like materials by name pattern. */
    public static boolean isContainerMaterial(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.endsWith("_SHULKER_BOX")
                || name.equals("SHULKER_BOX")
                || name.equals("CHEST")
                || name.equals("TRAPPED_CHEST")
                || name.equals("BARREL")
                || name.equals("BREWING_STAND")
                || name.equals("DROPPER")
                || name.equals("DISPENSER")
                || name.equals("HOPPER");
    }
}
