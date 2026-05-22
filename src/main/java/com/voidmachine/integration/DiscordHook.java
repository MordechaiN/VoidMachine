/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.integration;

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.config.MessageManager;
import com.voidmachine.config.PluginConfig;
import com.voidmachine.core.Outcome;
import com.voidmachine.core.Transaction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Best-effort DiscordSRV integration. Wired through reflection so the plugin
 * still loads cleanly if DiscordSRV is missing.
 *
 * <p>Only outbound text messages are sent; we never read Discord chat back into
 * the server.</p>
 */
public final class DiscordHook {

    private final VoidMachinePlugin plugin;
    private final PluginConfig config;
    private final MessageManager messages;

    private volatile boolean available;
    private volatile Object discordSrv;
    private volatile Method getDestinationTextChannelForGameChannelName;
    private volatile Method sendMessageBlocking;

    public DiscordHook(VoidMachinePlugin plugin, PluginConfig config, MessageManager messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        reload();
    }

    public void reload() {
        if (!config.discordEnabled()) {
            available = false;
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
            plugin.getLogger().info("DiscordSRV not present; Discord integration disabled.");
            available = false;
            return;
        }
        try {
            Class<?> clazz = Class.forName("github.scarsz.discordsrv.DiscordSRV");
            discordSrv = clazz.getMethod("getPlugin").invoke(null);
            getDestinationTextChannelForGameChannelName =
                    clazz.getMethod("getDestinationTextChannelForGameChannelName", String.class);
            Class<?> textChannelClass = Class.forName("net.dv8tion.jda.api.entities.channel.concrete.TextChannel");
            sendMessageBlocking = textChannelClass.getMethod("sendMessage", CharSequence.class);
            available = true;
            plugin.getLogger().info("DiscordSRV integration ready.");
        } catch (Throwable ex) {
            plugin.getLogger().warning("Failed to wire DiscordSRV: " + ex.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public void postBroadcast(Outcome outcome, Player player, Transaction tx) {
        if (!available) return;
        try {
            String channel = config.discordChannel();
            Object textChannel = getDestinationTextChannelForGameChannelName.invoke(discordSrv, channel);
            if (textChannel == null) {
                plugin.getLogger().warning("DiscordSRV channel '" + channel + "' not linked.");
                return;
            }
            Component broadcast = messages.broadcast(outcome,
                    TagResolver.resolver(
                            Placeholder.parsed("player", player.getName()),
                            Placeholder.parsed("input", Integer.toString(tx.inputAmount())),
                            Placeholder.parsed("output", Integer.toString(tx.outputAmount())),
                            Placeholder.parsed("outcome", outcome.name()),
                            Placeholder.parsed("item",
                                    tx.sacrifice() == null
                                            ? "?"
                                            : tx.sacrifice().getType().key().asString())));
            String plain = PlainTextComponentSerializer.plainText().serialize(broadcast);
            Object messageAction = sendMessageBlocking.invoke(textChannel, plain);
            // Submit asynchronously via JDA's queue(), if available.
            messageAction.getClass().getMethod("queue").invoke(messageAction);
        } catch (Throwable ex) {
            plugin.getLogger().warning("Failed to send Discord broadcast: " + ex.getMessage());
        }
    }
}
