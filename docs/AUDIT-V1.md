# VoidMachine V1 (1.1.0-beta) — Forensic Audit

This document records what V1 actually did, proven from code (not comments or README),
and the defects that justified the V2 rewrite of the transaction engine. It is kept in the
repository as the reference for *why* V2 is shaped the way it is.

Audited revision: `ef741dc` (branch base). Method: full read of all 51 source files,
compilation against `paper-api` 26.2 (built from PaperMC `ver/26.2`), and reading the
relevant Paper server source (`CraftPlayer#saveData`, `PlayerDataStorage`, `PlayerList`).

## 1. Inventory of the codebase

| Area | Files | Status in V1 |
|---|---|---|
| Physical machine path (staging GUI → capture → animation → delivery) | `animation/*`, `interaction/*`, `checkpoint/*`, `machine/*`, `transaction/*`, `service/RitualLock*` | **Live** |
| Legacy GUI path (`GuiManager`, `MachineGui`, `AnimationRunner`, `ProcessingService`, `StatsService`, `listener/*`, `Effects`) | 9 files | **Dead** — listeners never registered, but services are constructed |
| SQL/YAML storage (`db/*` except `GlobalStats`), HikariCP + MariaDB shaded | 6 files + ~3 MB of shaded jars | **Dead** for gameplay, but the pool is started on enable |
| `DiscordHook` | 1 | Only reachable from the dead legacy path |
| Tests | none | — |
| CI | none (README badge points at a missing `ci.yml`) | — |
| Gradle wrapper | `gradle-wrapper.properties` only; no `gradlew`, no wrapper jar | Build instructions in README cannot work as written |

## 2. Current-state model (the 25 questions)

1. **Registration** — `/vm admin create <name> [profile]`: target block (≤5 blocks) is replaced with
   the core material and stored in `machines.yml`. Profile name is not validated.
2. **Persistence** — `MachineDataStore.save()` rewrites `machines.yml` from the *in-memory registry*.
3. **Interaction** — right-click (main hand only) opens the staging GUI. Off-hand interactions are
   ignored *without being cancelled*.
4. **Selection** — the player physically moves the item into slot 13 of a custom chest inventory.
5. **When the item leaves the inventory** — when it is placed into the staging GUI. From then until
   START it lives in a non-persisted custom inventory.
6. **When persistence occurs** — on START: `CheckpointStore.writeSync` (fsync) → machine CAS lock →
   GUI slot cleared.
7. **When the outcome is generated** — in `AnimationPipeline.start()`, held **in memory only**.
   It is written to disk only at reveal (`markDelivering`).
8. **Animation start** — immediately after capture, same tick.
9. **Delivery** — at reveal: `addItem` then `dropItemNaturally` for overflow.
10. **Disconnect** — during ramp/tension: abort → full refund. After reveal: nothing (tx stays
    registered for the 1–2 s post-reveal window).
11. **Death** — `PlayerDeathListener` (MONITOR) calls `abortTransaction` whenever a tx is registered.
12. **Chunk unload** — abort → refund, also during the post-reveal window.
13. **Machine break** — cancelled for players/explosions/pistons/fluids. Not covered: entity block
    changes (wither), dispensers charging the anchor.
14. **Server crash** — checkpoint survives; recovery returns the item (CAPTURED/ANIMATING) or queues it
    for admin review (DELIVERING).
15. **Plugin disable** — staging items returned; active txs aborted with refund.
16. **Restart** — `StartupRecovery` runs before listeners.
17. **Two interaction events** — main-hand gate only; off-hand not cancelled.
18. **Repeated clicks** — 500 ms debounce map (never cleaned up).
19. **Two players** — machine `AtomicBoolean` CAS; main-thread only in practice.
20. **Concurrent machine access** — same.
21. **External inventory change during tx** — irrelevant after capture (item already removed).
22. **Reward > inventory** — overflow dropped at feet (any player can pick it up).
23. **Machine invalid** — `MachineDataStore.load()` skips machines in unloaded worlds.
24. **Invalid config** — mostly silently clamped; negative global weights throw in `OutcomeRoller`.
25. **Corrupt checkpoint** — logged and skipped (file left in place, never surfaced to admins).

## 3. Confirmed defects

Severity: **S1** item duplication or permanent loss · **S2** data loss / safety guarantee broken ·
**S3** functional bug · **S4** quality.

