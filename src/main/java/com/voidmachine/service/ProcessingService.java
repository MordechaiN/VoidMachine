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

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.config.MessageManager;
import com.voidmachine.config.PluginConfig;
import com.voidmachine.core.Outcome;
import com.voidmachine.core.OutcomeRoller;
import com.voidmachine.core.Transaction;
import com.voidmachine.db.HistoryEntry;
import com.voidmachine.integration.DiscordHook;
import com.voidmachine.util.Effects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves outcomes and applies them. Runs on the main thread for any Bukkit
 * mutation, but enqueues persistence onto the async executor.
 *
 * <p>The class owns the lifecycle of active {@link Transaction}s. On graceful
 * shutdown every still-CAPTURED or PROCESSING transaction has its sacrifice
 * returned to the player; if the player is offline, items are dropped at the
 * world spawn or their last known location.</p>
 */
public final class ProcessingService {

    private final VoidMachinePlugin plugin;
    private final PluginConfig config;
    private final MessageManager messages;
    private final OutcomeRoller roller;
    private final StatsService stats;
    private final Effects effects;
    private final DiscordHook discord;

    private final Map<java.util.UUID, Transaction> active = new ConcurrentHashMap<>();

    public ProcessingService(VoidMachinePlugin plugin,
                             PluginConfig config,
                             MessageManager messages,
                             OutcomeRoller roller,
                             StatsService stats,
                             Effects effects,
                             DiscordHook discord) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.roller = roller;
        this.stats = stats;
        this.effects = effects;
        this.discord = discord;
    }

    public Transaction openTransaction(Player player) {
        Transaction tx = new Transaction(player);
        active.put(tx.id(), tx);
        return tx;
    }

    public void abort(Transaction tx) {
        active.remove(tx.id());
        if (tx.state() == Transaction.State.CAPTURED || tx.state() == Transaction.State.PROCESSING) {
            tx.markFailed();
            Player player = Bukkit.getPlayer(tx.playerId());
            if (player != null && player.isOnline()) {
                returnSafely(player, tx.copyOfSacrificeWithAmount(tx.inputAmount()));
            } else {
                dropAtLastKnown(tx);
            }
        }
    }

    /**
     * Resolve outcome on the main thread. Caller has already captured the
     * transaction and marked it PROCESSING.
     */
    public Outcome resolveOutcome(Transaction tx) {
        if (tx.state() != Transaction.State.PROCESSING) {
            throw new IllegalStateException("resolveOutcome requires PROCESSING state");
        }
        Outcome rolled = roller.roll();
        int multiplier = roller.multiplierFor(rolled);
        int inAmount = tx.inputAmount();
        int outAmount = switch (rolled) {
            case DESTROYED -> 0;
            case RETURNED -> inAmount;
            default -> {
                int desired = Math.multiplyExact(inAmount, multiplier);
                if (desired > config.maxReturnAmount()) {
                    yield config.clampOnOverflow() ? config.maxReturnAmount() : inAmount;
                }
                yield desired;
            }
        };
        tx.markCompleted(rolled, outAmount);
        return rolled;
    }

    /**
     * Deliver the result to the player. Must be called on the main thread.
     */
    public void deliver(Player player, Transaction tx) {
        active.remove(tx.id());
        if (tx.state() != Transaction.State.COMPLETED) {
            abort(tx);
            return;
        }

        Outcome outcome = tx.outcome();
        int outAmount = tx.outputAmount();
        ItemStack sacrifice = tx.sacrifice();

        Component itemName = sacrifice == null
                ? Component.text("?")
                : Component.translatable(sacrifice.getType().translationKey());

        TagResolver resolver = TagResolver.resolver(
                Placeholder.parsed("player", player.getName()),
                Placeholder.component("item", itemName),
                Placeholder.parsed("input", Integer.toString(tx.inputAmount())),
                Placeholder.parsed("output", Integer.toString(outAmount)),
                Placeholder.parsed("multiplier", Integer.toString(roller.multiplierFor(outcome))),
                Placeholder.parsed("outcome", outcome.name())
        );

        if (outAmount > 0 && sacrifice != null) {
            ItemStack reward = sacrifice.clone();
            int maxStack = reward.getMaxStackSize();
            int remaining = outAmount;
            while (remaining > 0) {
                int amount = Math.min(maxStack, remaining);
                reward.setAmount(amount);
                returnSafely(player, reward.clone());
                remaining -= amount;
            }
        }

        player.sendMessage(messages.outcomeMessage(outcome, resolver));
        effects.playReveal(player, outcome);
        broadcastIfNeeded(player, tx, outcome, resolver);
        logIfEnabled(player, tx, outcome);

        HistoryEntry entry = new HistoryEntry(
                0L,
                player.getUniqueId(),
                player.getName(),
                sacrifice == null ? "AIR" : sacrifice.getType().key().asString(),
                tx.inputAmount(),
                outAmount,
                outcome,
                Instant.now()
        );
        stats.record(entry);
    }

    private void broadcastIfNeeded(Player player, Transaction tx, Outcome outcome, TagResolver resolver) {
        if (matches(config.broadcastOn(), outcome)) {
            Component msg = messages.broadcast(outcome, resolver);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("voidmachine.notify")) {
                    online.sendMessage(msg);
                }
            }
            Bukkit.getConsoleSender().sendMessage(msg);
        }

        if (matches(config.broadcastTitleOn(), outcome)) {
            Component title = messages.render("title.jackpot.title", resolver);
            Component subtitle = messages.render("title.jackpot.subtitle", resolver);
            Title.Times times = Title.Times.times(
                    Duration.ofMillis(50L * messages.intValue("title.jackpot.fade-in", 10)),
                    Duration.ofMillis(50L * messages.intValue("title.jackpot.stay", 60)),
                    Duration.ofMillis(50L * messages.intValue("title.jackpot.fade-out", 20)));
            Title titleObj = Title.title(title, subtitle, times);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("voidmachine.notify")) {
                    online.showTitle(titleObj);
                }
            }
        }

        if (matches(config.lightningOn(), outcome)) {
            Location loc = player.getLocation();
            loc.getWorld().strikeLightningEffect(loc);
        }

        if (config.discordEnabled()) {
            for (String name : config.discordForward()) {
                if (outcome.name().equalsIgnoreCase(name)) {
                    discord.postBroadcast(outcome, player, tx);
                    break;
                }
            }
        }
    }

    private void logIfEnabled(Player player, Transaction tx, Outcome outcome) {
        if (!config.logEveryRoll()) return;
        plugin.getLogger().info(String.format(Locale.ROOT,
                "[VM] %s sacrificed %d × %s → %s (out=%d)",
                player.getName(),
                tx.inputAmount(),
                tx.sacrifice() == null ? "AIR" : tx.sacrifice().getType().key().asString(),
                outcome.name(),
                tx.outputAmount()));
    }

    private static boolean matches(java.util.List<String> list, Outcome outcome) {
        if (list == null || list.isEmpty()) return false;
        for (String s : list) {
            if (outcome.name().equalsIgnoreCase(s)) return true;
        }
        return false;
    }

    private void returnSafely(Player player, ItemStack stack) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
        if (!overflow.isEmpty()) {
            Location at = player.getLocation();
            for (ItemStack drop : overflow.values()) {
                at.getWorld().dropItemNaturally(at, drop);
            }
        }
    }

    private void dropAtLastKnown(Transaction tx) {
        ItemStack sacrifice = tx.copyOfSacrificeWithAmount(tx.inputAmount());
        Player offline = Bukkit.getPlayer(tx.playerId());
        Location at = offline != null ? offline.getLocation() : null;
        if (at == null && !Bukkit.getWorlds().isEmpty()) {
            at = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        if (at != null) {
            at.getWorld().dropItemNaturally(at, sacrifice);
            plugin.getLogger().warning("Returned in-flight sacrifice of " + tx.playerName()
                    + " by world drop at " + at + " (player offline).");
        }
    }

    /**
     * Called during plugin disable. Returns any in-flight sacrifices to their
     * owners (online or via world drop) so a restart can never delete items.
     */
    public void shutdown() {
        // Snapshot — abort() removes entries from the map as it goes.
        java.util.List<Transaction> snapshot = new java.util.ArrayList<>(active.values());
        for (Transaction tx : snapshot) {
            try {
                abort(tx);
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to abort transaction " + tx.id() + ": " + ex.getMessage());
            }
        }
        active.clear();
    }

    @Nullable
    public Transaction active(java.util.UUID transactionId) {
        return active.get(transactionId);
    }
}
