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
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Typed view over {@code config.yml}. All getters re-resolve from the live
 * {@link FileConfiguration}, so a call to {@link #reload()} is enough to pick
 * up changes — no consumer needs to be rebuilt.
 */
public final class PluginConfig {

    private final VoidMachinePlugin plugin;
    private volatile FileConfiguration config;

    public PluginConfig(VoidMachinePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    public FileConfiguration raw() {
        return config;
    }

    public boolean debug() {
        return config.getBoolean("plugin.debug", false);
    }

    public String language() {
        return config.getString("plugin.language", "en");
    }

    public Locale locale() {
        return Locale.forLanguageTag(config.getString("plugin.locale", "en-US"));
    }

    public double outcomeWeight(Outcome outcome) {
        return config.getDouble("outcomes." + outcome.configKey(), defaultWeight(outcome));
    }

    public int outcomeMultiplier(Outcome outcome) {
        return switch (outcome) {
            case DESTROYED -> 0;
            case RETURNED -> 1;
            default -> config.getInt("multipliers." + outcome.configKey(), outcome.defaultMultiplier());
        };
    }

    private double defaultWeight(Outcome outcome) {
        return switch (outcome) {
            case DESTROYED -> 72.0;
            case RETURNED -> 18.0;
            case DOUBLED -> 7.0;
            case TRIPLED -> 2.0;
            case JACKPOT_X5 -> 1.0;
        };
    }

    public int maxInsertAmount() {
        return config.getInt("limits.max-insert-amount", 256);
    }

    public int maxReturnAmount() {
        return config.getInt("limits.max-return-amount", 1024);
    }

    public boolean clampOnOverflow() {
        return config.getBoolean("limits.clamp-on-overflow", true);
    }

    public long perPlayerCooldownSeconds() {
        return config.getLong("cooldown.per-player-seconds", 8);
    }

    public long globalCooldownSeconds() {
        return config.getLong("cooldown.global-seconds", 0);
    }

    public int dailyLimit() {
        return config.getInt("cooldown.daily-limit", 0);
    }

    public boolean notifyOnCooldownBlock() {
        return config.getBoolean("cooldown.notify-on-block", true);
    }

    public Set<String> allowedWorlds() {
        return new HashSet<>(config.getStringList("worlds.allowed"));
    }

    public Set<String> blockedWorlds() {
        return new HashSet<>(config.getStringList("worlds.blocked"));
    }

    public boolean blockContainers() { return config.getBoolean("blacklist.block-containers", true); }
    public boolean blockBundles() { return config.getBoolean("blacklist.block-bundles", true); }
    public boolean blockBlockEntity() { return config.getBoolean("blacklist.block-block-entity", true); }

    public Set<String> blacklistMarkedPdcKeys() {
        return new HashSet<>(config.getStringList("blacklist.block-marked-pdc-keys"));
    }

    public Set<Material> blacklistMaterials() {
        Set<Material> out = new HashSet<>();
        for (String s : config.getStringList("blacklist.materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null) out.add(m);
        }
        return out;
    }

    public Set<String> blacklistCustomKeys() {
        return new HashSet<>(config.getStringList("blacklist.custom-keys"));
    }

    public String guiTitle() { return config.getString("gui.title", "<dark_gray>The Void Machine"); }
    public int guiRows() { return Math.max(3, Math.min(6, config.getInt("gui.rows", 5))); }
    public int guiSacrificeSlot() { return config.getInt("gui.sacrifice-slot", 22); }
    public int guiActivateSlot() { return config.getInt("gui.activate-slot", 31); }
    public Material guiActivateButton() {
        Material m = Material.matchMaterial(config.getString("gui.activate-button-material", "NETHER_STAR"));
        return m != null ? m : Material.NETHER_STAR;
    }
    public Material guiFiller() {
        Material m = Material.matchMaterial(config.getString("gui.filler-material", "BLACK_STAINED_GLASS_PANE"));
        return m != null ? m : Material.BLACK_STAINED_GLASS_PANE;
    }
    public List<String> guiAnimationMaterials() {
        return config.getStringList("gui.animation-materials");
    }
    public boolean guiLockDuringAnimation() { return config.getBoolean("gui.lock-during-animation", true); }
    public boolean guiReturnOnDisconnect() { return config.getBoolean("gui.return-on-disconnect", true); }

    public int animationSteps() { return Math.max(1, config.getInt("animation.steps", 30)); }
    public int animationIntervalTicks() { return Math.max(1, config.getInt("animation.step-interval-ticks", 2)); }
    public int animationRevealDelayTicks() { return Math.max(0, config.getInt("animation.reveal-delay-ticks", 12)); }
    public float animationRevealVolume() { return (float) config.getDouble("animation.reveal-sound-volume", 1.0); }

    @Nullable
    public SoundDef sound(String key) {
        ConfigurationSection s = config.getConfigurationSection("sounds." + key);
        if (s == null) return null;
        String soundKey = s.getString("key");
        if (soundKey == null || soundKey.isBlank()) return null;
        return new SoundDef(soundKey, (float) s.getDouble("volume", 1.0), (float) s.getDouble("pitch", 1.0));
    }

    public boolean particlesEnabled() {
        return config.getBoolean("particles.enabled", true);
    }

    @Nullable
    public ParticleDef particle(String key) {
        ConfigurationSection s = config.getConfigurationSection("particles." + key);
        if (s == null) return null;
        String pk = s.getString("key");
        if (pk == null || pk.isBlank()) return null;
        return new ParticleDef(pk, s.getInt("count", 10), s.getDouble("offset", 0.4));
    }

    public List<String> broadcastOn() { return config.getStringList("events.broadcast-on"); }
    public List<String> broadcastTitleOn() { return config.getStringList("events.broadcast-title-on"); }
    public List<String> lightningOn() { return config.getStringList("events.lightning-on"); }
    public boolean logEveryRoll() { return config.getBoolean("events.log-every-roll", true); }

    public boolean discordEnabled() { return config.getBoolean("events.discord.enabled", false); }
    public String discordChannel() { return config.getString("events.discord.channel", "global"); }
    public List<String> discordForward() { return config.getStringList("events.discord.forward"); }

    public String storageType() {
        return config.getString("storage.type", "yaml").toLowerCase(Locale.ROOT);
    }

    public MysqlSettings mysql() {
        ConfigurationSection s = config.getConfigurationSection("storage.mysql");
        if (s == null) return null;
        return new MysqlSettings(
                s.getString("host", "localhost"),
                s.getInt("port", 3306),
                s.getString("database", "voidmachine"),
                s.getString("username", "voidmachine"),
                s.getString("password", ""),
                s.getBoolean("use-ssl", false),
                s.getInt("pool-size", 8),
                s.getInt("pool-min-idle", 2),
                s.getLong("connection-timeout-ms", 5000L),
                s.getString("table-prefix", "vm_"));
    }

    public long flushIntervalSeconds() {
        return config.getLong("storage.flush-interval-seconds", 60L);
    }

    public int historyCacheSize() {
        return config.getInt("storage.history-cache-size", 200);
    }

    public int storagePoolSize() {
        int n = config.getInt("performance.storage-pool-size", 0);
        return n <= 0 ? Math.max(2, Runtime.getRuntime().availableProcessors() / 2) : n;
    }

    public int maxConcurrentSessions() {
        return Math.max(0, config.getInt("performance.max-concurrent-sessions", 64));
    }

    public boolean dropClicksDuringLock() {
        return config.getBoolean("performance.drop-clicks-during-lock", true);
    }

    public boolean isWorldAllowed(@NotNull String name) {
        Set<String> blocked = blockedWorlds();
        if (blocked.contains(name)) return false;
        Set<String> allowed = allowedWorlds();
        return allowed.isEmpty() || allowed.contains(name);
    }

    /** Sound definition resolved from config. */
    public record SoundDef(String key, float volume, float pitch) {}

    /** Particle definition resolved from config. */
    public record ParticleDef(String key, int count, double offset) {}

    /** MySQL/MariaDB connection settings. */
    public record MysqlSettings(
            String host,
            int port,
            String database,
            String username,
            String password,
            boolean useSsl,
            int poolSize,
            int poolMinIdle,
            long connectionTimeoutMs,
            String tablePrefix) {}

    /** Empty immutable list constant for getters that want to avoid null. */
    public static List<String> empty() {
        return Collections.emptyList();
    }

    // =========================================================================
    //  Config profiles — named weight sets for physical machines
    // =========================================================================

    /**
     * Weight for the given outcome under the named config profile.
     *
     * <p>Falls back to the global {@code outcomes.*} value if the profile
     * is not defined or the specific key is missing. This guarantees that
     * the roller always has a valid positive weight even when a profile entry
     * is partially configured.</p>
     *
     * @param profileName profile key from {@code config.yml} (e.g. "default", "brutal")
     * @param outcome     outcome to look up
     * @return configured weight (always ≥ 0)
     */
    public double profileWeight(@NotNull String profileName, Outcome outcome) {
        String profilePath = "profiles." + profileName + "." + outcome.configKey();
        if (config.contains(profilePath)) {
            return Math.max(0.0, config.getDouble(profilePath));
        }
        // Graceful fallback: use global outcomes section.
        return outcomeWeight(outcome);
    }

    /**
     * Returns {@code true} if the named profile is explicitly defined in the
     * config (at least one key present under {@code profiles.<name>}).
     */
    public boolean isProfileDefined(@NotNull String profileName) {
        return config.isConfigurationSection("profiles." + profileName);
    }

    // =========================================================================
    //  Physical machine settings
    // =========================================================================

    /**
     * Material of the core block used for physical machine interaction.
     * Defaults to {@link org.bukkit.Material#RESPAWN_ANCHOR}.
     */
    @NotNull
    public Material machineCoreBlock() {
        Material m = Material.matchMaterial(
                config.getString("machine.core-material", "RESPAWN_ANCHOR"));
        return m != null ? m : Material.RESPAWN_ANCHOR;
    }

    /** Hard cap on the number of machines that may be registered. */
    public int machineMaxRegistered() {
        return Math.max(1, config.getInt("machine.max-registered", 3));
    }

    /** Hard cap on simultaneous active animations across all machines. */
    public int machineMaxConcurrentAnimations() {
        return Math.max(1, config.getInt("machine.max-concurrent-animations", 3));
    }

    /** If {@code true}, creative-mode players cannot activate the machine. */
    public boolean machineBlockCreative() {
        return config.getBoolean("machine.block-creative", true);
    }

    /** If {@code true}, spectator-mode players cannot activate the machine. */
    public boolean machineBlockSpectator() {
        return config.getBoolean("machine.block-spectator", true);
    }

    /** If {@code true}, players with the "vanished" metadata key are blocked. */
    public boolean machineBlockVanished() {
        return config.getBoolean("machine.block-vanished", true);
    }

    /**
     * Minimum time in milliseconds between consecutive right-clicks from the
     * same player before the interaction is treated as spam. 0 disables the check.
     */
    public long machineAntiSpamGapMs() {
        return Math.max(0, config.getLong("machine.anti-spam-gap-ms", 500));
    }

    // =========================================================================
    //  Checkpoint / crash recovery
    // =========================================================================

    /**
     * If {@code true}, startup recovery will attempt to deliver items whose
     * checkpoint was found in the {@link com.voidmachine.checkpoint.CheckpointStore.State#DELIVERING}
     * state. This risks a rare double-delivery on a crash-during-delivery, but
     * avoids permanent item loss.
     *
     * <p>Default: {@code false} — log a warning and require admin review via
     * {@code /vm admin pending}.</p>
     */
    public boolean checkpointRedeliverOnDelivering() {
        return config.getBoolean("checkpoint.redeliver-on-delivering-state", false);
    }

    // =========================================================================
    //  Atmosphere
    // =========================================================================

    /**
     * Master toggle for idle ambient effects and attract-mode pulses.
     * When {@code false}, {@link com.voidmachine.animation.AmbientEffectScheduler}
     * is not started (or is stopped on reload). Defaults to {@code true}.
     */
    public boolean atmosphereEnabled() {
        return config.getBoolean("atmosphere.enabled", true);
    }

    // =========================================================================
    //  World-machine animation
    // =========================================================================

    /**
     * Ticks before the animation watchdog fires and force-aborts the transaction.
     * Prevents stuck animations from leaking machine locks indefinitely.
     */
    /**
     * Watchdog hard-abort timeout in ticks.
     * Must be significantly longer than the actual animation (ramp + tension + reveal hold).
     * Prevents stuck animations from leaking machine locks indefinitely.
     */
    public int worldAnimMaxDurationTicks() {
        return Math.max(60, config.getInt("world-animation.max-duration-ticks", 200));
    }

    /** Ticks between each animation update step. Minimum 2 for Bedrock safety. */
    public int worldAnimStepTicks() {
        return Math.max(2, config.getInt("world-animation.step-ticks", 2));
    }

    /**
     * Duration in ticks of the "tension lock" phase — boss bar frozen at 90%,
     * particles and sounds stopped, building maximum suspense.
     */
    public int worldAnimTensionLockTicks() {
        return Math.max(5, config.getInt("world-animation.tension-lock-ticks", 20));
    }

    /** Max particles spawned per animation step (Bedrock clients benefit from lower values). */
    public int worldAnimParticlesPerStep() {
        return Math.max(0, Math.min(40, config.getInt("world-animation.particles-per-step", 12)));
    }

    /** Max Display entities spawned per active transaction. */
    public int worldAnimMaxDisplayEntities() {
        return Math.max(1, Math.min(8, config.getInt("world-animation.max-display-entities", 4)));
    }

    /**
     * Radius (blocks) within which spectators receive action bar notifications
     * about an active ritual.
     */
    public double worldAnimSpectatorRadius() {
        return Math.max(0, config.getDouble("world-animation.spectator-radius", 24));
    }

    /**
     * Radius (blocks) within which spectators receive particle effects.
     * Should be ≤ the Minecraft view distance.
     */
    public double worldAnimSpectatorParticleRadius() {
        return Math.max(0, config.getDouble("world-animation.spectator-particle-radius", 32));
    }
}
