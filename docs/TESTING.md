# Testing

```bash
./gradlew test        # 124 tests, about 45 s
./gradlew benchmark   # 5 opt-in measurements (tagged "benchmark")
./gradlew benchmark -Pjfr=/tmp/vm.jfr   # the same, with a Java Flight Recorder profile
```

Every test must run: a test that is skipped or aborted fails the build. (MockBukkit reports features
it does not implement as "aborted"; a JUnit extension turns that into a failure with the original
stack trace, so no test can silently stop before its assertions.) Integration tests also fail if the
plugin logged an unexpected warning or error.

## Suites

| Suite | Tests | What it proves |
|---|---|---|
| `core.custody.CrashConsistencyTest` | 3 | A simulated player whose saves can crash or silently fail. A crash is injected at **every** step of the protocol (live ritual and recovery itself), then 400 randomized crash sequences, then reloads with silently failing saves. Every run must converge to `offering removed + reward added` exactly once |
| `core.custody.CustodyEngineTest` | 7 | Capture refuses changed slots and corrupt ledgers without taking anything; partial payouts accumulate exactly; claims pay without a capture mark; rituals without one never pay; the full decision table |
| `core.journal.FileJournalTest` | 8 | Round trip, corrupt files quarantined (not deleted), renamed records rejected, temp files cleaned, replace needs an existing record, storage probe |
| `core.ledger.PlayerLedgerTest` | 4 | Round trip, empty ledger, double capture refused, malformed ledgers rejected |
| `core.outcome.OutcomeEngineTest` | 9 | Multiplier parsing, rounding and overflow, tier/multiplier agreement, invalid weights rejected with reasons, zero weights never chosen, distribution matches weights (statistical test), seeded determinism, cap flagged, expected return |
| `core.timeline.TimelineComposerTest` | 8 | Pre-reveal phases are identical for every verdict (no timing leak), segments contiguous, fakeouts only pretend worse verdicts, are rate-limited and rotate, jackpot variants never repeat immediately |
| `core.config.ConfigLoadingTest` | 7 | Bundled files load with zero problems against the real Paper 26.2 sound and particle registries; invalid configs fail with precise paths; V1 config migrates with identical odds; V1 machines migrate without losing entries |
| `core.property.PropertyTest` | 5 | Generated cases: every single-bit flip and every truncation of a journal record is rejected; random bytes never decode; ledger and multiplier round trips; the engine never picks a zero weight or exceeds the cap |
| `core.MiscCoreTest` | 9 | Health fails closed, effect budget fairness and caps, crowd model, spectator tiers, Hebrew bidi reordering (styles and non-text parts preserved), statistics persistence and corrupt-file handling, id rules |
| `i18n.LanguageFilesTest` | 4 | Hebrew and English have identical keys and shapes; every text renders with no unknown tag or placeholder; every key the code uses (literally or composed) exists in both |
| `it.RitualFlowTest` | 15 | The real plugin on MockBukkit, end to end: menu → record → capture → presentation → payout → proof on rejoin; each verdict; partial offerings; item components preserved; jackpots (every variant plays its whole script); full inventory hold and claim; overflow drop locked to the owner; a failed drop stays owed; displays and boss bars cleaned up; cancelled start event; reveal event carries the sealed verdict; admin preview takes and records nothing |
| `it.RecoveryTest` | 11 | Quit, death, crash before and after capture, crash after a saved or an unsaved payout, graceful shutdown mid-ritual, offering moved before capture, quit before capture, chunk unload, reload during a ritual |
| `it.ConcurrencyTest` | 7 | Two players pressing start on one machine, 20 start clicks, 200 interact events, every click type on every slot of the menu (all cancelled, nothing moves), tapping another stack, global ritual limit, eight simultaneous rituals with spectators |
| `it.AdminTest` | 11 | Players cannot use admin commands (nor see them in completion), confirmations bound to sender and single-use, busy machines need `--force`, REVIEW records never paid automatically, refund and release, stale confirmations change nothing, resolve, read-only tools, enable/disable, console |
| `it.FailClosedTest` | 7 | Invalid config refuses offerings but still pays what is owed; a bad reload keeps the previous config; broken storage refuses offerings and takes nothing, then recovers; corrupt records quarantined; unreadable ledgers never overwritten; owed entries without a record kept as evidence; a record whose deletion fails keeps its ledger entry, so it is finalized — not paid again — after the disk recovers |
| `it.LocalizationTest` | 4 | English client, Hebrew client, unknown language → Hebrew default, Bedrock Hebrew pre-ordered while Java Hebrew is not |
| `it.MigrationTest` | 1 | A V1 installation with config, machines, an interrupted ritual, an ambiguous delivery, a pending delivery and statistics is upgraded once, pays exactly once, and is not re-migrated after a restart |
| `it.ChaosTest` | 4 | Four seeds × 260 random steps on the real plugin: offers, quits, joins, full inventories, silently failing saves, chunk unloads, admin resolves, crashes and graceful restarts. Afterwards every player must own exactly `initial − offered + reward` over every ritual whose capture reached their data file, all records must be settled and all ledgers clean |

### Do the tests catch real bugs?

They were checked by deliberately breaking the code (mutation testing) and confirming a test fails:

