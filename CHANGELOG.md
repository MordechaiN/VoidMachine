# Changelog — VoidMachine

All notable changes are documented here.
Format: [version] — date, then Added / Changed / Fixed / Security sections.

---

## [1.1.0-beta] — 2026-05-23

### Added

- **Rare fakeout reveals** — ~1-in-100 chance for DOUBLED, TRIPLED, and JACKPOT_X5
  outcomes to play a fake CONSUMED reveal before snapping to the real result.

  Flow: animation resolves normally → fake "CONSUMED" boss bar (red) + smoke burst +
  wither sound plays → ~1.25 s pause → lightning strike → real outcome reveals
  (boss bar, particles, sound, chat message).

  Items are delivered *before* any visual plays — the fakeout is 100% presentation.
  No economic impact; no psychological manipulation of actual odds.

  - `fakeout.enabled` / `fakeout.chance-1-in` in `config.yml`
  - Pre-rolled in `AnimationPipeline.start()` alongside the outcome roll

- **Unique jackpot variants** — four distinct visual/audio sequences for JACKPOT_X5,
  pre-rolled per transaction so the same player never sees an identical jackpot twice:

  | Variant | Character |
  |---------|-----------|
  | `STORM` | Classic triple lightning, challenge fanfare, dragon growl (original behaviour) |
  | `SILENT` | Smoke implosion, 1 s of silence, then sudden amethyst burst + lightning |
  | `DRAGON` | Portal wash, dragon ambient echo, two delayed lightning strikes |
  | `ECHO` | Chaotic: wither scream + double challenge-done + rapid double lightning |

  All variants scale up particle counts when a crowd is nearby (see Crowd Awareness).
  Bedrock-safe: vanilla particles, sounds, and lightning only.

  - `JackpotVariant` enum (`com.voidmachine.animation.JackpotVariant`)
  - Dispatched in `AnimationPipeline.playRevealEffects()` via pre-rolled `ctx.jackpotVariant`

- **Dynamic void events** — random atmospheric surges every 10–45 minutes on idle machines.
  Selects a random unlocked, chunk-loaded machine and applies:
  - Stronger PORTAL + SOUL_FIRE_FLAME particle burst
  - Deeper bass surge (`block.beacon.power_select` louder + lower pitch than idle)
  - Ender dragon ambient resonance (`entity.ender_dragon.ambient`)
  - Lightning pulse
  - Action-bar message to nearby players: *"The Void grows restless…"*

  Atmosphere only — no rate changes, no economy effects, no ritual interference.
  Re-schedules itself after each event with a fresh random delay.

  - `void-events.enabled`, `void-events.min-interval-minutes`, `void-events.max-interval-minutes`
  - Implemented in `AmbientEffectScheduler.scheduleNextVoidEvent()` + `triggerVoidEvent()`

- **Crowd awareness system** — machines and reveals scale cosmetic effects when
  3+ players are nearby (configurable threshold and radius):

  *Ambient effects (idle machines):*
  - Smoke drift: ×2 particles
  - Soul-fire accent: ×3 particles
  - Hum volume: 0.25 → 0.38 (louder, heard from further)
  - Bass pulse: 0.28 → 0.45
  - Attract flash: ×2 particles

  *Reveal effects:*
  - TRIPLED: extra totem burst (+20 particles)
  - JACKPOT_X5 variants: all increase particle counts and world-space sound volume

  Nearby-player count is cached per machine every 40 ticks (2 s) — never per-tick.
  Atmosphere only — no probability changes. All players always see identical odds.

  - `crowd-awareness.enabled`, `.min-players`, `.radius`, `.check-interval-ticks`
  - Cache managed in `AmbientEffectScheduler.updateCrowdCache()`
  - Reveal check is a single inline `World.getNearbyPlayers()` call at reveal time

### Changed

- **`AnimationPipeline.reveal()` refactored** into composable helpers:
  `showRevealState()`, `schedulePostRevealCleanup()`, `playFakeoutSequence()`,
  `getCrowdNearby()`. Transaction bookkeeping (markCompleted / audit / stats) now
  occurs before the visual sequence, so audit logs are always timely regardless of
  fakeout delay.

- **`AnimationPipeline.playRevealEffects()` signature extended**: now accepts
  `JackpotVariant jackpotVariant` and `int crowdNearby` alongside the existing
  outcome and location parameters.

- **`AmbientEffectScheduler.tickMachine()` extended**: accepts `int crowdNearby`
  parameter; scales particle counts and sound volumes when crowd threshold is met.

### Version

- Bumped to `1.1.0-beta` in `build.gradle.kts`.

---

## [1.0.0] — 2026-05-23

### Added

