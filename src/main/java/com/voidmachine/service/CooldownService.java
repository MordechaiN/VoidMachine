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
import com.voidmachine.config.PluginConfig;
import com.voidmachine.db.DatabaseManager;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cooldown gating layer.
 *
 * <p>The service performs a fast, in-memory check first (per-player cooldown,
 * global cooldown). Daily limits require a database round trip; callers should
 * use the async API.</p>
 */
public final class CooldownService {

    private final VoidMachinePlugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;

    private final ConcurrentHashMap<UUID, Long> perPlayerCooldownExpires = new ConcurrentHashMap<>();
    private final AtomicLong globalCooldownExpires = new AtomicLong(0L);

    public CooldownService(VoidMachinePlugin plugin, PluginConfig config, DatabaseManager database) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
    }

    public void reload() {
        perPlayerCooldownExpires.clear();
        globalCooldownExpires.set(0L);
    }

    /**
     * Check whether the player is currently on cooldown. Returns the remaining
     * {@link Duration}, or {@code Duration.ZERO} if they are clear to play.
     *
     * <p>This call is synchronous and only checks in-memory state. Use
     * {@link #checkDailyLimit(Player)} for the database-bound daily limit.</p>
     */
    public Duration remainingCooldown(Player player) {
        if (player.hasPermission("voidmachine.bypass.cooldown")) return Duration.ZERO;
        long now = System.currentTimeMillis();

        long globalExpiresAt = globalCooldownExpires.get();
        if (globalExpiresAt > now) return Duration.ofMillis(globalExpiresAt - now);

        Long playerExpiresAt = perPlayerCooldownExpires.get(player.getUniqueId());
        if (playerExpiresAt != null && playerExpiresAt > now) {
            return Duration.ofMillis(playerExpiresAt - now);
        }
        return Duration.ZERO;
    }

    /**
     * Apply cooldowns after a successful sacrifice.
     */
    public void apply(Player player) {
        long now = System.currentTimeMillis();
        long perPlayerMs = config.perPlayerCooldownSeconds() * 1000L;
        long globalMs = config.globalCooldownSeconds() * 1000L;
        if (perPlayerMs > 0) {
            perPlayerCooldownExpires.put(player.getUniqueId(), now + perPlayerMs);
        }
        if (globalMs > 0) {
            globalCooldownExpires.updateAndGet(prev -> Math.max(prev, now + globalMs));
        }
    }

    /**
     * Daily-limit check. Resolves to {@code true} if the player still has uses
     * available today.
     */
    public java.util.concurrent.CompletableFuture<Boolean> checkDailyLimit(Player player) {
        int limit = config.dailyLimit();
        if (limit <= 0 || player.hasPermission("voidmachine.bypass.cooldown")) {
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }
        return database.dailyUsage(player.getUniqueId()).thenApply(uses -> uses < limit);
    }

    public void recordUse(Player player) {
        database.incrementDailyUsage(player.getUniqueId()).exceptionally(ex -> {
            plugin.getLogger().warning("Failed to bump daily usage for " + player.getName() + ": " + ex);
            return null;
        });
    }

    public boolean shouldNotifyOnBlock() {
        return config.notifyOnCooldownBlock();
    }
}