| Mutation (in `CustodyEngine`) | Failing tests |
|---|---|
| Trust the in-memory ledger as proof of payment | `CrashConsistencyTest`, `CustodyEngineTest`, `ChaosTest` (2 seeds; up to 128 items lost) |
| Pay a ritual whose capture mark is missing (instead of discarding it) | `CrashConsistencyTest` (3), `CustodyEngineTest` (2), `ChaosTest` (4 seeds), `RecoveryTest` |
| Remove the capture-mark check inside the payout | `CustodyEngineTest` (the decision table already discards such records — defence in depth) |
| Record one item less than was paid | 19 tests across `ChaosTest`, `CrashConsistencyTest`, `RecoveryTest`, `RitualFlowTest`, `MigrationTest`, `AdminTest`, `CustodyEngineTest` |
| Skip the save after a payout | `RecoveryTest` |
| Refund without re-checking a stale record (in the command) | `AdminTest.staleConfirmationChangesNothing` |

### Bugs the tests found during development

- Rewards could be placed in armour/off-hand slots by `addItem` on some implementations — now placed
  only in the 36 storage slots that capacity is computed for.
- An exception while dropping overflow could lose or duplicate the batch — now counted per batch.
- A single failing effect ended the whole presentation — cues are now isolated.
- A payout exception could leave a machine and a player locked for the session.
- The "machine is busy" message showed a raw `<player>` tag.
- A case-altered checksum header was accepted.
- A stale refund confirmation raised a false `STORAGE_ERROR`.
- **A failed journal delete still forgot the player's ledger entry**, so after a disk error and a
  restart a fully paid claim looked unpaid and would have been paid again. The ledger entry is now
  forgotten only after the file is really gone.

## Static analysis

- The build compiles main and test code with `-Xlint:all` (minus `serial` and `processing`): **zero
  warnings**. The only suppression in the repository is in the test double `VmServerMock`, for a raw
  return type it inherits from MockBukkit's `ServerMock`.
- [Error Prone](https://errorprone.info) 2.42.0 was run over all production sources (not wired into
  the Gradle build, which would need the Error Prone Gradle plugin and JDK-internal exports).
  It reported 26 findings; all were fixed except two `ArrayRecordComponent` notes on `JournalRecord`
  and `V1Checkpoint`, which hold serialized item bytes: both copy the array defensively and implement
  `equals`/`hashCode`/`toString` with array semantics. Fixed findings included reliance on enum
  declaration order (`ordinal()`) for health severity, tier rank and log level, an ignored
  `teleportAsync` future, an implicit time zone, a non-exhaustive switch, a public mutable array and
  unused fields.

## The test harness

- `Harness` boots the real plugin on MockBukkit 4 (`mockbukkit-v26.2`) with single-outcome test
  profiles (so every verdict is known in advance without any test hook in production code).
- `TestPlayer#saveData` behaves like Paper's: it writes inventory and persistent data together into a
  stand-in data file and can fail silently. `loadSaved` restores what the server would load after a
  crash. `Harness#crashAndRestart` discards all in-memory state without running shutdown logic.
- `TestWorld` and `VmServerMock` implement the few server features MockBukkit leaves out and the
  plugin uses (visual lightning, owner-locked drops, `Inventory#getHolder(false)`).

## Benchmarks (measured)

Run on the development container: Linux, 4 cores, Java 25.0.4, after a warm-up round.
The tick benchmarks run on MockBukkit, so they measure VoidMachine's own CPU work per tick
(scheduling, budget, audience, cues, text rendering, menus) — **not** packet encoding, network I/O or
the real cost of `Player#saveData()` on a Paper server.

| Measurement | Result |
|---|---|
| Ritual director, 1 jackpot ritual, 20 players nearby | avg 0.10 ms/tick, p99 0.92 ms |
| Ritual director, 4 concurrent jackpot rituals, 20 players | avg 0.15 ms/tick, p99 2.90 ms, max 2.97 ms |
| Ritual director, 8 concurrent jackpot rituals, 40 players | avg 0.20 ms/tick, p99 2.87 ms, max 3.21 ms |
| Ambient animation, 10 idle machines, 20 players nearby | avg 0.015 ms per tick, max 1.03 ms |
| Durable journal create (write + fsync + rename + dir fsync), off the main thread | p50 0.86 ms, p99 1.52 ms |
| Journal delete | p50 0.41 ms, p99 1.14 ms |
| Outcome decision (SecureRandom) | ~2.8 million per second (≈ 360 ns) |
| Hebrew bidi reordering for a Bedrock player | ≈ 6.9 µs per 61-character message |

A server tick has 50 ms. The p99 values come from the heavy ticks (reveal: payout, titles, display
spawn, client block changes); most ticks cost well under 0.2 ms. With 4 jackpots running at once and
20 players, the shared effect budget thinned about 3,500 particles over the rituals — that is the
budget doing its job, not a problem.

Not measured here (no Paper server available in this environment): the cost of `Player#saveData()`,
which VoidMachine calls twice per ritual on the main thread (capture and payout), as Paper's own
autosave does for every player. On a typical SSD this is a single small file write; measure it on your
hardware with `/vm admin diagnostics` and Paper's own timings.
