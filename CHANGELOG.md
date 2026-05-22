# Changelog — VoidMachine

All notable changes are documented here.
Format: [version] — date, then Added / Changed / Fixed / Security sections.

---

## [Unreleased]

### Removed
- **`ReelSymbol.java` deleted** — orphaned after CinematicGui reel-removal rewrite.
  Zero remaining references confirmed.

### Changed
- **Active START button gains enchant-glow** (`setEnchantmentGlintOverride(true)`) —
  NETHER_STAR pulses with purple sheen when sacrifice is placed. No actual enchantment
  applied. Instantly signals "this button is live." Geyser-compatible (1.20.5+ API).
- **RETURNED outcome text changed** from `GRAY` to `WHITE` — clear contrast against
  the gray border panes in the reveal phase. All five outcomes now have unmistakable
  distinct colours: DARK_RED / WHITE / GREEN / GOLD / LIGHT_PURPLE.
- **Header item (slot 4) text shortened** for mobile/controller readability:
  - Empty → name `"▼ Place item below"` (no lore)
  - Ready → name `"▼ Press START"` (no lore)
  Name is always visible; lore is hover-only and invisible on mobile / Bedrock without
  hover. Instruction now lives entirely in the item name.
- **Inactive START button lore removed** (was `"Place an item in the slot above."`) —
  the header item name already gives this instruction; duplication removed.
- **StagingGui completely redesigned** — Bedrock / mobile / controller / child UX priority:
  - Layout reduced from 5-row 45-slot to **3-row 27-slot** chest. Simpler, faster to parse.
  - Column-aligned layout: header (slot 4) → input (slot 13) → START button (slot 22).
    Natural top-to-bottom read order; player understands immediately without instructions.
  - Input slot left as **AIR** — the single empty slot against a dark pane background draws
    the eye immediately on any platform.
  - **Header item** (ENDER_EYE, slot 4): visible tooltip with explicit instructions.
    Updates from "Place your offering…" → "Press the button below" when item is placed.
  - Pane count reduced from 43 to 25. No inner accent layer — border panes only.
  - START button: BARRIER (inactive) → NETHER_STAR (active). Clear visual state change.
  - `staging.header-item` section added to `messages.yml` for localisation.
  - Inactive lore simplified to one line: "Place an item in the slot above."
- **CinematicGui completely redesigned** — reel slot-machine UI replaced by ritual chamber:
  - All reel machinery removed: `ReelSession`, `Phase` enum, `scheduleColumnStops`,
    `REEL_SLOTS`, `OUTCOME_REEL`, `NEAR_MISS`, `AtomicReference`/`AtomicInteger` tension
    scheduler, and `onRampStep` logic. Net: ~200 lines removed.
  - Single status pane at slot 13 (center of 3-row inventory).
  - **Ramp phase**: ENDER_EYE — "⬛ The Void stirs…" — static, dark, atmospheric.
    Boss bar and world sounds carry all ramp energy.
  - **Tension phase**: ENDER_EYE dims to ". . ." — mirrors boss bar freeze.
  - **Reveal phase**: border panes shift to outcome colour (red / grey / green / gold /
    magenta); center slot becomes a **bold, unambiguous outcome item**:
    - DESTROYED → BARRIER "✗ CONSUMED" (universally understood icon)
    - RETURNED → ENDER_PEARL "↩ RETURNED"
    - DOUBLED → EMERALD "✦ DOUBLED ×2"
    - TRIPLED → NETHER_STAR "★ TRIPLED ×3"
    - JACKPOT → NETHER_STAR (enchant glow) "★★ JACKPOT ×5 ★★"
  - JACKPOT glow via `ItemMeta.setEnchantmentGlintOverride(true)` (1.20.5+ API) —
    no actual enchantment, purely visual. Geyser-compatible.
  - `onRampStep` preserved as no-op — `AnimationPipeline` call surface unchanged.
  - `AnimationPipeline.java` requires zero changes; all signatures preserved.
  - `ReelSymbol.java` now orphaned (no references). Scheduled for removal.