- **Ritual lock state** — players become temporarily claimed by the Void the instant
  they press START. Positional movement (XYZ) is blocked; camera rotation remains
  free so the player feels captured, not frozen. Also blocked while locked:
  inventory open, inventory click, item drop (Q), and hand swap (F).

  Lock applied in `StagingGui.triggerStart` at the point-of-no-return and
  guaranteed to release on every exit path — animation complete, abort, capture
  failure, disconnect, death, shutdown, or crash recovery. No player can be
  left permanently locked.

  Bedrock-safe: `PlayerMoveEvent.setTo(corrected)` redirects to the player's
  origin (yaw/pitch preserved) rather than teleporting — no rubber-banding,
  no Geyser desync, no jitter.

  - **`RitualLockService`** (`com.voidmachine.service`) — tracks locked players,
    manages per-player portal-particle halos (5 particles at chest level, every
    10 ticks while locked), exposes thread-safe `isLocked(UUID)`.
  - **`RitualLockListener`** (`com.voidmachine.service`) — enforces the lock via
    `PlayerMoveEvent`, `InventoryOpenEvent`, `InventoryClickEvent`,
    `PlayerDropItemEvent`, and `PlayerSwapHandItemsEvent`. All handlers at
    `HIGH` priority.
  - **Ritual halo particles** — faint portal-particle orbit around locked players
    gives nearby spectators a visual cue that the ritual is in progress. No
    blindness, nausea, or slowness applied.

- **`/vm stats`** — server-wide lifetime statistics command. Output:

  ```
  ◈ VoidMachine — Lifetime Statistics
  ─────────────────────────────
    Sacrifices   1,247
  ─────────────────────────────
    Destroyed      899  (72.1%)
    Returned       225  (18.0%)
    Doubled         87  ( 7.0%)
    Tripled         25  ( 2.0%)
    Jackpots        11  ( 0.9%)
  ─────────────────────────────
    Items in     4,891
    Top offering minecraft:diamond ×340
  ```

  Requires `voidmachine.stats` permission (or `voidmachine.admin`). Persisted to
  `plugins/VoidMachine/global_stats.yml` — survives restarts.

- **`GlobalStats`** (`com.voidmachine.db.GlobalStats`) — atomic, YAML-backed
  lifetime counter store. Tracks total sacrifices, per-outcome counts, total
  items consumed, and per-material item counts (for top-offering detection).
  Counters updated asynchronously after each completed transaction. Wired into
  `AnimationPipeline` via `setGlobalStats`.

- **`voidmachine.stats` permission** — view `/vm stats` without full admin access.

- **Idle ambient effects** (`AmbientEffectScheduler`):
  - Portal smoke drift every 1 s from all idle machine blocks.
  - Soul-fire flame accent + respawn-anchor hum (world-space, ~4 block range) every 3 s.
  - Attract-mode pulse (END_ROD burst + amethyst chime) every 30 s — catches
    nearby players' attention without constant noise.
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
  at +0 / +10 / +20 ticks with an extra totem burst on the second strike.
  World-space `ui.toast.challenge_complete` at volume 4.0 (~64 block range) —
  the whole server area knows something happened.

### Changed

- **Sound polish pass** — psychological ambient design for idle machines and the ritual:
  - **Randomised ambient pool** (`AmbientEffectScheduler`): each 3-second hum cycle now
    draws from three sounds — 70% void hum (`block.respawn_anchor.ambient`), 20% portal
    resonance echo (`block.portal.ambient`), 10% enderman ambient whisper
    (`entity.enderman.ambient` at v=0.09). Pitch varies ±0.07 each fire so the
    machine never sounds mechanical.
  - **Deep bass pulse** (every ~45 s): `block.beacon.power_select` at pitch 0.30 — a
    subsonic thud felt more than heard. Range ~5 blocks.
  - **Metallic creak** (1-in-7 attract fires): attract-mode sound alternates between
    the amethyst chime (6/7) and an iron-door creak at pitch 0.42 (1/7) —
    "something shifted."
  - **START commit click** (`StagingGui.triggerStart`): `block.anvil.use` at v=0.7,
    p=0.55 fires at the exact moment the player commits — heavy, deliberate,
    irreversible. Natural silence follows before the ramp charge begins.
  - **TRIPLED resonance aftershock**: deep amethyst chime (p=0.65) at +4 ticks after
    the level-up reveal — gives TRIPLED a distinct audio tail vs DOUBLED.
  - **Jackpot dragon growl** (`entity.ender_dragon.growl`, v=0.65, p=1.2): plays at
    +10 ticks on the second lightning strike. ~12 block range.

