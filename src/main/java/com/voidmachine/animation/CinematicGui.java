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
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cinematic 3-row (27-slot) slot-machine reel GUI shown during a VoidMachine ritual.
 *
 * <h3>Core rule — outcome is pre-determined</h3>
 * {@link AnimationPipeline} rolls the outcome <em>before</em> calling {@link #open}.
 * The reels are <strong>presentation only</strong>; they dramatise a result that is
 * already committed server-side.  No reel position influences the actual outcome.
 *
 * <h3>Animation phases</h3>
 * <ol>
 *   <li><b>SPINNING</b> (ramp) — all 6 reel cells cycle through the 7 {@link ReelSymbol}s.
 *       Speed modulates with {@code progress}: fast every 2 t → medium every 4 t →
 *       slow every 6 t as ramp crosses 0.5 and 0.75.</li>
 *   <li><b>STOPPING</b> (tension) — a staggered BukkitTask stops columns 0→5 every 2 t.
 *       Columns 4 and 5 receive a near-miss symbol for one extra step before locking,
 *       creating maximum tension on the last symbol drop.</li>
 *   <li><b>REVEAL</b> — {@link AnimationPipeline} calls {@link #onReveal} once stops
 *       complete.  Border panes shift to the outcome colour; status slot shows result.</li>
 * </ol>
 *
 * <h3>Inventory layout (27-slot, 3×9)</h3>
 * <pre>
 *   [B][B][B][B][B][B][B][B][B]   row 0 — solid border
 *   [B][R][R][R][S][R][R][R][B]   row 1 — R=reel cell (10-12, 14-16), S=separator (13)
 *   [B][B][B][B][T][B][B][B][B]   row 2 — T=status pane (22)
 * </pre>
 *
 * <h3>Reel pattern design</h3>
 * Final symbols per outcome create recognisable (but presentation-only) patterns:
 * <ul>
 *   <li>JACKPOT_X5: all six JACKPOT_STAR — maximum euphoria.</li>
 *   <li>TRIPLED: five ECHO_FRAGMENT + one ABYSS_FLAME — near-miss on the last column is
 *       the most dramatic heartbreaker: the player sees five in a row, then FLAME ruins it.</li>
 *   <li>DOUBLED: four DARK_CRYSTAL + two different — clear win, mild drama on cols 4-5.</li>
 *   <li>RETURNED: two VOID_ESSENCE + four chaos — briefly looks like a pattern, then breaks.</li>
 *   <li>DESTROYED: all six different — pure chaos, no false hope.</li>
 * </ul>
 *
 * <h3>Near-miss mechanic</h3>
 * Columns 4 and 5 each display one "teaser" symbol for one step before their final symbol
 * lands.  The teaser is chosen per-outcome to maximise dramatic tension (see {@link #NEAR_MISS}).
 * This is <em>purely visual</em> — the teaser is discarded immediately and has no bearing on
 * the real outcome which was decided before the animation started.
 *
 * <h3>Bedrock / Geyser</h3>
 * Inventory GUIs render correctly on Bedrock via Geyser.  Dynamic title updates are not
 * attempted (static title set at open time).  {@code ItemMeta#setHideTooltip(true)} is used
 * on border and separator panes only; reel symbols retain their tooltip so players can read
 * symbol names for lore flavour.
 *
 * <h3>Thread safety</h3>
 * All public methods must be called on the main thread.
 * {@link #activeInventories} uses a ConcurrentHashMap-backed set for safe cross-thread reads
 * by {@link CinematicGuiListener}.
 *
 * <h3>Timing calibration</h3>
 * The column-stop sequence occupies ≤ 16 ticks within the tension phase.
 * Requires {@code worldAnimTensionLockTicks ≥ 16} (default 20).  Reducing below 16
 * may cause columns to still be stopping when {@link #onReveal} fires (visually degraded
 * but not a correctness issue — the outcome was already decided).
 */
public final class CinematicGui {

    // =========================================================================
    //  Layout constants
    // =========================================================================

    /** Six reel cells, left-to-right across the middle row. Slot 13 is the separator gap. */
    private static final int[] REEL_SLOTS = {10, 11, 12, 14, 15, 16};
    private static final int   COL_COUNT  = REEL_SLOTS.length; // 6

    /** Divider pane between the two reel groups (slot 13, middle of row 1). */
    private static final int SLOT_SEP    = 13;

    /** Status / outcome pane — bottom-centre of the GUI. */
    private static final int SLOT_STATUS = 22;

    // =========================================================================
    //  Reel outcome patterns
    // =========================================================================

    /**
     * Final symbol for each reel column, indexed by {@code [Outcome.ordinal()][col 0-5]}.
     *
     * <p>Symbol ordinals: 0=VOID_ESSENCE  1=ECHO_FRAGMENT  2=DARK_CRYSTAL
     *                      3=RUNIC_DUST    4=ABYSS_FLAME    5=VOID_EYE  6=JACKPOT_STAR</p>
     */
    private static final int[][] OUTCOME_REEL = {
        // DESTROYED  — pure chaos: all six different, max disorder, JACKPOT_STAR taunts mid-reel
        {3, 4, 5, 1, 6, 0},
        // RETURNED   — two VOID_ESSENCE then collapse: briefly looks like a pattern, then chaos
        {0, 0, 3, 4, 5, 1},
        // DOUBLED    — four DARK_CRYSTAL then two breaks: clear win with mild drama on last pair
        {2, 2, 2, 2, 4, 0},
        // TRIPLED    — five ECHO_FRAGMENT then ABYSS_FLAME heartbreaker
        {1, 1, 1, 1, 1, 4},
        // JACKPOT_X5 — all JACKPOT_STAR: pure euphoria
        {6, 6, 6, 6, 6, 6},
    };

    /**
     * Near-miss symbol for columns 4 and 5, indexed by {@code [Outcome.ordinal()][0 or 1]}.
     * Value -1 means no near-miss for that column (stop immediately on final symbol).
     *
     * <h3>Design rationale</h3>
     * <ul>
     *   <li>DESTROYED (col4=RUNIC_DUST=3): same as col0, briefly implies "two in a row?" before
     *       col5 reveals JACKPOT_STAR then locks VOID_ESSENCE — total chaos.</li>
     *   <li>RETURNED (col4=VOID_ESSENCE=0): same as col0+1, implies "three in a row?" →
     *       then col4 locks ABYSS_FLAME.  Col5 does same tease → locks ECHO.</li>
     *   <li>DOUBLED (col4=DARK_CRYSTAL=2): same as 0-3, implies "five in a row?" → ABYSS_FLAME.
     *       Col5 same → VOID_ESSENCE.</li>
     *   <li>TRIPLED (col4=no near-miss, col5=ECHO=1): col4 just stops clean (already "five").
     *       Col5 shows a 6th ECHO ("six of a kind!!") then ABYSS_FLAME drops — heartbreaker.</li>
     *   <li>JACKPOT (col4=VOID_EYE=5, col5=VOID_EYE=5): tease "not star" one step before the
     *       JACKPOT_STAR lands on both — extra tension even on a win.</li>
     * </ul>
     */
    private static final int[][] NEAR_MISS = {
        {3,  3},   // DESTROYED
        {0,  0},   // RETURNED
        {2,  2},   // DOUBLED
        {-1, 1},   // TRIPLED  — col4 clean, col5 teases ECHO before FLAME
        {5,  5},   // JACKPOT_X5 — VOID_EYE teases before JACKPOT_STAR
    };

    // =========================================================================
    //  Border colour per outcome (for reveal phase)
    // =========================================================================

    private static final Material[] REVEAL_BORDER = {
        Material.RED_STAINED_GLASS_PANE,     // DESTROYED
        Material.GRAY_STAINED_GLASS_PANE,    // RETURNED
        Material.GREEN_STAINED_GLASS_PANE,   // DOUBLED
        Material.YELLOW_STAINED_GLASS_PANE,  // TRIPLED
        Material.MAGENTA_STAINED_GLASS_PANE, // JACKPOT_X5
    };

    // =========================================================================
    //  Shared static panes (never mutated after class init)
    // =========================================================================

    private static final ItemStack BORDER_PANE = hiddenPane(Material.BLACK_STAINED_GLASS_PANE);
    private static final ItemStack SEP_PANE    = hiddenPane(Material.GRAY_STAINED_GLASS_PANE);

    // =========================================================================
    //  State
    // =========================================================================

    private final VoidMachinePlugin plugin;
    private final MessageManager    messages;

    private final ConcurrentHashMap<UUID, ReelSession> sessions = new ConcurrentHashMap<>();

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
     * Open the reel GUI for a player.
     *
     * <p>The outcome has been pre-determined by {@link AnimationPipeline} before this
     * call.  Reels will spin randomly then decelerate and stop to visually reveal the
     * pre-committed result.</p>
     *
     * @param player       ritual participant
     * @param tx           active transaction (must be in ANIMATING state)
     * @param outcome      pre-rolled outcome — must match what AnimationPipeline will deliver
     * @param outputAmount item count that will be returned (0 for DESTROYED)
     */
    public void open(@NotNull Player player, @NotNull Transaction tx,
                     @NotNull Outcome outcome, int outputAmount) {

        Component title = messages.render("animation.gui.title");
        Inventory inv   = Bukkit.createInventory(null, 27, title);

        // Fill all 27 slots with border panes as a base.
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, BORDER_PANE.clone());
        }

        // Separator between the two reel groups.
        inv.setItem(SLOT_SEP, SEP_PANE.clone());

        // Status pane starts blank (boss bar carries progress info).
        inv.setItem(SLOT_STATUS, hiddenPane(Material.BLACK_STAINED_GLASS_PANE));

        // Compute the locked final symbols for this outcome.
        int outIdx       = Math.min(outcome.ordinal(), OUTCOME_REEL.length - 1);
        int[] finalSyms  = OUTCOME_REEL[outIdx].clone();

        // Initialise reel cells at random starting positions for visual variety.
        int[] startPos = new int[COL_COUNT];
        for (int col = 0; col < COL_COUNT; col++) {
            startPos[col] = (int)(Math.random() * ReelSymbol.count());
            inv.setItem(REEL_SLOTS[col], ReelSymbol.byIndex(startPos[col]).buildStack());
        }

        ReelSession session = new ReelSession(tx, inv, outcome, finalSyms, startPos);
        sessions.put(player.getUniqueId(), session);
        activeInventories.add(inv);
        player.openInventory(inv);
    }

    /**
     * Called each ramp step by {@link AnimationPipeline}.
     * Advances reel symbols at a speed that decreases as {@code progress} grows.
     *
     * <p>Speed schedule (calls to this method per symbol advance):
     * <ul>
     *   <li>progress &lt; 0.50 → every 1 call (fast)</li>
     *   <li>progress &lt; 0.75 → every 2 calls (medium)</li>
     *   <li>progress ≥ 0.75   → every 3 calls (slow)</li>
     * </ul>
     *
     * @param uuid     player UUID
     * @param step     current step index (1-based, from AnimationPipeline)
     * @param progress ramp progress 0.0 → ~0.9
     */
    public void onRampStep(@NotNull UUID uuid, int step, float progress) {
        ReelSession session = sessions.get(uuid);
        if (session == null || session.phase != Phase.SPINNING) return;

        int callCount  = ++session.rampCalls;
        int updateEvery = progress < 0.50f ? 1 : (progress < 0.75f ? 2 : 3);
        if (callCount % updateEvery != 0) return;

        // Advance all non-stopped columns by one symbol position.
        for (int col = 0; col < COL_COUNT; col++) {
            if (session.stopped[col]) continue;
            session.positions[col] = (session.positions[col] + 1) % ReelSymbol.count();
            session.inv.setItem(REEL_SLOTS[col],
                    ReelSymbol.byIndex(session.positions[col]).buildStack());
        }
    }

    /**
     * Transition to the STOPPING phase.
     * Fires a staggered BukkitTask that stops reel columns left→right every 2 ticks.
     * Columns 4 and 5 receive a one-tick near-miss symbol before their final symbol lands.
     *
     * <p>Total time: ≤ 16 ticks (8 events × 2 t), leaving ≥ 4-tick hold before
     * {@code AnimationPipeline}'s reveal fires at {@code tensionTicks} (default 20).</p>
     */
    public void onTension(@NotNull UUID uuid) {
        ReelSession session = sessions.get(uuid);
        if (session == null) return;
        session.phase = Phase.STOPPING;
        scheduleColumnStops(uuid, session);
    }

    /**
     * Show the outcome in the GUI.
     * AnimationPipeline calls {@link #close(UUID)} after its hold period.
     *
     * @param uuid         player UUID
     * @param outcome      pre-rolled outcome (same value passed to {@link #open})
     * @param outputAmount items returned (0 for DESTROYED)
     */
    public void onReveal(@NotNull UUID uuid, @NotNull Outcome outcome, int outputAmount) {
        ReelSession session = sessions.get(uuid);
        if (session == null) return;
        session.phase = Phase.REVEAL;

        Inventory inv = session.inv;

        // Shift border panes to outcome colour.
        Material revealMat = REVEAL_BORDER[Math.min(outcome.ordinal(), REVEAL_BORDER.length - 1)];
        ItemStack revealPane = hiddenPane(revealMat);
        for (int i = 0; i < 27; i++) {
            if (isReelSlot(i) || i == SLOT_SEP || i == SLOT_STATUS) continue;
            inv.setItem(i, revealPane.clone());
        }

        // Status pane — visible hover text summarising the result.
        inv.setItem(SLOT_STATUS, buildStatusPane(revealText(outcome, outputAmount)));
    }

    /**
     * Close the GUI for this player and cancel any running tension task.
     * Safe to call when no session exists (no-op).
     */
    public void close(@NotNull UUID uuid) {
        ReelSession session = sessions.remove(uuid);
        if (session == null) return;

        BukkitTask tt = session.tensionTask;
        if (tt != null) {
            tt.cancel();
            session.tensionTask = null;
        }

        activeInventories.remove(session.inv);

        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null && p.isOnline()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (session.inv.equals(top)) p.closeInventory();
        }
    }

    /**
     * Close all open sessions.  Called from {@code VoidMachinePlugin.onDisable()}.
     */
    public void shutdown() {
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            close(uuid);
        }
        sessions.clear();
        activeInventories.clear();
    }

    /**
     * Returns {@code true} if {@code inv} is an active VM cinematic GUI.
     * Used by {@link CinematicGuiListener} to identify and cancel clicks.
     */
    public boolean isVmInventory(@NotNull Inventory inv) {
        return activeInventories.contains(inv);
    }

    // =========================================================================
    //  Column-stop scheduler
    // =========================================================================

    /**
     * Build and schedule the staggered column-stop sequence for the tension phase.
     *
     * <p>Event list construction:
     * <ol>
     *   <li>Columns 0-3: stop immediately (one event each, every 2 t).</li>
     *   <li>Column 4: optional near-miss event, then stop event.</li>
     *   <li>Column 5: optional near-miss event, then stop event.</li>
     * </ol>
     *
     * Event encoding: {@code 0-5} = stop that column; {@code 100+col} = near-miss step for col.
     */
    private void scheduleColumnStops(@NotNull UUID uuid, @NotNull ReelSession session) {
        int outOrdinal = Math.min(session.outcome.ordinal(), NEAR_MISS.length - 1);
        int nearMiss4  = NEAR_MISS[outOrdinal][0]; // symbol ordinal for near-miss, or -1
        int nearMiss5  = NEAR_MISS[outOrdinal][1];

        // Build ordered event list.
        List<Integer> ev = new ArrayList<>(10);
        ev.add(0); ev.add(1); ev.add(2); ev.add(3);
        if (nearMiss4 >= 0) ev.add(100 + 4); // near-miss step for col 4
        ev.add(4);
        if (nearMiss5 >= 0) ev.add(100 + 5); // near-miss step for col 5
        ev.add(5);
        final int[] events = ev.stream().mapToInt(Integer::intValue).toArray();

        AtomicReference<BukkitTask> taskRef = new AtomicReference<>();
        AtomicInteger               idxRef  = new AtomicInteger(0);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            ReelSession s = sessions.get(uuid);
            if (s == null) {
                BukkitTask t = taskRef.get();
                if (t != null) t.cancel();
                return;
            }

            int idx = idxRef.getAndIncrement();
            if (idx >= events.length) {
                BukkitTask t = taskRef.get();
                if (t != null) {
                    t.cancel();
                    s.tensionTask = null;
                }
                return;
            }

            int spec = events[idx];

            if (spec >= 100) {
                // Near-miss step: show a teaser symbol one tick before the final locks.
                int col    = spec - 100;
                int teaseSym = (col == 4) ? nearMiss4 : nearMiss5;
                s.inv.setItem(REEL_SLOTS[col], ReelSymbol.byIndex(teaseSym).buildStack());
            } else {
                // Stop step: lock column to its final symbol.
                s.stopped[spec] = true;
                int sym = s.finalSymbols[spec];
                s.positions[spec] = sym;
                s.inv.setItem(REEL_SLOTS[spec], ReelSymbol.byIndex(sym).buildStack());
            }

        }, 0L, 2L);

        taskRef.set(task);
        session.tensionTask = task;
    }

    // =========================================================================
    //  Item helpers
    // =========================================================================

    /**
     * Glass pane with hidden tooltip — background filler only.
     * The static result must be cloned before placing in an inventory.
     */
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

    /**
     * Status pane with visible display name (shown on hover).
     * Tooltip NOT hidden so the player can read the result text.
     */
    @NotNull
    private static ItemStack buildStatusPane(@NotNull Component text) {
        ItemStack is   = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta  meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(text.decoration(TextDecoration.ITALIC, false));
            is.setItemMeta(meta);
        }
        return is;
    }

    @NotNull
    private static Component revealText(@NotNull Outcome outcome, int outputAmount) {
        return switch (outcome) {
            case DESTROYED  -> Component.text("The void consumed everything.", NamedTextColor.DARK_RED);
            case RETURNED   -> Component.text("The machine returns your offering.", NamedTextColor.GRAY);
            case DOUBLED    -> Component.text("Amplified — " + outputAmount + " returned!", NamedTextColor.GREEN)
                                        .decorate(TextDecoration.BOLD);
            case TRIPLED    -> Component.text("Surge — " + outputAmount + " returned!", NamedTextColor.GOLD)
                                        .decorate(TextDecoration.BOLD);
            case JACKPOT_X5 -> Component.text("THE VOID AWAKENS — ×5!", NamedTextColor.LIGHT_PURPLE)
                                        .decorate(TextDecoration.BOLD);
        };
    }

    // =========================================================================
    //  Slot helpers
    // =========================================================================

    private static boolean isReelSlot(int slot) {
        for (int s : REEL_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    // =========================================================================
    //  Session
    // =========================================================================

    enum Phase { SPINNING, STOPPING, REVEAL }

    private static final class ReelSession {

        final Transaction tx;
        final Inventory   inv;
        final Outcome     outcome;
        final int[]       finalSymbols; // [COL_COUNT] — pre-determined symbol per column
        final boolean[]   stopped;      // [COL_COUNT] — column is locked
        final int[]       positions;    // [COL_COUNT] — current display symbol index

        volatile Phase    phase      = Phase.SPINNING;
        int               rampCalls  = 0;

        /** Running tension BukkitTask; null when not active. */
        @Nullable volatile BukkitTask tensionTask;

        ReelSession(@NotNull Transaction tx, @NotNull Inventory inv,
                    @NotNull Outcome outcome, @NotNull int[] finalSymbols,
                    @NotNull int[] startPositions) {
            this.tx           = tx;
            this.inv          = inv;
            this.outcome      = outcome;
            this.finalSymbols = finalSymbols.clone();
            this.stopped      = new boolean[COL_COUNT];
            this.positions    = startPositions.clone();
        }
    }
}