### Added
- **Idle ambient effects** (`AmbientEffectScheduler`):
  - Portal smoke drift every 1 s from all idle machine blocks.
  - Soul-fire flame accent + respawn-anchor hum (world-space, ~4 block range) every 3 s.
  - Attract-mode pulse (END_ROD burst + amethyst chime) every 30 s — catches nearby
    players' attention without constant noise.
  - Effects staggered per machine via `locationKey().hashCode()` to prevent
    multi-machine tick spikes.
  - Machines mid-ritual are skipped; chunks are never force-loaded.
  - Controlled by `atmosphere.enabled` in `config.yml` (default `true`).
  - `/vm reload` restarts the scheduler so the flag takes effect immediately.
- **Social visibility during active ritual**:
  - World-space portal ambient sound every ~20 ramp steps (~40 ticks) —
    spectators within 6 blocks hear the machine building.
  - World-space respawn-anchor tension hum at ritual tension phase —
    spectators within 8 blocks feel the dread.
  - World-space TRIPLED sound (×2 volume, ~32 block range) — nearby players
    hear surges without needing to watch.
- **Jackpot triple-lightning event**: three sequential `strikeLightningEffect` calls
  at +0 / +10 / +20 ticks with extra totem burst on the second. World-space
  `ui.toast.challenge_complete` at volume 4.0 (~64 block range) — the whole server
  area knows something happened.

### Fixed
- **`/vm admin remove` now clears the physical block**: previously the registry entry
  was deleted but the block remained in the world. The command now loads the chunk if
  needed, checks that the block is still the configured core material, and sets it to
  AIR before deregistering. If the world is unloaded or the block was already changed,
  the admin receives a specific warning and the machine is deregistered regardless.

### Changed
- **Staging GUI interaction model overhauled** for Bedrock / controller / touch compatibility:
  - **Pre-commit phase is now fully unrestricted**: normal click, pick-up, and
    shift-click all work naturally on the input slot (22) and the player's own inventory.
    Pane/border slots remain cancelled (prevents item theft and ghost-cursor from pane-swap).
    Shift-click from player inventory routes natively to slot 22 — no special routing code.
  - **`shiftClickToInput()` removed**: special-cased shift-click interceptor deleted entirely.
    Relies on vanilla Bukkit behaviour: slot 22 is the only non-pane slot in the top
    inventory, so shift-clicks go there automatically on all platforms.
  - **Cursor item resolved at START press** in `triggerStart()`: if the player holds an
    item on cursor when clicking START (e.g., picked up the sacrifice just before pressing),
    it is moved to slot 22 (if empty) or returned to inventory (if occupied).
  - **Stack clamping moved to START press** (previously at shift-click time): if the placed
    stack exceeds `maxInsertAmount`, the excess is returned to the player's inventory
    immediately before the WAL checkpoint — transparently, not silently.
  - **`StagingGuiListener` simplified**: removed `InventoryAction` import and shift-click
    routing. Drag handler now only cancels drags that touch non-input top-inventory slots.
- **Staging GUI lore updated** to platform-neutral language (removed "shift-click" mention).

### Added
- **Slot-machine reel animation** (`CinematicGui` rewrite + `ReelSymbol`):
  - 6 reel cells across the middle row of the cinematic GUI (slots 10–12 · 14–16),
    separated by a divider pane at slot 13.
  - 7 void-themed symbols: Void Essence, Echo Fragment, Dark Crystal, Runic Dust,
    Abyss Flame, Void Eye, Jackpot Star.  Pre-built cached `ItemStack`s; only
    `clone()` hits allocation per tick.
  - Three-phase speed progression driven by ramp `progress`:
    `< 0.5` → fast (every 2 t) · `< 0.75` → medium (every 4 t) · `≥ 0.75` → slow (every 6 t).
  - Staggered column-stop sequence during tension phase (columns 0→5, 2 t apart).
    Columns 4 and 5 display one near-miss "teaser" symbol for one tick before the
    final symbol locks — pure visual drama, no gameplay effect.
  - Per-outcome final reel patterns and per-outcome near-miss symbols designed for
    maximum dramatic tension (e.g. TRIPLED stops five ECHO_FRAGMENT then drops
    ABYSS_FLAME on the last column — the classic heartbreaker).
  - Border panes shift to outcome colour at reveal (red / grey / green / gold / magenta).
  - Total column-stop sequence ≤ 16 ticks, within the 20-tick tension window.

### Changed
- **Outcome pre-rolled at animation start** (`AnimationPipeline.start()`):
  - Outcome is rolled and stored in `AnimationContext` **before** any GUI frame is shown.
  - `reveal()` uses `ctx.outcome` / `ctx.outputAmount` — no re-roll at reveal.
  - `CinematicGui.open()` receives `Outcome outcome, int outputAmount` and passes
    the final symbol pattern to the reel session immediately.
  - This satisfies the core anti-manipulation rule: reels are presentation only;
    the outcome is fully committed before the first reel frame renders.