- **`StagingGui` completely redesigned** — Bedrock / mobile / controller / child UX
  priority:
  - Layout reduced from 5-row 45-slot to **3-row 27-slot**. Column-aligned:
    header (slot 4) → input (slot 13) → START button (slot 22). Natural
    top-to-bottom read order; no platform-specific input required.
  - Input slot left as AIR — the single empty slot against a dark pane background
    draws the eye immediately on all platforms.
  - Header item (ENDER_EYE, slot 4): updates "Place your offering…" →
    "Press the button below" when item is placed.
  - START button: BARRIER (inactive) → NETHER_STAR (active), with
    `setEnchantmentGlintOverride(true)` so the active button visibly glows.
  - Pane count reduced from 43 to 25.

- **`CinematicGui` completely redesigned** — reel slot-machine concept replaced by
  ritual chamber:
  - Single status pane at slot 13 (center of 3-row inventory). No reel machinery.
  - Ramp phase: ENDER_EYE — *"⬛ The Void stirs…"* — static, atmospheric.
  - Tension phase: ENDER_EYE dims to *". . ."* — mirrors boss bar freeze.
  - Reveal phase: border panes shift to outcome colour; center slot becomes a bold,
    unambiguous outcome item:
    - DESTROYED → BARRIER *"✗ CONSUMED"*
    - RETURNED → ENDER_PEARL *"↩ RETURNED"*
    - DOUBLED → EMERALD *"✦ DOUBLED ×2"*
    - TRIPLED → NETHER_STAR *"★ TRIPLED ×3"*
    - JACKPOT → NETHER_STAR (enchant glow) *"★★ JACKPOT ×5 ★★"*
  - JACKPOT glow via `ItemMeta.setEnchantmentGlintOverride(true)` (1.20.5+ API).
    No actual enchantment. Geyser-compatible.
  - `AnimationPipeline` call surface unchanged — zero changes required there.

- **Staging GUI interaction model overhauled** for Bedrock / controller / touch
  compatibility:
  - Pre-commit phase fully unrestricted — normal click, pick-up, and shift-click
    on the input slot and player inventory work naturally.
  - `shiftClickToInput()` removed — vanilla Bukkit routes shift-clicks to the
    single empty slot automatically on all platforms.
  - Cursor item resolved at START press — item held on cursor when clicking START
    is moved to the input slot (if empty) or returned to inventory (if occupied).
  - Stack clamping moved to START press — excess returned to player before WAL
    checkpoint; transparent, not silent.
  - Pane/border slots remain cancelled (prevents item theft and ghost-cursor from
    pane swaps).

- **`/vm admin create` improved** — admin looks at any block within 5 blocks and
  runs `/vm admin create <name> [profile]`. The plugin replaces that block with the
  configured core material and registers it. No manual pre-placement required.

- **RETURNED outcome text** changed from `GRAY` to `WHITE` — clear contrast against
  the gray border panes in the reveal phase. All five outcomes now have distinct
  unmistakable colours: DARK_RED / WHITE / GREEN / GOLD / LIGHT_PURPLE.

- **Header item text shortened** for mobile/controller readability:
  - Empty → *"▼ Place item below"* (no lore)
  - Ready → *"▼ Press START"* (no lore)
  The instruction lives in the item name — always visible, not hover-only.

- **Staging GUI lore updated** to platform-neutral language (removed "shift-click"
  mention).

- **Animation timing** restored to pre-alpha defaults after experimentation:
  `animation.steps` = 28, `animation.step-interval-ticks` = 3,
  `world-animation.step-ticks` = 3, `world-animation.tension-lock-ticks` = 40,
  `world-animation.max-duration-ticks` = 300.

### Fixed

- **`/vm admin remove` now clears the physical block** — previously the registry entry
  was deleted but the block remained in the world. The command now loads the chunk if
  needed, checks that the block is still the configured core material, and sets it to
  AIR. If the world is unloaded or the block was already changed, the admin receives
  a specific warning and the machine is deregistered regardless.

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
- **Animation speed reduced** (target ~5 s total, was ~15 s):

  | Setting                               | Old | New |
  |---------------------------------------|-----|-----|
  | `animation.steps`                    | 28  | 30  |
  | `animation.step-interval-ticks`      |  3  |  2  |
  | `world-animation.step-ticks`         |  3  |  2  |
  | `world-animation.tension-lock-ticks` | 40  | 20  |
  | `world-animation.max-duration-ticks` | 300 | 200 |
  | Reveal boss-bar hold (hardcoded)     | 40L | 20L |

  Ramp is driven by `animation.steps × step-ticks` rather than derived from max-duration.
  `max-duration-ticks` is the watchdog timeout only (should remain > actual animation length).
  *(These values were later restored to their original defaults in v1.0.0.)*

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
