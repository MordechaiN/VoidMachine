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

import com.voidmachine.core.Outcome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Lightweight YAML-backed storage. Adequate for small to mid-sized servers.
 * Writes are batched on a flush interval to keep disk pressure low.
 *
 * <p>All in-memory state is held in concurrent collections, so reads from any
 * executor thread are safe. Flushes are serialized through a per-instance lock.</p>
 */
public final class YamlStorage implements Storage {

    private static final int HISTORY_KEEP = 5_000;

    private final File statsFile;
    private final File historyFile;
    private final File usageFile;
    private final Logger logger;

    private final Map<UUID, PlayerStats> stats = new ConcurrentHashMap<>();
    private final Deque<HistoryEntry> history = new ArrayDeque<>();
    private final Map<UUID, Map<String, Integer>> dailyUsage = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    private long historyCounter;
    private volatile boolean dirty;

    public YamlStorage(File dataFolder, Logger logger) {
        this.statsFile = new File(dataFolder, "stats.yml");
        this.historyFile = new File(dataFolder, "history.yml");
        this.usageFile = new File(dataFolder, "usage.yml");
        this.logger = logger;
    }

    @Override
    public void start() {
        loadStatsFile();
        loadHistoryFile();
        loadUsageFile();
    }

    @Override
    public void shutdown() {
        flush();
    }

