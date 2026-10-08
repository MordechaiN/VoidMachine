# Architecture

VoidMachine 2 is a rewrite. It is split into a **core** that knows nothing about the server and a
**Paper layer** that adapts it to Bukkit. Everything that decides what a player owns lives in the core
and is tested without a server.

```
com.voidmachine
├── VoidMachinePlugin          entry point: start/stop the runtime, fail closed on startup errors
├── api                        RitualView, MachineView and the six Bukkit events (public, stable)
├── core                       pure Java (+ Gson for files, Adventure for text reordering)
│   ├── outcome                Multiplier, OutcomeTier, OutcomeTable (integer weights), OutcomeEngine, Verdict
│   ├── journal                JournalRecord, JournalCodec (CRC32C), FileJournal (fsync, atomic rename, quarantine)
│   ├── ledger                 PlayerLedger: per-player capture marks and paid counters
│   ├── custody                CustodyEngine: capture, pay, reconcile — the safety protocol
│   ├── health                 HealthMonitor: problem sources → health state → accept or refuse offerings
│   ├── config                 Node (typed, path-aware reader), SettingsLoader, Settings, problems
│   ├── script                 Cue, Script, Theme, Ritualbook, RitualsLoader (rituals.yml)
│   ├── timeline               Phase, Pacing, RitualTimeline, TimelineComposer, FakeoutPlanner, VariantPicker
│   ├── spectator              SpectatorTier, CrowdModel
│   ├── budget                 EffectBudget: fair per-tick particle/packet shares
│   ├── stats                  StatsBook, StatsStore
│   ├── text                   BidiText: visual ordering of Hebrew for Bedrock
│   ├── machine                MachineRecord, MachineFile (V1 and V2 formats)
│   └── migration              V1ConfigMigration, V1Checkpoint
└── paper                      Bukkit adapters
    ├── VoidMachineRuntime     wiring, startup order, background tasks, reload, shutdown
    ├── ritual                 RitualService (lifecycle), CustodyService, JournalService (I/O thread), ActiveRitual
    ├── presentation           RitualDirector (one tick task for all rituals), CuePlayer, CueGuard,
    │                          DisplayService, MachineVisuals, RitualBars, Audience, AmbientService
    ├── menu                   OfferingMenu, ChamberMenu, MenuListener
    ├── listener               MachineListener, PlayerListener, WorldListener
    ├── command                VoidMachineCommand, Confirmations
    ├── item                   ItemCodec, OfferingRules, BukkitCustodyPlayer
    ├── machine                Machine, MachineRegistry, MachineStore
    ├── config                 ConfigService, Yamls (safe YAML), PaperGameRegistry
    ├── i18n                   Messages, BedrockDetector
    ├── audit                  AuditLog (JSON lines, own thread)
    ├── diag                   Diagnostics, TickProfiler
    └── migration              LegacyDataMigrator (V1 data files)
```

## Principles

- **Truth before theatre.** The verdict is computed once (`OutcomeEngine.decide`) and stored in an
  immutable `Verdict` inside the journal record before anything is taken. The presentation receives
  a read-only view; nothing it does can reach the verdict or the payout.
- **One owner per state.** `RitualService` owns ritual state transitions. `CustodyEngine` owns item
  movement and the ledger. `JournalService` owns the files. The director owns only visuals.
- **Durable before destructive.** Records are durable before items move. Items and the ledger change
  in the same tick and reach disk in the same save.
- **Proof from disk.** Settled records are closed only on evidence loaded from disk
  (`CustodyEngine.Mode.FRESH_LOAD`); in-memory evidence (`LIVE`) only ever defers.
- **Fail closed.** Invalid configuration or unwritable storage stops new offerings — never recovery.
- **Isolation.** A failing ritual presentation is resolved with its sealed verdict; a failing cue is
  skipped; a failing payout leaves the record for the retry path; none of them blocks other rituals.

## Threads

| Thread | Work |
|---|---|
| Server main thread | Everything that touches the world, inventories, players, ledgers and menus |
| `VoidMachine-Journal` (one thread) | Journal writes, deletes and probes; results return to the main thread through the scheduler |
| `VoidMachine-Audit` (daemon) | Appending audit lines (bounded queue; overflow is counted, never blocks the server) |

No other asynchronous work happens. Startup loads the journal synchronously (it must be known before
players can join), and shutdown waits up to 5 seconds for queued journal I/O.

