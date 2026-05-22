/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.util;

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.config.PluginConfig;
import com.voidmachine.core.Outcome;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Best-effort dispatch of sounds and particles. All lookups tolerate misconfigured
 * keys: an unknown sound or particle simply degrades to silence rather than
 * throwing.
 */
public final class Effects {

    private final VoidMachinePlugin plugin;
    private final PluginConfig config;

    public Effects(VoidMachinePlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void reload() {
        // No cached state — config is queried on every call.
    }

    public void play(Player player, String key) {
        PluginConfig.SoundDef sound = config.sound(key);
        if (sound == null) return;
        playSound(player, sound);
    }

    public void playReveal(Player player, Outcome outcome) {
        String soundKey = switch (outcome) {
            case DESTROYED -> "reveal-destroy";
            case RETURNED -> "reveal-return";
            case DOUBLED -> "reveal-double";
            case TRIPLED -> "reveal-triple";
            case JACKPOT_X5 -> "reveal-jackpot";
        };
        play(player, soundKey);

        if (outcome == Outcome.JACKPOT_X5) {
            PluginConfig.SoundDef global = config.sound("global-jackpot");
            if (global != null) {
                broadcastSound(global);
            }
        }

        if (!config.particlesEnabled()) return;
        String particleKey = switch (outcome) {
            case DESTROYED -> "destroy";
            case RETURNED -> "return";
            case DOUBLED, TRIPLED -> "multiply";
            case JACKPOT_X5 -> "jackpot";
        };
        spawnParticles(player.getLocation(), particleKey);
    }

    public void spawnParticles(Location location, String key) {
        if (!config.particlesEnabled()) return;
        PluginConfig.ParticleDef def = config.particle(key);
        if (def == null || location.getWorld() == null) return;
        Particle particle = matchParticle(def.key());
        if (particle == null) return;
        location.getWorld().spawnParticle(particle,
                location.clone().add(0.0, 1.2, 0.0),
                def.count(), def.offset(), def.offset(), def.offset(), 0.0);
    }

    private void playSound(Player player, PluginConfig.SoundDef def) {
        // The String overload accepts any namespaced sound key, including
        // resource-pack-defined ones, and is forward-compatible across the
        // recent Sound-enum-to-Registry transition in the Paper API.
        player.playSound(player.getLocation(), def.key(), def.volume(), def.pitch());
    }

    private void broadcastSound(PluginConfig.SoundDef def) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            p.playSound(p.getLocation(), def.key(), def.volume(), def.pitch());
        }
    }

    @SuppressWarnings("deprecation")
    private static Particle matchParticle(String key) {
        try {
            return Particle.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
