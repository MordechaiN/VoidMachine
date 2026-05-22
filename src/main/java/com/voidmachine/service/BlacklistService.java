/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.service;

import com.voidmachine.config.PluginConfig;
import com.voidmachine.util.ItemValidator;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pre-flight safety filter for items entering the Void Machine.
 *
 * <p>The service does not look at the player or world; that is the GUI's job.
 * Here we only answer: "Is this stack acceptable to consume?"</p>
 */
public final class BlacklistService {

    private final PluginConfig config;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public BlacklistService(PluginConfig config) {
        this.config = config;
        reload();
    }

    public void reload() {
        snapshot.set(new Snapshot(
                config.blacklistMaterials(),
                config.blacklistMarkedPdcKeys(),
                config.blacklistCustomKeys(),
                config.blockContainers(),
                config.blockBundles(),
                config.blockBlockEntity()
        ));
    }

    /**
     * Returns {@code null} if acceptable, otherwise a short reason key suitable
     * for surfacing through {@code messages.yml}.
     */
    @Nullable
    public String validate(@Nullable ItemStack stack) {
        if (ItemValidator.isEmpty(stack)) return "empty";
        Snapshot snap = snapshot.get();

        Material material = stack.getType();
        if (snap.materials.contains(material)) return "material";
        if (snap.blockContainers && ItemValidator.isContainerMaterial(material)) return "container";
        if (snap.blockBundles && ItemValidator.isNonEmptyBundle(stack)) return "bundle";
        if (snap.blockBlockEntity && ItemValidator.hasBlockEntityState(stack)) return "block-entity";

        for (String key : snap.pdcKeys) {
            if (ItemValidator.hasPdcKey(stack, key)) return "pdc:" + key;
        }
        for (String key : snap.customKeys) {
            if (ItemValidator.hasPdcKey(stack, key)) return "custom:" + key;
        }
        return null;
    }

    /** True if items are below the configured per-insert limit. */
    public boolean isWithinInsertLimit(ItemStack stack) {
        if (ItemValidator.isEmpty(stack)) return false;
        return stack.getAmount() <= config.maxInsertAmount();
    }

    private record Snapshot(
            Set<Material> materials,
            Set<String> pdcKeys,
            Set<String> customKeys,
            boolean blockContainers,
            boolean blockBundles,
            boolean blockBlockEntity
    ) {}
}