## Startup order

1. Configuration: copy defaults, migrate a V1 `config.yml` (with backup), validate `config.yml` and
   `rituals.yml`. Invalid → `CONFIG_INVALID`, continue for recovery.
2. Audit log, Bedrock detection, languages.
3. Journal (probe write access, load records, quarantine corrupt ones), statistics.
4. Machines (`machines.yml`, V1 format converted with backup).
5. V1 data files (checkpoints, pending deliveries, statistics) → journal records, once.
6. Services; reconcile players already online (plugin reload) with `LIVE` evidence.
7. Remove orphaned display entities near machines in loaded chunks.
8. Listeners, command, background tasks (retry every second, offline verification every 5 minutes,
   storage health every 30 seconds, statistics save).
9. A short report in the console: configuration, machines, pending records, quarantine, health.

## Shutdown

Stop accepting offerings → resolve every live ritual immediately (sealed verdict, paid now) → remove
bars, displays and client-side block changes → cancel tasks → drain journal I/O → delete records of
rituals that never took anything → save statistics → close the audit log.

## Ritual lifecycle in code

`RitualService.begin` (checks, `RitualStartEvent`, roll, record) → `JournalService.create` (I/O
thread) → `RitualService.onRecordWritten` (main thread: `CustodyEngine.capture`, `RitualCommitEvent`,
`Presentation.start`) → `RitualDirector.tick` (each server tick while rituals run) → at the reveal
tick `RitualService.reveal` (`CustodyService.pay`, statistics, audit, `Presentation.revealed`,
`RitualRevealEvent`) → after the aftermath `RitualService.finish` (cleanup, cooldowns,
`RitualCompleteEvent`).

`resolveNow` jumps to reveal and finish; it is used by admins, unloads, shutdown, the watchdog and the
presentation error path.

## Presentation

- **Timeline.** `TimelineComposer` builds the ritual from the pacing: phases before the reveal are drawn
  from the presentation seed *before* the verdict is consulted, so their lengths are identical for every
  verdict (tested). Fakeout phases are inserted only for eligible wins; jackpots use their variant's hold.
- **Random streams.** The presentation stream (cue chances, jitter) and the planning stream (fakeout,
  jackpot variant) are separate, so adding a fakeout never changes which cues fire.
- **Cues** come from `rituals.yml`. `CuePlayer` turns them into sounds, particles, lightning (visual
  only), display actions and client-side respawn-anchor charge changes.
- **Audience.** Players around the machine are classified (owner, inner, near, far) every few ticks;
  each cue says who receives it. Crowds raise intensity up to a cap.
- **Budget.** `EffectBudget` divides a per-tick particle/packet allowance fairly between running
  rituals. Exceeding it thins effects; it never delays a ritual or a payout.
- **Hygiene.** Display entities are non-persistent and tagged with the plugin's key; orphans are swept
  on startup and when chunks with machines load. Charge glows are client-side only and restored.
  Boss bars are removed on finish. The director's tick task exists only while rituals run.

## Events (API)

All events are synchronous and carry an immutable `RitualView` (ritual id, player, machine, profile,
offering copy, input amount, outcome id, tier, multiplier, reward).

| Event | When |
|---|---|
| `RitualStartEvent` (cancellable) | Before anything is rolled, recorded or taken |
| `RitualCommitEvent` | The offering was taken and the verdict is final |
| `RitualRevealEvent` | The verdict is shown and paid (carries the fakeout pattern and crowd size) |
| `RitualJackpotEvent` | After a jackpot reveal (carries the variant id) |
| `RitualCompleteEvent` | The ritual ended (carries items paid and items still held) |
| `RitualRecoveryEvent` | An interrupted or deferred ritual was settled later |

Admin previews fire no events.

## Data formats

- **Journal record** (`.vmj`): first line `VMJ2 <crc32c-hex>`, then compact JSON (format version 2):
  ids, player, machine, profile, creation time, item key, base64 item template
  (`ItemStack#serializeAsBytes` of one item, so components and data versions survive upgrades),
  input amount, outcome id, tier, multiplier, reward, kind, revision, note. Maximum 1 MiB.
- **Player ledger** (player persistent data, key `voidmachine:ledger`):
  `v1|<ritual-uuid>:<paid>:<reward>|…`.
- **Statistics:** `stats.json`, replaced atomically.
- **Audit:** one JSON object per line: time, event, fields.
