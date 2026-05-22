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

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.config.MessageManager;
import com.voidmachine.config.PluginConfig;
import com.voidmachine.core.Outcome;
import com.voidmachine.core.Transaction;
import com.voidmachine.service.BlacklistService;
import com.voidmachine.service.CooldownService;
import com.voidmachine.service.ProcessingService;
import com.voidmachine.util.Effects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns active {@link MachineGui} sessions and routes inventory events to them.
 *
 * <p>The manager is the central choke point for anti-dupe logic. Every
 * {@link InventoryClickEvent} routed here is examined against the session's
 * state (locked / unlocked, allowed slots, allowed click types).</p>
 */
public final class GuiManager {

    private final VoidMachinePlugin plugin;
    private final PluginConfig config;
    private final MessageManager messages;
    private final BlacklistService blacklist;
    private final CooldownService cooldowns;
    private final ProcessingService processing;
    private final AnimationRunner animations;
    private final Effects effects;

    private final Map<UUID, MachineGui> sessions = new ConcurrentHashMap<>();

    public GuiManager(VoidMachinePlugin plugin,
                      PluginConfig config,
                      MessageManager messages,
                      BlacklistService blacklist,
                      CooldownService cooldowns,
                      ProcessingService processing,
                      Effects effects) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.blacklist = blacklist;
        this.cooldowns = cooldowns;
        this.processing = processing;
        this.effects = effects;
        this.animations = new AnimationRunner(plugin, config, effects);
    }

    public void open(Player player) {
        if (!config.isWorldAllowed(player.getWorld().getName())) {
            player.sendMessage(messages.render("generic.world-blocked"));
            return;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            player.sendMessage(messages.render("machine.already-in-session"));
            return;
        }
        Duration remaining = cooldowns.remainingCooldown(player);
        if (!remaining.isZero()) {
            if (cooldowns.shouldNotifyOnBlock()) {
                player.sendMessage(messages.render("machine.cooldown",
                        Placeholder.parsed("cooldown", formatDuration(remaining))));
            }
            return;
        }
        int max = config.maxConcurrentSessions();
        if (max > 0 && sessions.size() >= max) {
            player.sendMessage(messages.render("machine.global-cooldown",
                    Placeholder.parsed("cooldown", "60s")));
            return;
        }
        cooldowns.checkDailyLimit(player).thenAccept(ok -> {
            if (!ok) {
                player.sendMessage(messages.render("machine.daily-limit"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> openInternal(player));
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Daily-limit check failed: " + ex);
            Bukkit.getScheduler().runTask(plugin, () -> openInternal(player));
            return null;
        });
    }

    private void openInternal(Player player) {
        if (sessions.containsKey(player.getUniqueId())) return;
        MachineGui gui = new MachineGui(player, config, messages);
        sessions.put(player.getUniqueId(), gui);
        player.openInventory(gui.getInventory());
        player.sendMessage(messages.render("machine.open"));
        effects.play(player, "open");
    }

    public void shutdown() {
        // Snapshot first: closeInventory() fires events that modify the map.
        java.util.List<MachineGui> snapshot = new java.util.ArrayList<>(sessions.values());
        for (MachineGui gui : snapshot) {
            Player player = Bukkit.getPlayer(gui.ownerId());
            if (player != null) {
                player.closeInventory();
            }
            cleanup(gui);
        }
        sessions.clear();
    }

    public boolean isOurInventory(@Nullable Inventory inv) {
        if (inv == null) return false;
        InventoryHolder holder = inv.getHolder();
        return holder instanceof MachineGui;
    }

    @Nullable
    public MachineGui session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MachineGui gui)) return;
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        if (!gui.ownerId().equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        // While locked, drop everything to prevent dupe via concurrent edits.
        if (gui.isLocked()) {
            if (config.dropClicksDuringLock()) {
                event.setCancelled(true);
                event.setResult(org.bukkit.event.Event.Result.DENY);
            } else {
                event.setCancelled(true);
            }
            return;
        }

        int clickedSlot = event.getRawSlot();
        boolean clickedTop = clickedSlot < event.getView().getTopInventory().getSize();

        // Block all shift-click activity that targets the top inventory.
        if (event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }
        // Block number-key hotbar swaps targeting top inventory.
        if (event.getClick() == ClickType.NUMBER_KEY && clickedTop) {
            event.setCancelled(true);
            return;
        }
        // Block off-hand swap to/from machine slots.
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
            return;
        }
        // Block double-click collect from top inventory.
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        if (clickedTop) {
            handleTopClick(event, gui, player);
        }
        // Bottom-inventory clicks are allowed (player rearranging their own inv).
    }

    private void handleTopClick(InventoryClickEvent event, MachineGui gui, Player player) {
        int slot = event.getRawSlot();
        if (slot == gui.activateSlot()) {
            event.setCancelled(true);
            attemptActivate(gui, player);
            return;
        }
        if (slot != gui.sacrificeSlot()) {
            event.setCancelled(true);
            return;
        }
        // Sacrifice slot: only the player's cursor item or the held stack may go in.
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT) {
            ItemStack candidate = cursor != null ? cursor : current;
            String reject = candidate == null || candidate.getType().isAir()
                    ? null
                    : blacklist.validate(candidate);
            if (reject != null) {
                event.setCancelled(true);
                player.sendMessage(messages.render("machine.blacklisted"));
                effects.play(player, "tick");
                return;
            }
        } else {
            // Other click types are too risky in the sacrifice slot — refuse.
            event.setCancelled(true);
        }
    }

    public void handleDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof MachineGui gui)) return;
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw >= topSize) continue;
            if (gui.isLocked()) {
                event.setCancelled(true);
                return;
            }
            if (raw != gui.sacrificeSlot()) {
                event.setCancelled(true);
                return;
            }
            if (event.getRawSlots().size() > 1) {
                // Multi-slot drag into our window is always cancelled.
                event.setCancelled(true);
                return;
            }
        }
    }

    public void handleClose(Player player, Inventory inventory) {
        if (!(inventory.getHolder() instanceof MachineGui gui)) return;
        // If the player closed mid-animation we still return the items as a safety net.
        if (gui.isLocked() && config.guiReturnOnDisconnect()) {
            Transaction tx = gui.transaction();
            if (tx != null) {
                processing.abort(tx);
            }
        } else {
            // Return any item the player placed in the sacrifice slot but didn't activate.
            ItemStack pending = inventory.getItem(gui.sacrificeSlot());
            if (pending != null && !pending.getType().isAir()) {
                returnItem(player, pending);
                inventory.setItem(gui.sacrificeSlot(), null);
            }
        }
        cleanup(gui);
        sessions.remove(gui.ownerId());
    }

    public void handleQuit(Player player) {
        MachineGui gui = sessions.remove(player.getUniqueId());
        if (gui == null) return;
        Transaction tx = gui.transaction();
        if (tx != null) {
            processing.abort(tx);
        } else {
            ItemStack pending = gui.getInventory().getItem(gui.sacrificeSlot());
            if (pending != null && !pending.getType().isAir()) {
                returnItem(player, pending);
            }
        }
        cleanup(gui);
    }

    private void cleanup(MachineGui gui) {
        gui.setAnimationTask(null);
        gui.detach();
    }

    private void attemptActivate(MachineGui gui, Player player) {
        ItemStack sacrifice = gui.getInventory().getItem(gui.sacrificeSlot());
        if (sacrifice == null || sacrifice.getType().isAir() || sacrifice.getAmount() <= 0) {
            player.sendMessage(messages.render("machine.empty-sacrifice"));
            effects.play(player, "tick");
            return;
        }
        String reject = blacklist.validate(sacrifice);
        if (reject != null) {
            player.sendMessage(messages.render("machine.blacklisted"));
            return;
        }
        if (!blacklist.isWithinInsertLimit(sacrifice)) {
            player.sendMessage(messages.render("machine.blacklisted"));
            return;
        }
        if (!hasRoomForReturn(player, sacrifice)) {
            // Soft check: even on a miss we may return the same stack.
            player.sendMessage(messages.render("machine.full-inventory"));
            return;
        }

        Transaction tx = processing.openTransaction(player);
        tx.capture(sacrifice.clone());
        gui.attach(tx);
        gui.setLocked(true);

        // Atomically vacate the sacrifice slot. Until this returns the inventory
        // sees the original; afterwards only the Transaction holds the items.
        gui.getInventory().setItem(gui.sacrificeSlot(), null);
        tx.markProcessing();
        effects.play(player, "activate");

        gui.setAnimationTask(animations.run(player, gui.getInventory(), gui.sacrificeSlot(), () ->
                onAnimationComplete(player, gui, tx)));
    }

    private void onAnimationComplete(Player player, MachineGui gui, Transaction tx) {
        if (!player.isOnline()) {
            processing.abort(tx);
            cleanup(gui);
            sessions.remove(gui.ownerId());
            return;
        }
        try {
            Outcome outcome = processing.resolveOutcome(tx);
            // Show the sacrifice stack as the "result" in the sacrifice slot so the
            // player has a beat to register what happened before the inventory closes.
            ItemStack reveal = tx.copyOfSacrificeWithAmount(tx.outputAmount());
            if (outcome != Outcome.DESTROYED) {
                gui.getInventory().setItem(gui.sacrificeSlot(), reveal);
            } else {
                gui.getInventory().setItem(gui.sacrificeSlot(), null);
            }
            // Close inventory after a short pause; deliver items into player inv directly.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.closeInventory();
                }
                processing.deliver(player, tx);
                cooldowns.apply(player);
                cooldowns.recordUse(player);
            }, 18L);
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to deliver outcome: " + ex);
            ex.printStackTrace();
            processing.abort(tx);
        } finally {
            gui.setLocked(false);
        }
    }

    private boolean hasRoomForReturn(Player player, ItemStack stack) {
        ItemStack probe = stack.clone();
        probe.setAmount(Math.min(probe.getMaxStackSize(), Math.max(1, stack.getAmount())));
        Map<Integer, ItemStack> dry = new HashMap<>();
        // Use a copy of the inventory to avoid mutating the real one.
        Inventory copy = Bukkit.createInventory(null, player.getInventory().getStorageContents().length);
        copy.setContents(player.getInventory().getStorageContents());
        dry.putAll(copy.addItem(probe));
        return dry.isEmpty();
    }

    private void returnItem(Player player, ItemStack stack) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
        if (!overflow.isEmpty()) {
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    private static String formatDuration(Duration d) {
        long secs = d.toSeconds();
        if (secs >= 60) {
            long m = secs / 60;
            long s = secs % 60;
            return m + "m " + s + "s";
        }
        return secs + "s";
    }

    @SuppressWarnings("unused")
    private static Component empty() {
        return Component.empty();
    }

    @SuppressWarnings("unused")
    private static TagResolver placeholder(String name, String value) {
        return Placeholder.parsed(name, value);
    }
}