| # | Sev | Defect | Proof |
|---|---|---|---|
| D1 | S1 | **Death in the post-reveal window duplicates items.** Reward is delivered at reveal, but the tx stays in `TransactionRegistry` for 20–45 ticks. `PlayerDeathListener` calls `abortTransaction`, which refunds the full sacrifice without checking state. With `keepInventory` the player keeps reward + refund. Trivially triggered with `/suicide` or PvP. | `AnimationPipeline.reveal` → `schedulePostRevealCleanup(…, 20L)`; `ItemCaptureService.abortTransaction` has no state check |
| D2 | S1 | **Same duplication via plugin disable / chunk unload** in the post-reveal window (`onDisable` aborts every registered tx). | `VoidMachinePlugin.onDisable`, `MachineBlockListener.onChunkUnload` |
| D3 | S1 | **Death mid-animation without keepInventory loses the item.** The refund is added to the inventory at MONITOR priority, after drops were computed; the server then clears the inventory. Checkpoint is deleted. | `PlayerDeathListener` (MONITOR) + `abortTransaction` → `giveOrDrop` |
| D4 | S1 | **Crash-rollback duplication.** Neither capture nor delivery is coordinated with the player's `.dat` file. If the player's last save predates the capture, a crash returns the item via the checkpoint *and* via the rolled-back player file. | `CheckpointStore` has no link to player data |
| D5 | S1 | **Async delete races a new checkpoint.** `deleteAsync(uuid)` deletes "the most recently modified file for this player". A retry right after a failed lock, or a new ritual right after completion, can have its *new* checkpoint deleted by the stale async task. | `CheckpointStore.findCheckpointFile` + `deleteAsync` |
| D6 | S1 | **Pending deliveries are not durable.** `PendingDeliveryQueue.add` saves asynchronously; `StartupRecovery` deletes the checkpoint asynchronously in parallel. Crash between → item exists nowhere. Join delivery persists the removal *before* delivering. | `PendingDeliveryQueue.saveAsync`, `StartupRecovery.recoverItem` |
| D7 | S1 | **Staging GUI crash window.** The item sits in a non-persisted custom inventory before START. An autosave in that window followed by a crash loses it. | `StagingGui` design |
| D8 | S2 | **Quit-to-refund exploit.** Disconnecting before reveal always refunds (EV 1.0 vs ≈0.34 for playing it out). The outcome is not persisted, so README's "same result on recovery" is false. | `scheduleRamp` → `abortTransaction("player_quit_during_ramp")` |
| D9 | S2 | **Machines in late-loading worlds are erased.** Skipped at load, then the next `save()` rewrites the file without them. | `MachineDataStore.load/save` |
| D10 | S2 | **Overflow rewards can be stolen.** Overflow is dropped with `dropItemNaturally`, no owner — spectators standing around a jackpot can grab it. | `giveOrDrop` |
| D11 | S2 | **Respawn-anchor off-hand interaction not cancelled.** Off-hand glowstone can charge the machine; anchors explode when used outside the Nether. | `MachineInteractionListener` returns early for `OFF_HAND` without cancelling |
| D12 | S2 | **Watchdog can refund after delivery** if timings are configured so that `max-duration-ticks` < animation length (no validation). | `AnimationWatchdog` + no config cross-validation |
| D13 | S3 | Configured but **not enforced** on the live path: cooldowns, daily limit, world allow/block lists, `max-concurrent-animations`, `max-display-entities`, spectator radii, `max-concurrent-sessions`. | grep: only referenced by dead legacy code |
| D14 | S3 | `GlobalStats` top-item counts are lost on reload: `minecraft:diamond` is saved under `top-items.minecraft.diamond` (nested section) and read back as a section. Concurrent async saves can interleave. Not saved on shutdown. | `GlobalStats.save/load` |
| D15 | S3 | Machine names are used raw as YAML keys (dots create nested sections). | `MachineDataStore.save` |
| D16 | S3 | `/vm admin remove` force-loads chunks synchronously and deletes without confirmation. | `adminRemove` |
| D17 | S3 | Hard-coded English `§` strings throughout; player-facing text bypasses `messages.yml`. | multiple |
| D18 | S4 | Anti-spam map never cleaned (`clearSpamEntry` has no caller). | grep |
| D19 | S4 | Dead legacy subsystem (≈1,900 lines) plus ~3 MB of shaded JDBC drivers started on every enable. | §1 |
| D20 | S4 | Corrupt checkpoints are skipped silently and never surfaced; DELIVERING-state items wait on a `/vm admin pending` command that does not exist. | `StartupRecovery`, `VoidMachineCommand` |

## 4. Facts about Paper 26.2 that constrain the design

* `Player#saveData()` is synchronous and writes `<uuid>.dat` via temp file + `Util.safeReplaceFile`
  (old file kept as `.dat_old`). **I/O errors are caught and only logged** — the call cannot be used
  to *prove* that a save succeeded.
* A player's `PersistentDataContainer` is stored inside the same `.dat` file as the inventory, so one
  save persists both atomically.
* `ItemStack#serializeAsBytes()` stores full components + DataVersion (migrates across versions) but
  enforces the codec stack-size limit — oversized stacks (e.g. 320 diamonds) must never be serialized
  as a single stack.
* Non-persistent entities (`Entity#setPersistent(false)`) are never written to disk, so display
  entities cannot survive a crash.

## 5. V2 decisions derived from this audit

1. **The item never leaves the player's inventory until the commit.** The offering screen *selects*
   an inventory slot; nothing is moved into a GUI. Removes D7 and most GUI dupe surfaces.
2. **The verdict is sealed at capture** and written (fsync) to a journal *before* the item is removed.
   Nothing ever re-rolls it; quitting, dying or crashing cannot change it (fixes D8).
3. **No refund paths are triggered by player events.** Death/quit/teleport only defer delivery.
   The only way to return an offering is an explicit admin refund. Removes D1–D3, D12.
4. **Two-party commit with the player file.** Capture and every payout are recorded in a ledger in the
   player's PDC, saved atomically with the inventory. Recovery decides from the *loaded* player file,
   so a crash can never pay twice or pay for an item the rolled-back file still contains (fixes D4).
5. **Journal records are written once and deleted only after the payout is verified from a freshly
   loaded player file** (because `saveData` failures are silent). Fixes D5, D6.
6. Overflow is **held by the Void** (durable) and paid out automatically when there is room; dropping
   is optional and owner-locked (fixes D10).
7. Machines are stored independently of world load state (fixes D9); ids are validated (D15).
8. Everything configured is enforced, validated at startup, and reported precisely (D13, D20).
