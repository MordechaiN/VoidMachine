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
import com.voidmachine.config.PluginConfig;
import com.voidmachine.util.Effects;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * The cinematic spinner that runs between activation and reveal.
 *
 * <p>The runner cycles the slots adjacent to the sacrifice slot through the
 * configured materials, plays the "tick" sound, and spawns the "tick" particle
 * around the player. It deliberately throttles updates to every {@code step-interval-ticks}
 * to avoid packet spam, particularly important under Geyser where every
 * inventory update is forwarded as a Bedrock container update.</p>
 */
public final class AnimationRunner {

    private final VoidMachinePlugin plugin;
    private final PluginConfig config;
    private final Effects effects;

    public AnimationRunner(VoidMachinePlugin plugin, PluginConfig config, Effects effects) {
        this.plugin = plugin;
        this.config = config;
        this.effects = effects;
    }

    public BukkitTask run(Player player, Inventory inventory, int centerSlot, Runnable onComplete) {
        List<Material> cycle = materials();
        int rows = inventory.getSize() / 9;
        List<Integer> ring = ringAround(centerSlot, rows);

        int steps = config.animationSteps();
        int interval = config.animationIntervalTicks();
        int reveal = config.animationRevealDelayTicks();

        BukkitRunnable task = new BukkitRunnable() {
            int step;

            @Override
            public void run() {
                if (!player.isOnline() || inventory.getViewers().isEmpty()) {
                    cancel();
                    onComplete.run();
                    return;
                }
                if (step >= steps) {
                    cancel();
                    // Defer the final reveal so the player feels the tension.
                    plugin.getServer().getScheduler().runTaskLater(plugin, onComplete, reveal);
                    return;
                }
                Material m = cycle.get(step % cycle.size());
                ItemStack stack = new ItemStack(m);
                for (int slot : ring) {
                    inventory.setItem(slot, stack.clone());
                }
                effects.play(player, "tick");
                effects.spawnParticles(player.getLocation(), "tick");
                step++;
            }
        };
        return task.runTaskTimer(plugin, 0L, interval);
    }

    private List<Material> materials() {
        List<Material> list = new ArrayList<>();
        for (String s : config.guiAnimationMaterials()) {
            Material m = Material.matchMaterial(s);
            if (m != null) list.add(m);
        }
        if (list.isEmpty()) {
            list.add(Material.PURPLE_STAINED_GLASS_PANE);
            list.add(Material.BLACK_STAINED_GLASS_PANE);
        }
        return list;
    }

    private List<Integer> ringAround(int center, int rows) {
        List<Integer> out = new ArrayList<>(8);
        int row = center / 9;
        int col = center % 9;
        int[][] offsets = {
                {-1, -1}, {-1, 0}, {-1, 1},
                { 0, -1},          { 0, 1},
                { 1, -1}, { 1, 0}, { 1, 1}
        };
        for (int[] o : offsets) {
            int r = row + o[0];
            int c = col + o[1];
            if (r < 0 || r >= rows || c < 0 || c > 8) continue;
            out.add(r * 9 + c);
        }
        return out;
    }
}
