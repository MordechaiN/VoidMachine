/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.config;

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.core.Outcome;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * MiniMessage-driven message lookup. Messages are stored in {@code messages.yml}.
 *
 * <p>All public render methods return Adventure {@link Component} instances so they
 * can be sent verbatim to players via {@code player.sendMessage(...)}. The class is
 * thread-safe for reading; reloads are guarded by the underlying volatile reference
 * to the configuration.</p>
 */
public final class MessageManager {

    private final VoidMachinePlugin plugin;
    private final PluginConfig config;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private volatile FileConfiguration messages;
    private volatile Component prefix;

    public MessageManager(VoidMachinePlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        FileConfiguration loaded = YamlConfiguration.loadConfiguration(file);

        try (InputStreamReader defaultsReader = new InputStreamReader(
                Objects.requireNonNull(plugin.getResource("messages.yml"),
                        "messages.yml missing from jar"),
                StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(defaultsReader));
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not load default messages: " + ex.getMessage());
        }

        this.messages = loaded;
        this.prefix = mm.deserialize(loaded.getString("prefix", ""));
    }

    public MiniMessage mm() {
        return mm;
    }

    public Component prefix() {
        return prefix;
    }

    /**
     * Render a message by dot-path with arbitrary placeholder resolvers.
     */
    @NotNull
    public Component render(@NotNull String path, @NotNull TagResolver... resolvers) {
        String raw = messages.getString(path);
        if (raw == null || raw.isBlank()) {
            return Component.empty();
        }
        TagResolver[] all = withPrefix(resolvers);
        return mm.deserialize(raw, all);
    }

    @NotNull
    public List<Component> renderList(@NotNull String path, @NotNull TagResolver... resolvers) {
        List<String> raws = messages.getStringList(path);
        if (raws.isEmpty()) {
            String single = messages.getString(path);
            if (single != null && !single.isBlank()) {
                raws = List.of(single.split("\\R"));
            }
        }
        List<Component> out = new ArrayList<>(raws.size());
        TagResolver[] all = withPrefix(resolvers);
        for (String raw : raws) {
            out.add(mm.deserialize(raw, all));
        }
        return out;
    }

    public Component outcomeMessage(Outcome outcome, TagResolver... resolvers) {
        return render("outcome." + outcome.configKey(), resolvers);
    }

    public Component broadcast(Outcome outcome, TagResolver... resolvers) {
        return render("broadcast." + outcome.configKey(), resolvers);
    }

    private TagResolver[] withPrefix(TagResolver[] resolvers) {
        TagResolver[] all = new TagResolver[resolvers.length + 1];
        all[0] = Placeholder.component("prefix", prefix);
        System.arraycopy(resolvers, 0, all, 1, resolvers.length);
        return all;
    }

    public String raw(String path, String fallback) {
        return messages.getString(path, fallback);
    }

    public int intValue(String path, int fallback) {
        return messages.getInt(path, fallback);
    }
}