    private void loadStatsFile() {
        if (!statsFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(statsFile);
        ConfigurationSection root = y.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection sec = root.getConfigurationSection(key);
                if (sec == null) continue;
                PlayerStats ps = new PlayerStats(uuid, sec.getString("name", uuid.toString()));
                long sac = sec.getLong("sacrifices", 0);
                Map<Outcome, Long> counts = new HashMap<>();
                Map<Outcome, Long> items = new HashMap<>();
                ConfigurationSection cs = sec.getConfigurationSection("counts");
                ConfigurationSection is = sec.getConfigurationSection("items");
                if (cs != null) for (Outcome o : Outcome.values()) counts.put(o, cs.getLong(o.configKey(), 0L));
                if (is != null) for (Outcome o : Outcome.values()) items.put(o, is.getLong(o.configKey(), 0L));
                ps.seed(sac, counts, items, sec.getLong("last-update-ms", 0L));
                stats.put(uuid, ps);
            } catch (IllegalArgumentException ex) {
                logger.warning("Skipping invalid stats entry " + key + ": " + ex.getMessage());
            }
        }
    }

    private void loadHistoryFile() {
        if (!historyFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(historyFile);
        ConfigurationSection root = y.getConfigurationSection("entries");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                long id = Long.parseLong(key);
                ConfigurationSection sec = root.getConfigurationSection(key);
                if (sec == null) continue;
                history.addLast(new HistoryEntry(
                        id,
                        UUID.fromString(sec.getString("player-id", new UUID(0, 0).toString())),
                        sec.getString("player-name", "?"),
                        sec.getString("material", "AIR"),
                        sec.getInt("input", 0),
                        sec.getInt("output", 0),
                        Outcome.valueOf(sec.getString("outcome", Outcome.DESTROYED.name())),
                        java.time.Instant.ofEpochMilli(sec.getLong("when", 0L))));
                if (id > historyCounter) historyCounter = id;
            } catch (Exception ex) {
                logger.warning("Skipping invalid history entry " + key + ": " + ex.getMessage());
            }
        }
    }

    private void loadUsageFile() {
        if (!usageFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(usageFile);
        ConfigurationSection root = y.getConfigurationSection("usage");
        if (root == null) return;
        for (String dateKey : root.getKeys(false)) {
            ConfigurationSection dateSec = root.getConfigurationSection(dateKey);
            if (dateSec == null) continue;
            for (String uuidKey : dateSec.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidKey);
                    int v = dateSec.getInt(uuidKey, 0);
                    dailyUsage.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(dateKey, v);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    @Override
    public void recordTransaction(HistoryEntry entry) {
        PlayerStats ps = stats.computeIfAbsent(entry.playerId(), id -> new PlayerStats(id, entry.playerName()));
        ps.setPlayerName(entry.playerName());
        ps.record(entry.outcome(), entry.inputAmount(), entry.outputAmount());

        long id;
        synchronized (lock) {
            historyCounter++;
            id = historyCounter;
            history.addFirst(new HistoryEntry(id, entry.playerId(), entry.playerName(),
                    entry.materialKey(), entry.inputAmount(), entry.outputAmount(),
                    entry.outcome(), entry.when()));
            while (history.size() > HISTORY_KEEP) {
                history.removeLast();
            }
        }
        dirty = true;
    }

    @Override
    public PlayerStats loadStats(UUID playerId, String fallbackName) {
        PlayerStats ps = stats.computeIfAbsent(playerId, id -> new PlayerStats(id, fallbackName));
        if (fallbackName != null && !fallbackName.equals(ps.playerName())) {
            ps.setPlayerName(fallbackName);
            dirty = true;
        }
        return ps;
    }

    @Override
    public void incrementDailyUsage(UUID playerId, String today) {
        dailyUsage.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .merge(today, 1, Integer::sum);
        dirty = true;
    }

    @Override
    public int dailyUsage(UUID playerId, String today) {
        Map<String, Integer> m = dailyUsage.get(playerId);
        return m == null ? 0 : m.getOrDefault(today, 0);
    }

    @Override
    public List<HistoryEntry> recentHistory(int limit) {
        synchronized (lock) {
            List<HistoryEntry> out = new ArrayList<>(Math.min(limit, history.size()));
            int i = 0;
            for (HistoryEntry e : history) {
                if (i++ >= limit) break;
                out.add(e);
            }
            return out;
        }
    }

    @Override
    public Map<String, Long> topByOutcome(Outcome outcome, int limit) {
        List<PlayerStats> all = new ArrayList<>(stats.values());
        all.sort(Comparator.comparingLong((PlayerStats p) -> p.items(outcome)).reversed());
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(limit, all.size()); i++) {
            PlayerStats ps = all.get(i);
            if (ps.items(outcome) <= 0) break;
            out.put(ps.playerName(), ps.items(outcome));
        }
        return out;
    }

    @Override
    public Map<String, Long> topBySacrifices(int limit) {
        List<PlayerStats> all = new ArrayList<>(stats.values());
        all.sort(Comparator.comparingLong(PlayerStats::sacrifices).reversed());
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(limit, all.size()); i++) {
            PlayerStats ps = all.get(i);
            if (ps.sacrifices() <= 0) break;
            out.put(ps.playerName(), ps.sacrifices());
        }
        return out;
    }

    @Override
    public String selfTest() {
        return null;
    }

    public synchronized void flush() {
        if (!dirty) return;
        try {
            writeStats();
            writeHistory();
            writeUsage();
            dirty = false;
        } catch (IOException ex) {
            logger.warning("Failed to flush YAML storage: " + ex.getMessage());
        }
    }

    private void writeStats() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("players");
        for (Map.Entry<UUID, PlayerStats> e : stats.entrySet()) {
            ConfigurationSection sec = root.createSection(e.getKey().toString());
            PlayerStats ps = e.getValue();
            sec.set("name", ps.playerName());
            sec.set("sacrifices", ps.sacrifices());
            sec.set("last-update-ms", ps.lastUpdateMs());
            ConfigurationSection counts = sec.createSection("counts");
            ConfigurationSection items = sec.createSection("items");
            for (Outcome o : Outcome.values()) {
                counts.set(o.configKey(), ps.count(o));
                items.set(o.configKey(), ps.items(o));
            }
        }
        y.save(statsFile);
    }

    private void writeHistory() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("entries");
        synchronized (lock) {
            for (HistoryEntry h : history) {
                ConfigurationSection sec = root.createSection(Long.toString(h.id()));
                sec.set("player-id", h.playerId().toString());
                sec.set("player-name", h.playerName());
                sec.set("material", h.materialKey());
                sec.set("input", h.inputAmount());
                sec.set("output", h.outputAmount());
                sec.set("outcome", h.outcome().name());
                sec.set("when", h.when().toEpochMilli());
            }
        }
        y.save(historyFile);
    }

    private void writeUsage() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("usage");
        for (Map.Entry<UUID, Map<String, Integer>> e : dailyUsage.entrySet()) {
            UUID uuid = e.getKey();
            for (Map.Entry<String, Integer> day : e.getValue().entrySet()) {
                ConfigurationSection dateSec = root.getConfigurationSection(day.getKey());
                if (dateSec == null) dateSec = root.createSection(day.getKey());
                dateSec.set(uuid.toString(), day.getValue());
            }
        }
        y.save(usageFile);
    }
}
