/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.gui;

import com.voidmachine.config.MessageManager;
import com.voidmachine.config.PluginConfig;
import com.voidmachine.core.Transaction;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A single player's Void Machine GUI. Implements {@link InventoryHolder} so we
 * can cleanly distinguish our inventory in event listeners without relying on
 * the inventory title (which can be spoofed).
 */
public final class MachineGui implements InventoryHolder {

    private final UUID ownerId;
    private final PluginConfig config;
    private final Inventory inventory;
    private final int sacrificeSlot;
    private final int activateSlot;

    private volatile boolean locked;
    private @Nullable Transaction transaction;
    private @Nullable BukkitTask animationTask;

    public MachineGui(Player owner, PluginConfig config, MessageManager messages) {
        this.ownerId = owner.getUniqueId();
        this.config = config;
        this.sacrificeSlot = config.guiSacrificeSlot();
        this.activateSlot = config.guiActivateSlot();
        Component title = messages.mm().deserialize(config.guiTitle());
        this.inventory = Bukkit.createInventory(this, config.guiRows() * 9, title);
        decorate(messages);
    }

    private void decorate(MessageManager messages) {
        ItemStack filler = filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot == sacrificeSlot || slot == activateSlot) continue;
            inventory.setItem(slot, filler);
        }
        inventory.setItem(activateSlot, activateButton(messages));
    }

    private ItemStack filler() {
        ItemStack stack = new ItemStack(config.guiFiller());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack activateButton(MessageManager messages) {
        Material material = config.guiActivateButton();
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.render("gui.activate-button.name"));
            meta.lore(messages.renderList("gui.activate-button.lore"));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public int sacrificeSlot() {
        return sacrificeSlot;
    }

    public int activateSlot() {
        return activateSlot;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public @Nullable Transaction transaction() {
        return transaction;
    }

    public void attach(Transaction transaction) {
        this.transaction = transaction;
    }

    public void detach() {
        this.transaction = null;
    }

    public @Nullable BukkitTask animationTask() {
        return animationTask;
    }

    public void setAnimationTask(@Nullable BukkitTask task) {
        if (animationTask != null && !animationTask.isCancelled()) {
            animationTask.cancel();
        }
        this.animationTask = task;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
