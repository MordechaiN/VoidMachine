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
import com.voidmachine.config.PluginConfig;
import com.voidmachine.interaction.ItemCaptureService;
import com.voidmachine.machine.MachineBlock;
import com.voidmachine.util.ItemValidator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 3-row staging GUI — explicit commit step before a VoidMachine ritual begins.
 *
 * <h3>Layout (27-slot, 3×9)</h3>
 * <pre>
 * [B][B][B][B][H][B][B][B][B]   row 0 — H = header / instruction item  (slot  4)
 * [B][B][B][B][I][B][B][B][B]   row 1 — I = input slot                 (slot 13)
 * [B][B][B][B][S][B][B][B][B]   row 2 — S = START button               (slot 22)
 * </pre>
 *
 * <h3>Design goals</h3>
 * <ul>
 *   <li>A player must understand what to do within 2 seconds — including children,
 *       mobile users, and controller / Bedrock players.</li>
 *   <li>ONE open slot (13) draws the eye immediately against the dark pane background.</li>
 *   <li>The header item (slot 4) provides explicit text instructions on hover.</li>
 *   <li>The START button (slot 22) is directly below the input slot — natural read order.</li>
 *   <li>Minimal pane count — 25 border panes, no inner accent layer.</li>
 * </ul>
 *
 * <h3>Interaction model</h3>
 * <b>Pre-commit</b>: input slot (13) is fully unrestricted — normal click, pick-up,
 * and shift-click all work naturally.  All other top-inventory slots are cancelled by
 * {@link StagingGuiListener} to prevent item theft or ghost cursors.  The player's own
 * inventory is fully interactive.  No special shift-click routing is required: slot 13
 * is the only non-pane slot in the top inventory, so Bukkit routes shift-clicks from
 * the player inventory directly to it on all platforms.
 *
 * <b>Post-commit</b>: once START is pressed, ALL inventory interactions are cancelled
 * (hard lock) until capture succeeds (GUI closes) or fails (GUI restored for retry).
 *
 * <h3>Stack clamping</h3>
 * Clamping happens at {@link #triggerStart} time, NOT at insert time.  If the inserted
 * stack exceeds {@link PluginConfig#maxInsertAmount()}, the excess is returned to the
 * player's inventory immediately before the WAL checkpoint.
 *
 * <h3>Cancel (ESC)</h3>
 * Any item in slot 13 is returned via {@link #returnAndClose(UUID)} when the player
 * closes without pressing START.
 *
 * <h3>Thread safety</h3>
 * All public methods must be called on the main thread.
 */
public final class StagingGui {

    // ── Layout constants ──────────────────────────────────────────────────────

    /** Center of row 0 — informational header item with hover instructions. */
    private static final int SLOT_HEADER = 4;

    /** Center of row 1 — the player places their sacrifice here. */
    static final int SLOT_INPUT  = 13;

    /** Center of row 2 — START button (BARRIER when inactive, NETHER_STAR when active). */
    static final int SLOT_START  = 22;

    /** Total inventory size: 3 rows × 9 columns = 27 slots. */
    private static final int INV_SIZE = 27;

    // ── Pre-built shared pane (constructed once, cloned on use) ──────────────

    /** Background filler for all non-functional slots — dark, minimal. */
    private static final ItemStack BORDER_PANE = hiddenPane(Material.BLACK_STAINED_GLASS_PANE);

    // ── State ─────────────────────────────────────────────────────────────────

    private final VoidMachinePlugin plugin;
    private final MessageManager    messages;
    private final PluginConfig      config;
    private final ItemCaptureService captureService;

    /** Active sessions keyed by player UUID. */
    private final ConcurrentHashMap<UUID, StagingSession> sessions = new ConcurrentHashMap<>();

    /**
     * Set of all open staging inventories. Checked by {@link StagingGuiListener}
     * to quickly identify whether the player's open view belongs to this GUI.
     */
    final Set<Inventory> activeInventories =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // =========================================================================
    //  Constructor
    // =========================================================================

    public StagingGui(@NotNull VoidMachinePlugin plugin,
                      @NotNull MessageManager messages,
                      @NotNull ItemCaptureService captureService) {
        this.plugin         = plugin;
        this.messages       = messages;
        this.config         = plugin.pluginConfig();
        this.captureService = captureService;
    }

    // =========================================================================
    //  Public API  (main thread only)
    // =========================================================================

    /**
     * Open the staging GUI for the player at the given machine.
     * No-op if a session is already open for this player.
     */
    public void open(@NotNull Player player, @NotNull MachineBlock machine) {
        UUID uuid = player.getUniqueId();
        if (sessions.containsKey(uuid)) return;

        Component title = messages.render("staging.gui.title");
        Inventory inv   = Bukkit.createInventory(null, INV_SIZE, title);

        fillLayout(inv);
        inv.setItem(SLOT_HEADER, buildHeaderItem(false));
        inv.setItem(SLOT_START,  buildStartButton(false));
        // SLOT_INPUT stays as AIR — the only open slot creates immediate visual clarity.

        sessions.put(uuid, new StagingSession(machine, inv));
        activeInventories.add(inv);
        player.openInventory(inv);
    }

    /**
     * Refresh the START button and header item based on whether slot 13 holds an item.
     * No-op if the session is in committed state.
     * Called by {@link StagingGuiListener} after any change to the input slot.
     */
    public void refreshStartButton(@NotNull UUID uuid) {
        StagingSession session = sessions.get(uuid);
        if (session == null || session.committed) return;
        boolean hasItem = !ItemValidator.isEmpty(session.inv.getItem(SLOT_INPUT));
        session.inv.setItem(SLOT_START,  buildStartButton(hasItem));
        session.inv.setItem(SLOT_HEADER, buildHeaderItem(hasItem));
    }

    /**
     * Attempt to begin the ritual. Called when the player clicks {@code SLOT_START}.
     *
     * <h3>Sequence</h3>
     * <ol>
     *   <li>Cursor item resolved: if the player holds an item on cursor when pressing
     *       START, it is moved to slot 13 (if empty) or returned to inventory.</li>
     *   <li>Stack clamping: if the sacrifice exceeds {@link PluginConfig#maxInsertAmount()},
     *       the excess is returned immediately and the clamped amount is used.</li>
     *   <li>Session marked {@code committed} — GUI freezes immediately (hard lock).</li>
     *   <li>{@link ItemCaptureService#captureFromGui} called synchronously.</li>
     *   <li>Success: session removed, GUI closed.</li>
     *   <li>Failure: GUI restored with the clamped item for retry.</li>
     * </ol>
     */
    public void triggerStart(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        StagingSession session = sessions.get(uuid);
        if (session == null || session.committed) return;

        // ── Resolve cursor item ───────────────────────────────────────────────
        ItemStack cursor = player.getItemOnCursor();
        if (!ItemValidator.isEmpty(cursor)) {
            if (ItemValidator.isEmpty(session.inv.getItem(SLOT_INPUT))) {
                session.inv.setItem(SLOT_INPUT, cursor);
            } else {
                giveOrDrop(player, cursor);
            }
            player.setItemOnCursor(null);
            player.updateInventory();
        }

        // ── Get sacrifice item ────────────────────────────────────────────────
        ItemStack input = session.inv.getItem(SLOT_INPUT);
        if (ItemValidator.isEmpty(input)) return;

        // ── Clamp to max insert amount ────────────────────────────────────────
        int maxInsert = Math.max(1, config.maxInsertAmount());
        final ItemStack sacrifice;
        if (input.getAmount() > maxInsert) {
            ItemStack excess = input.clone();
            excess.setAmount(input.getAmount() - maxInsert);
            giveOrDrop(player, excess);
            sacrifice = input.clone();
            sacrifice.setAmount(maxInsert);
            player.updateInventory();
        } else {
            sacrifice = input.clone();
        }

        // ── Lock GUI visually ─────────────────────────────────────────────────
        session.committed = true;
        session.inv.setItem(SLOT_INPUT,  buildProcessingPane());
        session.inv.setItem(SLOT_START,  buildStartButton(false));
        session.inv.setItem(SLOT_HEADER, buildProcessingHeader());

        // ── Delegate to capture service ───────────────────────────────────────
        captureService.captureFromGui(player, session.machine, sacrifice, () -> {
            // onConsumed: point-of-no-return.
            // Remove session BEFORE closing so InventoryCloseEvent sees
            // isStagingInventory() == false and skips returnAndClose.
            sessions.remove(uuid);
            activeInventories.remove(session.inv);
            if (player.isOnline()
                    && session.inv.equals(player.getOpenInventory().getTopInventory())) {
                player.closeInventory();
            }
        });

        // ── Restore if capture failed ─────────────────────────────────────────
        // captureFromGui is synchronous. If onConsumed fired, the session was already
        // removed (containsKey returns false). If still present, capture failed.
        if (sessions.containsKey(uuid)) {
            session.committed = false;
            session.inv.setItem(SLOT_INPUT,  sacrifice);
            session.inv.setItem(SLOT_START,  buildStartButton(true));
            session.inv.setItem(SLOT_HEADER, buildHeaderItem(true));
        }
    }

    /**
     * Return the item in {@code SLOT_INPUT} to the player and remove the session.
     * Called by {@link StagingGuiListener} when the player closes (ESC).
     */
    public void returnAndClose(@NotNull UUID uuid) {
        StagingSession session = sessions.remove(uuid);
        if (session == null) return;
        activeInventories.remove(session.inv);

        if (!session.committed) {
            ItemStack input = session.inv.getItem(SLOT_INPUT);
            if (!ItemValidator.isEmpty(input)) {
                Player player = plugin.getServer().getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    giveOrDrop(player, input);
                    player.updateInventory();
                }
            }
        }
    }

    /**
     * Remove and return the item in {@code SLOT_INPUT} for this player's session.
     * Returns {@code null} if no session or slot is empty.
     *
     * <p>Used by {@link com.voidmachine.interaction.PlayerDeathListener} to extract
     * the item for death-drop handling before calling {@link #closeSessionSilently}.</p>
     */
    @Nullable
    public ItemStack takeInputItem(@NotNull UUID uuid) {
        StagingSession session = sessions.get(uuid);
        if (session == null || session.committed) return null;
        ItemStack item = session.inv.getItem(SLOT_INPUT);
        if (ItemValidator.isEmpty(item)) return null;
        session.inv.setItem(SLOT_INPUT, null);
        return item;
    }

    /**
     * Close the session without returning the item.
     * Use when the caller has already handled the item (e.g. death listener).
     */
    public void closeSessionSilently(@NotNull UUID uuid) {
        StagingSession session = sessions.remove(uuid);
        if (session == null) return;
        activeInventories.remove(session.inv);
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player.isOnline()) {
            if (session.inv.equals(player.getOpenInventory().getTopInventory())) {
                player.closeInventory();
            }
        }
    }

    /**
     * Close all sessions and return items. Called from {@code VoidMachinePlugin.onDisable()}.
     */
    public void shutdown() {
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            returnAndClose(uuid);
        }
        sessions.clear();
        activeInventories.clear();
    }

    /** Returns {@code true} if the given inventory is an active staging GUI. */
    public boolean isStagingInventory(@NotNull Inventory inv) {
        return activeInventories.contains(inv);
    }

    /** Returns {@code true} if the player has an open staging session. */
    public boolean hasOpenSession(@NotNull UUID uuid) {
        return sessions.containsKey(uuid);
    }

    /**
     * Returns {@code true} if the player's staging session is committed
     * (START pressed; capture pending). All input is locked while committed.
     */
    public boolean isCommitted(@NotNull UUID uuid) {
        StagingSession session = sessions.get(uuid);
        return session != null && session.committed;
    }

    // =========================================================================
    //  Layout helpers
    // =========================================================================

    /**
     * Fill all non-functional slots with the dark border pane.
     * Skips {@link #SLOT_HEADER}, {@link #SLOT_INPUT}, and {@link #SLOT_START}.
     */
    private static void fillLayout(@NotNull Inventory inv) {
        for (int slot = 0; slot < INV_SIZE; slot++) {
            if (slot == SLOT_HEADER || slot == SLOT_INPUT || slot == SLOT_START) continue;
            inv.setItem(slot, BORDER_PANE.clone());
        }
    }

    /**
     * Header instruction item shown at the top-centre of the GUI.
     *
     * <p>Tooltip is visible (not hidden) so players can read the instructions on hover.
     * Changes text when the player places an item in the input slot.</p>
     */
    @NotNull
    private ItemStack buildHeaderItem(boolean hasItem) {
        String namePath = hasItem
                ? "staging.header-item.ready-name"
                : "staging.header-item.empty-name";
        String lorePath = hasItem
                ? "staging.header-item.ready-lore"
                : "staging.header-item.empty-lore";

        Component name = messages.render(namePath)
                .decoration(TextDecoration.ITALIC, false);
        List<Component> lore = messages.renderList(lorePath)
                .stream()
                .map(c -> c.decoration(TextDecoration.ITALIC, false))
                .toList();

        ItemStack is   = new ItemStack(Material.ENDER_EYE);
        ItemMeta  meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (!lore.isEmpty()) meta.lore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    /** Header item shown while capture is pending — replaces instruction text. */
    @NotNull
    private static ItemStack buildProcessingHeader() {
        ItemStack is   = new ItemStack(Material.ENDER_EYE);
        ItemMeta  meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(
                    Component.text("The ritual begins…", NamedTextColor.DARK_PURPLE)
                             .decoration(TextDecoration.ITALIC, false));
            is.setItemMeta(meta);
        }
        return is;
    }

    @NotNull
    private ItemStack buildStartButton(boolean active) {
        Material mat      = active ? Material.NETHER_STAR : Material.BARRIER;
        String   namePath = active ? "staging.start-button.active-name"
                                   : "staging.start-button.inactive-name";
        String   lorePath = active ? "staging.start-button.active-lore"
                                   : "staging.start-button.inactive-lore";

        Component name = messages.render(namePath)
                .decoration(TextDecoration.ITALIC, false);
        List<Component> lore = messages.renderList(lorePath)
                .stream()
                .map(c -> c.decoration(TextDecoration.ITALIC, false))
                .toList();

        ItemStack is   = new ItemStack(mat);
        ItemMeta  meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (!lore.isEmpty()) meta.lore(lore);
            if (active) {
                // Enchant-glow override — purple sheen on NETHER_STAR makes the
                // START button feel alive and important. No actual enchantment applied.
                meta.setEnchantmentGlintOverride(true);
            }
            is.setItemMeta(meta);
        }
        return is;
    }

    /** Locked visual placed in the input slot immediately when START is pressed. */
    @NotNull
    private static ItemStack buildProcessingPane() {
        ItemStack is   = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta  meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(
                    Component.text("Processing…", NamedTextColor.GRAY)
                             .decoration(TextDecoration.ITALIC, false));
            is.setItemMeta(meta);
        }
        return is;
    }

    /** Creates a glass pane with a hidden tooltip — background filler only. */
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

    private void giveOrDrop(@NotNull Player player, @NotNull ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (overflow.isEmpty()) return;
        for (ItemStack drop : overflow.values()) {
            player.getLocation().getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    // =========================================================================
    //  Session record
    // =========================================================================

    private static final class StagingSession {

        final MachineBlock machine;
        final Inventory    inv;

        /**
         * Set {@code true} the moment START is clicked.
         * While {@code true} ALL inventory interactions are cancelled.
         * Reset to {@code false} if {@link ItemCaptureService#captureFromGui} fails.
         */
        volatile boolean committed = false;

        StagingSession(@NotNull MachineBlock machine, @NotNull Inventory inv) {
            this.machine = machine;
            this.inv     = inv;
        }
    }
}
