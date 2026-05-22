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

import com.voidmachine.config.PluginConfig;
import com.voidmachine.core.Outcome;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * HikariCP + MariaDB/MySQL persistence. Tables are created lazily on first use.
 *
 * <p>Reads block on the calling thread, which is acceptable because all callers
 * are dispatched through {@link DatabaseManager}'s executor. The pool size is
 * configurable so administrators of large servers can tune throughput.</p>
 */
public final class SqlStorage implements Storage {

    private final PluginConfig.MysqlSettings settings;
    private final Logger logger;
    private HikariDataSource ds;

    private final String tStats;
    private final String tHistory;
    private final String tUsage;

    public SqlStorage(PluginConfig.MysqlSettings settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
        String prefix = settings.tablePrefix();
        this.tStats = prefix + "stats";
        this.tHistory = prefix + "history";
        this.tUsage = prefix + "daily_usage";
    }

    @Override
    public void start() {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("VoidMachine-Hikari");
        hc.setJdbcUrl("jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/" + settings.database()
                + "?useSSL=" + settings.useSsl() + "&rewriteBatchedStatements=true&useUnicode=true&characterEncoding=utf8");
        hc.setUsername(settings.username());
        hc.setPassword(settings.password());
        hc.setMaximumPoolSize(settings.poolSize());
        hc.setMinimumIdle(settings.poolMinIdle());
        hc.setConnectionTimeout(settings.connectionTimeoutMs());
        hc.setLeakDetectionThreshold(30_000L);
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "256");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        this.ds = new HikariDataSource(hc);
        try (Connection c = ds.getConnection()) {
            ensureSchema(c);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to initialise SQL schema", ex);
        }
    }

    private void ensureSchema(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS " + tStats + " (" +
                        "player_id CHAR(36) NOT NULL PRIMARY KEY," +
                        "player_name VARCHAR(32) NOT NULL," +
                        "sacrifices BIGINT NOT NULL DEFAULT 0," +
                        "destroyed_count BIGINT NOT NULL DEFAULT 0," +
                        "returned_count BIGINT NOT NULL DEFAULT 0," +
                        "doubled_count BIGINT NOT NULL DEFAULT 0," +
                        "tripled_count BIGINT NOT NULL DEFAULT 0," +
                        "jackpot_count BIGINT NOT NULL DEFAULT 0," +
                        "destroyed_items BIGINT NOT NULL DEFAULT 0," +
                        "returned_items BIGINT NOT NULL DEFAULT 0," +
                        "doubled_items BIGINT NOT NULL DEFAULT 0," +
                        "tripled_items BIGINT NOT NULL DEFAULT 0," +
                        "jackpot_items BIGINT NOT NULL DEFAULT 0," +
                        "last_update_ms BIGINT NOT NULL DEFAULT 0" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS " + tHistory + " (" +
                        "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                        "player_id CHAR(36) NOT NULL," +
                        "player_name VARCHAR(32) NOT NULL," +
                        "material VARCHAR(128) NOT NULL," +
                        "input_amount INT NOT NULL," +
                        "output_amount INT NOT NULL," +
                        "outcome VARCHAR(32) NOT NULL," +
                        "occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "INDEX idx_player (player_id)," +
                        "INDEX idx_outcome (outcome)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS " + tUsage + " (" +
                        "player_id CHAR(36) NOT NULL," +
                        "day CHAR(10) NOT NULL," +
                        "uses INT NOT NULL DEFAULT 0," +
                        "PRIMARY KEY (player_id, day)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            s.executeUpdate();
        }
    }

    @Override
    public void shutdown() {
        if (ds != null) ds.close();
    }

    @Override
    public void recordTransaction(HistoryEntry entry) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                upsertStats(c, entry);
                insertHistory(c, entry);
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            logger.warning("SQL recordTransaction failed: " + ex.getMessage());
        }
    }

    private void upsertStats(Connection c, HistoryEntry e) throws SQLException {
        long deltaItems = switch (e.outcome()) {
            case DESTROYED -> e.inputAmount();
            default -> e.outputAmount();
        };
        String col = switch (e.outcome()) {
            case DESTROYED -> "destroyed";
            case RETURNED -> "returned";
            case DOUBLED -> "doubled";
            case TRIPLED -> "tripled";
            case JACKPOT_X5 -> "jackpot";
        };
        String sql = "INSERT INTO " + tStats + " (player_id, player_name, sacrifices, " + col + "_count, " + col + "_items, last_update_ms) " +
                "VALUES (?, ?, 1, 1, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "  player_name = VALUES(player_name)," +
                "  sacrifices = sacrifices + 1," +
                "  " + col + "_count = " + col + "_count + 1," +
                "  " + col + "_items = " + col + "_items + VALUES(" + col + "_items)," +
                "  last_update_ms = VALUES(last_update_ms)";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, e.playerId().toString());
            s.setString(2, safeName(e.playerName()));
            s.setLong(3, deltaItems);
            s.setLong(4, System.currentTimeMillis());
            s.executeUpdate();
        }
    }

    private void insertHistory(Connection c, HistoryEntry e) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO " + tHistory +
                " (player_id, player_name, material, input_amount, output_amount, outcome, occurred_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            s.setString(1, e.playerId().toString());
            s.setString(2, safeName(e.playerName()));
            s.setString(3, e.materialKey());
            s.setInt(4, e.inputAmount());
            s.setInt(5, e.outputAmount());
            s.setString(6, e.outcome().name());
            s.setTimestamp(7, Timestamp.from(e.when()));
            s.executeUpdate();
        }
    }

    @Override
    public PlayerStats loadStats(UUID playerId, String fallbackName) {
        try (Connection c = ds.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM " + tStats + " WHERE player_id = ?")) {
            s.setString(1, playerId.toString());
            try (ResultSet rs = s.executeQuery()) {
                PlayerStats ps = new PlayerStats(playerId, fallbackName);
                if (rs.next()) {
                    Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
                    Map<Outcome, Long> items = new EnumMap<>(Outcome.class);
                    counts.put(Outcome.DESTROYED, rs.getLong("destroyed_count"));
                    counts.put(Outcome.RETURNED,  rs.getLong("returned_count"));
                    counts.put(Outcome.DOUBLED,   rs.getLong("doubled_count"));
                    counts.put(Outcome.TRIPLED,   rs.getLong("tripled_count"));
                    counts.put(Outcome.JACKPOT_X5, rs.getLong("jackpot_count"));
                    items.put(Outcome.DESTROYED, rs.getLong("destroyed_items"));
                    items.put(Outcome.RETURNED,  rs.getLong("returned_items"));
                    items.put(Outcome.DOUBLED,   rs.getLong("doubled_items"));
                    items.put(Outcome.TRIPLED,   rs.getLong("tripled_items"));
                    items.put(Outcome.JACKPOT_X5, rs.getLong("jackpot_items"));
                    ps.seed(rs.getLong("sacrifices"), counts, items, rs.getLong("last_update_ms"));
                    ps.setPlayerName(rs.getString("player_name"));
                }
                return ps;
            }
        } catch (SQLException ex) {
            logger.warning("SQL loadStats failed: " + ex.getMessage());
            return new PlayerStats(playerId, fallbackName);
        }
    }

    @Override
    public void incrementDailyUsage(UUID playerId, String today) {
        try (Connection c = ds.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "INSERT INTO " + tUsage + " (player_id, day, uses) VALUES (?, ?, 1) " +
                             "ON DUPLICATE KEY UPDATE uses = uses + 1")) {
            s.setString(1, playerId.toString());
            s.setString(2, today);
            s.executeUpdate();
        } catch (SQLException ex) {
            logger.warning("SQL incrementDailyUsage failed: " + ex.getMessage());
        }
    }

    @Override
    public int dailyUsage(UUID playerId, String today) {
        try (Connection c = ds.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT uses FROM " + tUsage + " WHERE player_id = ? AND day = ?")) {
            s.setString(1, playerId.toString());
            s.setString(2, today);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            logger.warning("SQL dailyUsage failed: " + ex.getMessage());
            return 0;
        }
    }

    @Override
    public List<HistoryEntry> recentHistory(int limit) {
        List<HistoryEntry> out = new ArrayList<>(limit);
        try (Connection c = ds.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT id, player_id, player_name, material, input_amount, output_amount, outcome, occurred_at " +
                             "FROM " + tHistory + " ORDER BY id DESC LIMIT ?")) {
            s.setInt(1, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    out.add(new HistoryEntry(
                            rs.getLong("id"),
                            UUID.fromString(rs.getString("player_id")),
                            rs.getString("player_name"),
                            rs.getString("material"),
                            rs.getInt("input_amount"),
                            rs.getInt("output_amount"),
                            Outcome.valueOf(rs.getString("outcome")),
                            rs.getTimestamp("occurred_at").toInstant()));
                }
            }
        } catch (SQLException ex) {
            logger.warning("SQL recentHistory failed: " + ex.getMessage());
        }
        return out;
    }

    @Override
    public Map<String, Long> topByOutcome(Outcome outcome, int limit) {
        String col = switch (outcome) {
            case DESTROYED -> "destroyed_items";
            case RETURNED -> "returned_items";
            case DOUBLED -> "doubled_items";
            case TRIPLED -> "tripled_items";
            case JACKPOT_X5 -> "jackpot_items";
        };
        Map<String, Long> out = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT player_name, " + col + " FROM " + tStats + " WHERE " + col + " > 0 " +
                             "ORDER BY " + col + " DESC LIMIT ?")) {
            s.setInt(1, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException ex) {
            logger.warning("SQL topByOutcome failed: " + ex.getMessage());
        }
        return out;
    }

    @Override
    public Map<String, Long> topBySacrifices(int limit) {
        Map<String, Long> out = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT player_name, sacrifices FROM " + tStats + " WHERE sacrifices > 0 " +
                             "ORDER BY sacrifices DESC LIMIT ?")) {
            s.setInt(1, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException ex) {
            logger.warning("SQL topBySacrifices failed: " + ex.getMessage());
        }
        return out;
    }

    @Override
    public String selfTest() {
        try (Connection c = ds.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT 1");
             ResultSet rs = s.executeQuery()) {
            return rs.next() ? null : "self test returned no rows";
        } catch (SQLException ex) {
            return ex.getMessage();
        }
    }

    private static String safeName(String name) {
        if (name == null) return "?";
        return name.length() > 32 ? name.substring(0, 32) : name;
    }

    /** Reads a HistoryEntry without using auto-generated ID; used only by tests. */
    @SuppressWarnings("unused")
    private static HistoryEntry materialize(ResultSet rs) throws SQLException {
        return new HistoryEntry(
                rs.getLong("id"),
                UUID.fromString(rs.getString("player_id")),
                rs.getString("player_name"),
                rs.getString("material"),
                rs.getInt("input_amount"),
                rs.getInt("output_amount"),
                Outcome.valueOf(rs.getString("outcome")),
                Instant.now());
    }

    /** Helpful when migrating: returns a HashMap because some callers mutate. */
    @SuppressWarnings("unused")
    private static Map<Outcome, Long> emptyOutcomeMap() {
        Map<Outcome, Long> m = new HashMap<>();
        for (Outcome o : Outcome.values()) m.put(o, 0L);
        return m;
    }
}
