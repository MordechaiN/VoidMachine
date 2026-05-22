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

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.config.MessageManager;
import com.voidmachine.core.Outcome;
import com.voidmachine.core.Transaction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cinematic 3-row (27-slot) ritual GUI shown during a VoidMachine transaction.
 *
 * <h3>Design philosophy</h3>
 * Drama comes from <em>outside</em> the inventory: boss bar, sounds, world particles,
 * and lightning. This GUI is a dark, focused ritual chamber — not a slot machine.
 * A player on any platform (Java, Bedrock, controller, touch) must understand the
 * result within 2 seconds of it appearing.
 *
 * <h3>Layout (27-slot, 3×9)</h3>
 * <pre>
 *   [B][B][B][B][B][B][B][B][B]   row 0 — dark border
 *   [B][B][B][B][C][B][B][B][B]   row 1 — C = ritual status pane (slot 13)
 *   [B][B][B][B][B][B][B][B][B]   row 2 — dark border
 * </pre>
 *
 * <h3>Phases</h3>
 * <ol>
 *   <li><b>RAMP</b> (via {@link AnimationPipeline} step ticker) — slot 13 shows
 *       "⬛ The Void stirs…". Boss bar and world sounds carry all the tension.
 *       The GUI is deliberately static; no slot-machine spinning.</li>
 *   <li><b>TENSION</b> (via {@link #onTension}) — slot 13 dims to ". . ." silence.
 *       Mirrors the boss bar freeze at 90%.</li>
 *   <li><b>REVEAL</b> (via {@link #onReveal}) — border panes shift to the outcome
 *       colour; slot 13 shows a bold, unambiguous outcome item with large readable
 *       name: CONSUMED / RETURNED / DOUBLED / TRIPLED / ★ JACKPOT.</li>
 * </ol>
 *
 * <h3>Core rule — outcome is pre-determined</h3>
 * {@link AnimationPipeline} rolls the outcome <em>before</em> calling {@link #open}.
 * This GUI is <strong>presentation only</strong> — it dramatises a result that is
 * already committed server-side. Nothing displayed here influences the outcome.
 *
 * <h3>Bedrock / Geyser</h3>
 * Static pane layout renders identically on Java and Bedrock. No dynamic title changes
 * (these trigger close/reopen flicker on Bedrock). No inventory animations. Works
 * correctly with controller and touch input — all slots are cancelled by
 * {@link CinematicGuiListener}, so input precision is irrelevant.
 *
 * <h3>Thread safety</h3>
 * All public methods must be called on the main thread.
 */
public final class CinematicGui {

    // ── Layout constants ──────────────────────────────────────────────────────

    /** Single ritual status pane — center of the middle row. */
    private static final int SLOT_STATUS = 13;

    // ── Border colours per outcome (reveal phase) ─────────────────────────────

    private static final Material[] REVEAL_BORDER = {
        Material.RED_STAINED_GLASS_PANE,     // DESTROYED
        Material.GRAY_STAINED_GLASS_PANE,    // RETURNED
        Material.GREEN_STAINED_GLASS_PANE,   // DOUBLED
        Material.YELLOW_STAINED_GLASS_PANE,  // TRIPLED
        Material.MAGENTA_STAINED_GLASS_PANE, // JACKPOT_X5
    };

    // ── Shared static pane (cloned on use) ───────────────────────────────────

    private static final ItemStack BORDER_PANE = hiddenPane(Material.BLACK_STAINED_GLASS_PANE);

    // ── State ─────────────────────────────────────────────────────────────────

    private final VoidMachinePlugin plugin;
    private final MessageManager    messages;

    private final ConcurrentHashMap<UUID, RitualSession> sessions = new ConcurrentHashMap<>();

    /** All open VM inventories — fast identity check by {@link CinematicGuiListener}. */
    final Set<Inventory> activeInventories =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // =========================================================================
    //  Constructor
    // =========================================================================

    public CinematicGui(@NotNull VoidMachinePlugin plugin, @NotNull MessageManager messages) {
        this.plugin   = plugin;
        this.messages = messages;
    }

    // =========================================================================
    //  Public API  (called by AnimationPipeline — main thread only)
    // =========================================================================

    /**
     * Open the ritual GUI for the player.
     *
     * <p>The outcome is already decided by {@link AnimationPipeline}. This GUI
     * presents a static dark ritual chamber while the boss bar and world effects
     * carry the drama.</p>
     *
     * @param player       ritual participant
     * @param tx           active transaction (held for pipeline compatibility)
     * @param outcome      pre-rolled outcome — will be used at {@link #onReveal}
     * @param outputAmount items to be returned (0 for DESTROYED)
     */
    public void open(@NotNull org.bukkit.entity.Player player, @NotNull Transaction tx,
                     @NotNull Outcome outcome, int outputAmount) {

        Component title = messages.render("animation.gui.title");
        Inventory inv   = Bukkit.createInventory(null, 27, title);

        // Fill all 27 slots with dark border panes.
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, BORDER_PANE.clone());
        }

        // Center slot: ritual status pane — "the Void stirs".
        inv.setItem(SLOT_STATUS, buildRampPane());

        sessions.put(player.getUniqueId(), new RitualSession(inv));
        activeInventories.add(inv);
        player.openInventory(inv);
    }

    /**
     * Called each ramp step by {@link AnimationPipeline}.
     *
     * <p>The GUI is intentionally static during the ramp — the boss bar, sounds,
     * and world particles carry all the energy. This is a no-op; the method exists
     * to preserve the {@link AnimationPipeline} call surface.</p>
     *
     * @param uuid     player UUID
     * @param step     current step index
     * @param progress ramp progress 0.0 → ~0.9
     */
    public void onRampStep(@NotNull UUID uuid, int step, float progress) {
        // Static during ramp — drama is outside the inventory.
    }

    /**
     * Transition to the tension (silence) phase.
     * Dims the status pane to ". . ." — mirrors the boss bar freeze.
     *
     * @param uuid player UUID
     */
    public void onTension(@NotNull UUID uuid) {
        RitualSession session = sessions.get(uuid);
        if (session == null) return;
        session.inv.setItem(SLOT_STATUS, buildTensionPane());
    }

    /**
     * Show the outcome in the GUI.
     *
     * <p>Border panes shift to the outcome colour (red / grey / green / gold / magenta).
     * The center slot becomes a bold, unambiguous outcome item. Text is large enough
     * to read on any platform — no hovering required on Bedrock / controller.</p>
     *
     * @param uuid         player UUID
     * @param outcome      pre-rolled outcome (same value passed to {@link #open})
     * @param outputAmount items returned (0 for DESTROYED)
     */
    public void onReveal(@NotNull UUID uuid, @NotNull Outcome outcome, int outputAmount) {
        RitualSession session = sessions.get(uuid);
        if (session == null) return;

        // Shift all border slots to the outcome colour.
        Material    revealMat  = REVEAL_BORDER[Math.min(outcome.ordinal(), REVEAL_BORDER.length - 1)];
        ItemStack   revealPane = hiddenPane(revealMat);
        for (int i = 0; i < 27; i++) {
            if (i == SLOT_STATUS) continue;
            session.inv.setItem(i, revealPane.clone());
        }

        // Center: bold, platform-readable outcome item.
        session.inv.setItem(SLOT_STATUS, buildRevealItem(outcome, outputAmount));
    }

    /**
     * Close the GUI for this player. Safe to call when no session exists (no-op).
     */
    public void close(@NotNull UUID uuid) {
        RitualSession session = sessions.remove(uuid);
        if (session == null) return;

        activeInventories.remove(session.inv);

        org.bukkit.entity.Player p = plugin.getServer().getPlayer(uuid);
        if (p != null && p.isOnline()) {
            if (session.inv.equals(p.getOpenInventory().getTopInventory())) {
                p.closeInventory();
            }
        }
    }

    /** Close all open sessions. Called from {@code VoidMachinePlugin.onDisable()}. */
    public void shutdown() {
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            close(uuid);
        }
        sessions.clear();
        activeInventories.clear();
    }

    /**
     * Returns {@code true} if {@code inv} is an active VM cinematic GUI.
     * Used by {@link CinematicGuiListener} to identify and cancel interactions.
     */
    public boolean isVmInventory(@NotNull Inventory inv) {
        return activeInventories.contains(inv);
    }

    // =========================================================================
    //  Item builders
    // =========================================================================

    /** Status pane shown during the ramp phase. */
    @NotNull
    private static ItemStack buildRampPane() {
        ItemStack is   = new ItemStack(Material.ENDER_EYE);
        ItemMeta  meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(
                    Component.text("⬛  The Void stirs…", NamedTextColor.DARK_PURPLE)
                             .decoration(TextDecoration.ITALIC, false));
            is.setItemMeta(meta);
        }
        return is;
    }

    /** Status pane shown during the tension (silence) phase. */
    @NotNull
    private static ItemStack buildTensionPane() {
        ItemStack is   = new ItemStack(Material.ENDER_EYE);
        ItemMeta  meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(
                    Component.text(". . .", NamedTextColor.DARK_GRAY)
                             .decoration(TextDecoration.ITALIC, false));
            is.setItemMeta(meta);
        }
        return is;
    }

    /**
     * Bold outcome item shown at reveal.
     *
     * <p>Design rules:
     * <ul>
     *   <li>Name must be readable at a glance — no ambiguity.</li>
     *   <li>Lore shows a brief flavour line and the item count where relevant.</li>
     *   <li>Distinct materials per outcome tier so icon alone conveys result on Bedrock.</li>
     *   <li>JACKPOT uses {@link ItemMeta#setEnchantmentGlintOverride(Boolean)} for the
     *       enchant-glow purple sheen — no actual enchantment applied.</li>
     * </ul>
     */
    @NotNull
    private static ItemStack buildRevealItem(@NotNull Outcome outcome, int outputAmount) {
        return switch (outcome) {
            case DESTROYED  -> revealItem(
                    Material.BARRIER,
                    Component.text("✗  CONSUMED", NamedTextColor.DARK_RED)
                             .decorate(TextDecoration.BOLD),
                    List.of(Component.text("The Void took everything.", NamedTextColor.GRAY)),
                    false);

            case RETURNED   -> revealItem(
                    Material.ENDER_PEARL,
                    Component.text("↩  RETURNED", NamedTextColor.WHITE)
                             .decorate(TextDecoration.BOLD),
                    List.of(Component.text("Your offering was refused.", NamedTextColor.GRAY)),
                    false);

            case DOUBLED    -> revealItem(
                    Material.EMERALD,
                    Component.text("✦  DOUBLED  ×2", NamedTextColor.GREEN)
                             .decorate(TextDecoration.BOLD),
                    List.of(Component.text(outputAmount + " items returned.", NamedTextColor.GREEN)),
                    false);

            case TRIPLED    -> revealItem(
                    Material.NETHER_STAR,
                    Component.text("★  TRIPLED  ×3", NamedTextColor.GOLD)
                             .decorate(TextDecoration.BOLD),
                    List.of(Component.text(outputAmount + " items returned.", NamedTextColor.GOLD)),
                    false);

            case JACKPOT_X5 -> revealItem(
                    Material.NETHER_STAR,
                    Component.text("★★  JACKPOT  ×5  ★★", NamedTextColor.LIGHT_PURPLE)
                             .decorate(TextDecoration.BOLD),
                    List.of(Component.text("THE VOID AWAKENS.", NamedTextColor.DARK_PURPLE)
                                     .decorate(TextDecoration.BOLD),
                            Component.text(outputAmount + " items returned.", NamedTextColor.LIGHT_PURPLE)),
                    true); // enchant glow
        };
    }

    @NotNull
    private static ItemStack revealItem(@NotNull Material mat,
                                        @NotNull Component name,
                                        @NotNull List<Component> lore,
                                        boolean glint) {
        ItemStack is   = new ItemStack(mat);
        ItemMeta  meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                    .map(c -> c.decoration(TextDecoration.ITALIC, false))
                    .toList());
            if (glint) {
                // Enchant-glow override (1.20.5+): purple sheen without any actual enchantment.
                meta.setEnchantmentGlintOverride(true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            }
            is.setItemMeta(meta);
        }
        return is;
    }

    /** Glass pane with hidden tooltip — background filler only. */
    @NotNull
    private static ItemStack hiddenPane(@NotNull Material mat) {
        ItemStack is   = new ItemStack(mat);
        ItemMeta  meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
            is.setItemMeta(meta);
        }
        return is;
    }

    // =========================================================================
    //  Session
    // =========================================================================

    private static final class RitualSession {
        final Inventory inv;

        RitualSession(@NotNull Inventory inv) {
            this.inv = inv;
        }
    }
}
