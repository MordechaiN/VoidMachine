/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.db;

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.config.PluginConfig;
import com.voidmachine.core.Outcome;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async-safe facade over the active {@link Storage} backend. All write methods
 * return a {@link CompletableFuture} so callers can react to completion or
 * failure without blocking the main server thread.
 */
public final class DatabaseManager {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final VoidMachinePlugin plugin;
    private final PluginConfig config;
    private Storage backend;
    private ExecutorService io;
    private ScheduledExecutorService scheduler;

    public DatabaseManager(VoidMachinePlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        plugin.getDataFolder().mkdirs();
        String type = config.storageType();
        if ("mysql".equals(type) || "mariadb".equals(type)) {
            PluginConfig.MysqlSettings settings = config.mysql();
            if (settings == null) {
                throw new IllegalStateException("storage.type=mysql but no mysql block in config.yml");
            }
            backend = new SqlStorage(settings, plugin.getLogger());
        } else {
            backend = new YamlStorage(plugin.getDataFolder(), plugin.getLogger());
        }
        backend.start();

        this.io = Executors.newFixedThreadPool(config.storagePoolSize(), namedFactory("VoidMachine-IO"));
        this.scheduler = new ScheduledThreadPoolExecutor(1, namedFactory("VoidMachine-Sched"));

        if (backend instanceof YamlStorage yaml) {
            long sec = Math.max(5L, config.flushIntervalSeconds());
            scheduler.scheduleAtFixedRate(yaml::flush, sec, sec, TimeUnit.SECONDS);
        }
    }

    public void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
        if (io != null) {
            io.shutdown();
            try {
                if (!io.awaitTermination(5L, TimeUnit.SECONDS)) {
                    io.shutdownNow();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        if (backend != null) backend.shutdown();
    }

    public CompletableFuture<Void> recordTransaction(HistoryEntry entry) {
        return CompletableFuture.runAsync(() -> backend.recordTransaction(entry), io);
    }

    public CompletableFuture<PlayerStats> loadStats(UUID playerId, String fallbackName) {
        return CompletableFuture.supplyAsync(() -> backend.loadStats(playerId, fallbackName), io);
    }

    public CompletableFuture<Void> incrementDailyUsage(UUID playerId) {
        String day = LocalDate.now(ZoneId.systemDefault()).format(DAY_FMT);
        return CompletableFuture.runAsync(() -> backend.incrementDailyUsage(playerId, day), io);
    }

    public CompletableFuture<Integer> dailyUsage(UUID playerId) {
        String day = LocalDate.now(ZoneId.systemDefault()).format(DAY_FMT);
        return CompletableFuture.supplyAsync(() -> backend.dailyUsage(playerId, day), io);
    }

    public CompletableFuture<List<HistoryEntry>> recentHistory(int limit) {
        return CompletableFuture.supplyAsync(() -> backend.recentHistory(limit), io);
    }

    public CompletableFuture<Map<String, Long>> topByOutcome(Outcome outcome, int limit) {
        return CompletableFuture.supplyAsync(() -> backend.topByOutcome(outcome, limit), io);
    }

    public CompletableFuture<Map<String, Long>> topBySacrifices(int limit) {
        return CompletableFuture.supplyAsync(() -> backend.topBySacrifices(limit), io);
    }

    public CompletableFuture<String> selfTest() {
        return CompletableFuture.supplyAsync(backend::selfTest, io);
    }

    public Duration flushInterval() {
        return Duration.ofSeconds(Math.max(5L, config.flushIntervalSeconds()));
    }

    private static ThreadFactory namedFactory(String namePrefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, namePrefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    System.err.println("[VoidMachine] Uncaught error in " + thread.getName() + ": " + ex));
            return t;
        };
    }
}
