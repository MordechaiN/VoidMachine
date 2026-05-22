/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.animation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/**
 * The seven void-themed symbols that spin on the {@link CinematicGui} reels.
 *
 * <p>Each constant holds a pre-built {@link ItemStack} cached at class-load time.
 * Always call {@link #buildStack()} to obtain a clone safe for placing in an
 * inventory — never share the raw cached instance, which Bukkit may mutate.</p>
 *
 * <h3>Symbol ordering (ordinal = reel index)</h3>
 * <pre>
 *   0  VOID_ESSENCE   — purple dye      (common)
 *   1  ECHO_FRAGMENT  — echo shard      (uncommon)
 *   2  DARK_CRYSTAL   — amethyst shard  (uncommon)
 *   3  RUNIC_DUST     — glowstone dust  (common)
 *   4  ABYSS_FLAME    — blaze powder    (uncommon)
 *   5  VOID_EYE       — ender eye       (rare visual)
 *   6  JACKPOT_STAR   — nether star     (jackpot)
 * </pre>
 */
public enum ReelSymbol {

    VOID_ESSENCE (Material.PURPLE_DYE,     "◈ Void Essence"),
    ECHO_FRAGMENT(Material.ECHO_SHARD,     "✦ Echo Fragment"),
    DARK_CRYSTAL (Material.AMETHYST_SHARD, "◆ Dark Crystal"),
    RUNIC_DUST   (Material.GLOWSTONE_DUST, "∘ Runic Dust"),
    ABYSS_FLAME  (Material.BLAZE_POWDER,   "▶ Abyss Flame"),
    VOID_EYE     (Material.ENDER_EYE,      "◉ Void Eye"),
    JACKPOT_STAR (Material.NETHER_STAR,    "★ Void Star");

    // ── Static helpers ────────────────────────────────────────────────────────

    private static final ReelSymbol[] VALUES = values();

    /**
     * Total symbol count.
     * Use instead of {@code values().length} to avoid the defensive array copy.
     */
    public static int count() {
        return VALUES.length;
    }

    /**
     * Wrap-safe ordinal lookup.
     * {@code byIndex(-1)} returns the last symbol; {@code byIndex(7)} wraps to 0.
     */
    @NotNull
    public static ReelSymbol byIndex(int idx) {
        return VALUES[Math.floorMod(idx, VALUES.length)];
    }

    // ── Instance ──────────────────────────────────────────────────────────────

    private final Material  material;
    private final ItemStack cached; // pre-built — never expose directly

    ReelSymbol(@NotNull Material material, @NotNull String displayName) {
        this.material = material;
        this.cached   = buildItem(material, displayName);
    }

    @NotNull
    public Material material() {
        return material;
    }

    /**
     * Returns a {@linkplain ItemStack#clone() clone} of the cached stack.
     * Safe to place in an inventory without mutating the shared template.
     */
    @NotNull
    public ItemStack buildStack() {
        return cached.clone();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @NotNull
    private static ItemStack buildItem(@NotNull Material mat, @NotNull String name) {
        ItemStack is   = new ItemStack(mat);
        ItemMeta  meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name)
                    .decoration(TextDecoration.ITALIC, false));
            // Tooltip visible — player can read symbol names for lore flavour.
            is.setItemMeta(meta);
        }
        return is;
    }
}
