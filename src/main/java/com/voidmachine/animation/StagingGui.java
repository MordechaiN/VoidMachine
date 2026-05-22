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
 * 5-row staging GUI — explicit commit step before a VoidMachine ritual begins.
 *
 * <h3>Layout (45-slot, 5×9)</h3>
 * <pre>
 * [B][B][B][B][B][B][B][B][B]   row 0 — border
 * [B][A][A][A][A][A][A][A][B]   row 1 — accents
 * [B][A][A][A][I][A][A][A][B]   row 2 — I = input slot (22)
 * [B][A][A][A][A][A][A][A][B]   row 3 — accents
 * [B][B][B][B][S][B][B][B][B]   row 4 — S = START button (40)
 * </pre>
 *
 * <h3>Interaction model</h3>
 * <b>Pre-commit</b>: the input slot (22) is completely unrestricted — normal click,
 * pick-up, and shift-click all work naturally.  Pane and border slots are cancelled by
 * {@link StagingGuiListener} to prevent item theft or ghost cursors from pane-swaps,
 * but the player's own inventory is fully interactive.  No special shift-click routing
 * is performed: since slot 22 is the only non-pane slot in the top inventory, Bukkit
 * naturally routes shift-clicks from the player's inventory directly to it.
 * This design works correctly on Java, Bedrock (Geyser), controller, and touch inputs.
 *
 * <b>Post-commit</b>: once START is pressed, ALL inventory interactions are cancelled
 * (hard lock) until capture either succeeds (GUI closes) or fails (GUI restored).
 *
 * <h3>Stack clamping</h3>
 * Clamping happens at {@link #triggerStart} time, NOT at insert time.
 * If the inserted stack exceeds {@link PluginConfig#maxInsertAmount()}, the excess is
 * returned to the player's inventory immediately before the WAL checkpoint.
 * Pre-commit insertion is never intercepted or modified.
 *
 * <h3>Cancel (ESC)</h3>
 * When the player closes the GUI without pressing START, any item in slot 22
 * is returned via {@link #returnAndClose(UUID)}.
 *
 * <h3>Thread safety</h3>
 * All public methods must be called on the main thread.
 */
public final class StagingGui {

    // ── Layout constants ──────────────────────────────────────────────────────

    /** Slot where the player places their sacrifice — center of row 2 (slot 18+4). */
    static final int SLOT_INPUT = 22;

    /** Slot of the START button — center of row 4 (slot 36+4). */
    static final int SLOT_START = 40;

    // ── Pre-built shared panes (constructed once, cloned on use) ─────────────

    private static final ItemStack BORDER_PANE = hiddenPane(Material.BLACK_STAINED_GLASS_PANE);
    private static final ItemStack ACCENT_PANE = hiddenPane(Material.PURPLE_STAINED_GLASS_PANE);

    // ── State ─────────────────────────────────────────────────────────────────

    private final VoidMachinePlugin plugin;
    private final MessageManager messages;
    private final PluginConfig config;
    private final ItemCaptureService captureService;

    /** Active sessions keyed by player UUID. */
    private final ConcurrentHashMap<UUID, StagingSession> sessions = new ConcurrentHashMap<>();

    /**
     * Set of all open staging inventories. Checked by {@link StagingGuiListener} to
     * quickly identify whether the player's open view belongs to us.
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
        Inventory inv   = Bukkit.createInventory(null, 45, title);

        fillLayout(inv);
        inv.setItem(SLOT_START, buildStartButton(false));

        sessions.put(uuid, new StagingSession(machine, inv));
        activeInventories.add(inv);
        player.openInventory(inv);
    }

    /**
     * Refresh the START button based on whether slot 22 currently holds an item.
     * No-op if the session is in committed state.
     * Called by {@link StagingGuiListener} after any change to the input slot.
     */
    public void refreshStartButton(@NotNull UUID uuid) {
        StagingSession session = sessions.get(uuid);
        if (session == null || session.committed) return;
        boolean hasItem = !ItemValidator.isEmpty(session.inv.getItem(SLOT_INPUT));
        session.inv.setItem(SLOT_START, buildStartButton(hasItem));
    }

    /**
     * Attempt to begin the ritual. Called when the player clicks {@code SLOT_START}.
     *
     * <h3>Sequence</h3>
     * <ol>
     *   <li>Cursor item resolved: if player holds an item on cursor when pressing START,
     *       it is moved to slot 22 (if empty) or returned to inventory (if occupied).</li>
     *   <li>Stack clamping: if the sacrifice exceeds {@link PluginConfig#maxInsertAmount()},
     *       the excess is returned immediately and the clamped amount is used.</li>
     *   <li>Session is marked {@code committed} — GUI freezes immediately (hard lock).</li>
     *   <li>{@link ItemCaptureService#captureFromGui} is called synchronously.</li>
     *   <li>If {@code onConsumed} fired (success): session removed, GUI closed.</li>
     *   <li>If session still exists (failure): GUI restored with the clamped item for retry.</li>
     * </ol>
     */
    public void triggerStart(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        StagingSession session = sessions.get(uuid);
        if (session == null || session.committed) return;

        // ── Resolve cursor item ───────────────────────────────────────────────
        // A player may have left-clicked slot 22 to pick up the item just before
        // pressing START — the item is now on the cursor, not in slot 22.
        // Move it back to slot 22 (if empty) or return it to the player's inventory.
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
        if (ItemValidator.isEmpty(input)) return; // nothing placed — ignore click

        // ── Clamp to max insert amount (return excess immediately) ────────────
        // Clamping is deferred to press-time so pre-commit insertion is never
        // intercepted. All platforms (Java / Bedrock / controller / touch) can
        // place items naturally; the excess is returned visibly on START press.
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
        session.inv.setItem(SLOT_INPUT, buildProcessingPane());
        session.inv.setItem(SLOT_START, buildStartButton(false));

        // ── Delegate to capture service ───────────────────────────────────────
        captureService.captureFromGui(player, session.machine, sacrifice, () -> {
            // ── onConsumed: point-of-no-return ───────────────────────────────
            // Remove session BEFORE closing inventory so the InventoryCloseEvent
            // sees isStagingInventory() == false and skips returnAndClose.
            sessions.remove(uuid);
            activeInventories.remove(session.inv);
            if (player.isOnline()
                    && session.inv.equals(player.getOpenInventory().getTopInventory())) {
                player.closeInventory();
            }
        });

        // ── Restore if capture failed ─────────────────────────────────────────
        // captureFromGui is synchronous. If onConsumed fired, the session was removed
        // (sessions.containsKey returns false). If the session is still present,
        // capture failed — restore the clamped sacrifice item for retry.
        if (sessions.containsKey(uuid)) {
            session.committed = false;
            session.inv.setItem(SLOT_INPUT, sacrifice);
            session.inv.setItem(SLOT_START, buildStartButton(true));
        }
    }

    /**
     * Return the item in {@code SLOT_INPUT} to the player and remove the session.
     * Called by {@link StagingGuiListener} when the player closes the GUI (ESC).
     *
     * <p>No-op if no session exists. If the session is {@code committed}, the slot
     * holds a processing placeholder — no item is returned.</p>
     */
    public void returnAndClose(@NotNull UUID uuid) {
        StagingSession session = sessions.remove(uuid);
        if (session == null) return;
        activeInventories.remove(session.inv);

        // If committed, the real item is either captured (session was already removed
        // by onConsumed) or restored to slot 22 (committed=false). Either way, safe to skip.
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
     * Remove and return the item currently in {@code SLOT_INPUT} for this player's session.
     * Returns {@code null} if no session or slot is empty.
     *
     * <p>Used by {@link com.voidmachine.interaction.PlayerDeathListener} to extract the
     * item for proper death-loot handling before calling {@link #closeSessionSilently}.</p>
     */
    @Nullable
    public ItemStack takeInputItem(@NotNull UUID uuid) {
        StagingSession session = sessions.get(uuid);
        if (session == null || session.committed) return null; // committed = item captured/processing
        ItemStack item = session.inv.getItem(SLOT_INPUT);
        if (ItemValidator.isEmpty(item)) return null;
        session.inv.setItem(SLOT_INPUT, null);
        return item;
    }

    /**
     * Close the session <em>without</em> returning the item in slot 22.
     * Use when the caller has already handled the item (e.g. death listener added it to drops).
     */
    public void closeSessionSilently(@NotNull UUID uuid) {
        StagingSession session = sessions.remove(uuid);
        if (session == null) return;
        activeInventories.remove(session.inv);
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player.isOnline()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (session.inv.equals(top)) {
                player.closeInventory();
            }
        }
    }

    /**
     * Close all sessions and return items. Called from
     * {@code VoidMachinePlugin.onDisable()}.
     */
    public void shutdown() {
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            returnAndClose(uuid);
        }
        sessions.clear();
        activeInventories.clear();
    }

    /**
     * Returns {@code true} if the given inventory is an active staging GUI.
     */
    public boolean isStagingInventory(@NotNull Inventory inv) {
        return activeInventories.contains(inv);
    }

    /**
     * Returns {@code true} if the player has an open staging session.
     * Used by {@link com.voidmachine.interaction.MachineInteractionListener} as a gate.
     */
    public boolean hasOpenSession(@NotNull UUID uuid) {
        return sessions.containsKey(uuid);
    }

    /**
     * Returns {@code true} if the player's staging session is in committed state
     * (START was pressed; capture in progress). All input is locked while committed.
     */
    public boolean isCommitted(@NotNull UUID uuid) {
        StagingSession session = sessions.get(uuid);
        return session != null && session.committed;
    }

    // =========================================================================
    //  Layout helpers
    // =========================================================================

    /**
     * Fill all non-special slots.
     * Row 0 / row 4 / column 0 / column 8 → border; otherwise → accent.
     * {@code SLOT_INPUT} and {@code SLOT_START} are skipped.
     */
    private static void fillLayout(@NotNull Inventory inv) {
        for (int slot = 0; slot < 45; slot++) {
            if (slot == SLOT_INPUT || slot == SLOT_START) continue;
            int row = slot / 9;
            int col = slot % 9;
            boolean isBorder = (row == 0 || row == 4 || col == 0 || col == 8);
            inv.setItem(slot, isBorder ? BORDER_PANE.clone() : ACCENT_PANE.clone());
        }
    }

    @NotNull
    private ItemStack buildStartButton(boolean active) {
        Material mat   = active ? Material.NETHER_STAR : Material.BARRIER;
        String namePath = active ? "staging.start-button.active-name"
                                 : "staging.start-button.inactive-name";
        String lorePath = active ? "staging.start-button.active-lore"
                                 : "staging.start-button.inactive-lore";

        Component name = messages.render(namePath)
                .decoration(TextDecoration.ITALIC, false);
        List<Component> lore = messages.renderList(lorePath)
                .stream()
                .map(c -> c.decoration(TextDecoration.ITALIC, false))
                .toList();

        ItemStack is  = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (!lore.isEmpty()) meta.lore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    /** Locked visual placed in slot 22 immediately when START is pressed. */
    @NotNull
    private static ItemStack buildProcessingPane() {
        ItemStack is  = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(
                    Component.text("Processing…", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false));
            is.setItemMeta(meta);
        }
        return is;
    }

    @NotNull
    private static ItemStack hiddenPane(@NotNull Material mat) {
        ItemStack is  = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
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
        final Inventory inv;

        /**
         * Set to {@code true} the moment START is clicked.
         * While {@code true}, ALL inventory interactions are cancelled.
         * Reset to {@code false} if {@link ItemCaptureService#captureFromGui} fails.
         */
        volatile boolean committed = false;

        StagingSession(@NotNull MachineBlock machine, @NotNull Inventory inv) {
            this.machine = machine;
            this.inv     = inv;
        }
    }
}