---

## [0.1.0-alpha] — 2026-05-22

First alpha milestone. Core gameplay loop playable end-to-end on a live server.

### Added
- **Staging GUI** (5-row commit screen): right-click machine → place item in center slot →
  press ⚡ FEED THE VOID. Item is not captured until START is confirmed.
  `StagingGui` + `StagingGuiListener`.
- **Explicit commit step**: WAL checkpoint and machine lock only occur after START is pressed,
  not on machine right-click.
- **`ItemCaptureService.captureFromGui()`**: GUI-path capture that accepts a pre-provided
  `ItemStack` and fires an `onConsumed` callback at the point-of-no-return.
- **`PlayerDeathListener`**: hard-aborts staging sessions and active transactions on death.
  Staging item is injected into `event.getDrops()` so it is not silently lost.
- **CinematicGui** (3-row): inventory animation shown during the ritual ramp/tension/reveal.
  Integrated with `AnimationPipeline`.
- **Piston + liquid-flow protection**: machine blocks now survive piston push/pull and
  liquid-flow events in addition to explosions.
- **Admin-only commands**: `/vm admin create <name> [profile]`, `/vm admin remove <name>`,
  `/vm admin list`, `/vm reload`.
- **Config profiles**: named weight sets per machine (e.g. `default`, `brutal`, `unstable`).
- **WAL checkpoint system**: item captured to disk (`fsync`) before it leaves the player,
  with startup recovery for items lost in server crashes.
- **AnimationWatchdog**: hard-aborts stuck transactions after configurable timeout.

### Changed
- **Animation speed dramatically reduced** (target ~5 s total, was ~15 s):

  | Setting                               | Old | New |
  |---------------------------------------|-----|-----|
  | `animation.steps` (default)          | 28  | 30  |
  | `animation.step-interval-ticks`      | 3   | 2   |
  | `world-animation.step-ticks`         | 3   | 2   |
  | `world-animation.tension-lock-ticks` | 40  | 20  |
  | `world-animation.max-duration-ticks` | 300 | 200 |
  | Reveal boss-bar hold (hardcoded)     | 40L | 20L |

  Ramp is now driven by `animation.steps × step-ticks` rather than derived from max-duration.
  `max-duration-ticks` is now the watchdog timeout only (should remain > actual animation length).

- **Machine block break hardened**: registered blocks can no longer be broken by ANY player,
  including admins. The `voidmachine.admin.break` permission no longer grants break access.
  Use `/vm admin remove <name>` exclusively.

- **Shift-click into staging GUI clamped** to `limits.max-insert-amount`.
  Excess remains in the player's inventory slot.

- **START button locks GUI immediately** on click: slot 22 shows "Processing…" and all
  further interaction (click, drag, close) is blocked until capture succeeds or fails.
  If capture fails (machine busy, checkpoint error), the GUI is fully restored for retry.

- **Staging GUI lore updated** with clear instructions:
  - Inactive: explains click-to-place and shift-click from inventory.
  - Active: warns "there is no going back."

- **Player interaction flow**: right-click now opens staging GUI instead of immediately
  capturing from main hand. Machine lock pre-check removed from interaction listener;
  the lock attempt happens inside `captureFromGui` when START is pressed.

### Fixed
- Death while staging GUI open no longer silently loses the item (custom inventory items
  are not automatically included in Minecraft's death drops).
- Explosion protection now correctly handles both entity-explosion and block-explosion events.
- Piston movement no longer displaces machine blocks.

### Known Limitations (Alpha)
- Items inside an **active transaction** (post-commit, animation running) may not appear in
  death drops if `keepInventory=false`. The WAL checkpoint persists; admins can review via
  the audit log and manually refund if needed.
- **Multi-block structure validation** (crying obsidian frame) not yet implemented. Any block
  registered as the core material is a valid machine.
- **Slot-machine reel animation** not yet implemented. CinematicGui uses pulsing accent panes.
- Transaction items refunded via `abortTransaction` on death go to player inventory at
  `MONITOR` priority; if `keepInventory=false`, they may be lost before the drop list is
  finalized (edge case — audit log retains evidence).
