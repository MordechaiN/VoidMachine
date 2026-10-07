<div align="center">

<img src="VoidMachine%20Logo.png" alt="VoidMachine" width="180">

# VoidMachine

**A ritual machine that swallows an offering and decides its fate.**

*Offer a stack. The machine stirs. The Void decides — once, before the show begins.*

![Paper 26.2](https://img.shields.io/badge/Paper-26.2-d73b3e?style=flat-square)
![Java 25](https://img.shields.io/badge/Java-25-5382a1?style=flat-square)
![Bedrock via Geyser](https://img.shields.io/badge/Bedrock-Geyser%20%2B%20Floodgate-4a4a4a?style=flat-square)
![Languages](https://img.shields.io/badge/languages-Hebrew%20%7C%20English-6a0dad?style=flat-square)
![License MIT](https://img.shields.io/badge/license-MIT-303030?style=flat-square)

</div>

---

VoidMachine turns a block in your world into an artifact. Players walk up, choose a stack from their
inventory and offer it. The machine draws the offering in, the light changes, the sound drops away —
and then the Void gives its verdict: the offering is consumed, partly returned, returned, doubled,
tripled, or, very rarely, multiplied five times in one of five legendary jackpot reveals.

It is an **item sink with theatre**: on the default odds, about 39% of what is offered comes back on
average. There is no money, no Vault, no economy hook — only items, and a moment people talk about.

## Why VoidMachine is different: nothing is ever lost to a bug

Most "sacrifice" plugins take the item, play an animation, and hope nothing goes wrong. VoidMachine 2
treats every offering as a transaction:

- **The verdict is rolled exactly once**, before anything is taken, and written to disk with a checksum
  and `fsync` *before* the item leaves the inventory. The animation never decides anything.
- **The item is taken and a receipt is written into the player's own data, in the same tick and the
  same save.** Either both reach the disk or neither does.
- **A record is only closed when a fresh load of the player's data proves the reward arrived.**
  A crash at any moment — mid-animation, mid-payout, mid-save — is repaired the next time the player
  joins: they get exactly what the verdict promised, never twice, never nothing.
- **Quitting, dying, teleporting or a chunk unloading never cancel or re-roll a ritual.** They only
  delay delivery. Full inventory? The Void holds the rest and returns it as soon as there is room.
- **Corrupt or unknown data is never deleted.** It is quarantined and reported to admins.

This is not a promise in a README: the test suite injects a crash at every step of the protocol,
replays 400 randomized crash sequences against the custody engine, and drives the real plugin through
seeded chaos — crashes, restarts, quits, full inventories and silently failing saves — checking that
every player ends with exactly what they were owed. See [Testing](docs/TESTING.md) and
[Recovery](docs/RECOVERY.md).

## What players experience

1. **Right-click the machine.** A small screen opens with the item in your hand already chosen.
   Tap any other stack in your own inventory to choose it instead, pick how many (1, a quarter, half,
   all), and read the exact odds in the book. Items never move into the screen — you cannot lose them
   by closing it.
2. **Press the offering button.** The verdict is sealed and recorded. The machine draws the offering
   in; the respawn-anchor core starts to charge.
3. **The ritual.** Awaken → capture → stir → ramp → instability → silence. Phase lengths are re-drawn
   for every ritual *independently of the verdict*, so nobody can read the outcome from timing.
4. **The reveal.** Boss bar, title, sound and light — very different for a loss and a jackpot.
   Rewards land in your inventory at this moment.

| Verdict | Default multiplier | Default chance |
|---|---|---|
| Consumed | ×0 | 64% |
| Tithe | ×0.5 (rounded down) | 10% |
| Returned | ×1 | 20% |
| Doubled | ×2 | 4% |
| Tripled | ×3 | 1.8% |
| Jackpot | ×5 | 0.2% |

Odds are per machine profile, identical for every player, and never depend on who is watching, how
much was offered or what happened before. Admins can see them at any time with `/vm admin odds`.

### Five legendary jackpots

A jackpot plays one of five reveals (weighted, never the same one twice in a row on a machine):

- **Void Ascension** — a beam of white light climbs out of the machine and the offering rises with it.
- **The Devourer** — darkness, a heartbeat that speeds up, then a roar.
- **Black Star** — a black star forms above the machine, collapses, and bursts into light.
- **Heart of the Void** — a slow drum, quickening; the machine has a heart, and it beats for you.
- **Null Crown** — the rarest: a crown of soul fire settles on the offerer's head while a bell tolls.

### Fakeouts that never lie

Sometimes (8% of eligible wins by default, rate-limited per player and server-wide) the Void pretends
a lesser verdict before revealing the real one. A fakeout only ever ends **better** than it pretended,
and it is pure theatre: the verdict and the payout were fixed before the first cue.

### Spectators and crowds

Players near a running machine see and hear it in tiers — inner circle, nearby, far away. When a crowd
gathers, effects grow stronger (within a per-tick budget). Big results can be announced to the area or
the whole server (configurable per tier, with a cooldown).

### Idle machines breathe

A machine with players nearby drifts portal smoke and hums; rarely, it surges ("The Void grows
restless…"). Purely atmospheric.

## Bedrock players and Hebrew

- Every screen is a plain chest inventory; every click is cancelled and handled by the plugin, so
  Geyser clients behave exactly like Java clients.
- Boss bars, titles, chat, sounds and particles reach Bedrock players. The floating item display is
  a Java extra (Geyser may not render it); Bedrock players lose nothing important without it.
- **Hebrew is the default language**, English is bundled, and each player gets their own client
  language when a translation exists. Bedrock renders all text left-to-right, which reverses Hebrew;
  VoidMachine pre-orders Hebrew text for Bedrock players only (detected through Floodgate or Geyser,
  with a UUID heuristic as a fallback).

## For admins

- **Create a machine:** look at a block and run `/vm admin create <id> [profile]`. The block becomes
  the core (a respawn anchor by default) and is protected from breaking, explosions, pistons and fire.
- **Health model:** `HEALTHY`, `DEGRADED`, `RECOVERY_REQUIRED`, `CONFIG_INVALID`, `STORAGE_ERROR`.
  If the configuration is invalid or the disk cannot be written durably, new offerings are refused —
  while recovery and payouts keep working.
- **Loud configuration:** every value is validated on start and on `/vm admin reload`, with the exact
  path, the expected value and a hint. A failed reload keeps the previous configuration.
- **Tools:** list, status, pending records, inspect, resolve a running ritual, refund or release a
  record (with confirmation codes), diagnostics with measured tick cost, health re-check, odds,
  previews of any verdict or jackpot variant (nothing is taken or recorded), and a JSON-lines audit
  trail of every step.
- **Data you can read:** journal records are checksummed JSON files; statistics are `stats.json`;
  machines are `machines.yml`.

See [Commands](docs/COMMANDS.md), [Permissions](docs/PERMISSIONS.md) and
[Configuration](docs/CONFIGURATION.md).

## Installation

**Requirements:** Paper 26.2, Java 25. Optional: Floodgate and/or Geyser-Spigot (only used to detect
Bedrock players precisely). Folia is not supported.

1. Put `VoidMachine-2.0.0.jar` into `plugins/` and start the server.
2. Look at a block and run `/vm admin create altar`.
3. Optional: tune `plugins/VoidMachine/config.yml` (odds, limits, announcements) and
   `rituals.yml` (choreography), then run `/vm admin reload`.

**Upgrading from 1.x:** just replace the jar. The old configuration, machines, interrupted rituals,
pending deliveries and lifetime statistics are converted automatically on first start, the originals
are kept in `plugins/VoidMachine/backup/v1/`. Read [Migration](docs/MIGRATION.md) first.

## Configuration at a glance

```yaml
profiles:
  default:
    weights: { consumed: 64, tithe: 10, returned: 20, doubled: 4, tripled: 1.8, jackpot: 0.2 }
    theme: void
    pacing: standard
limits:
  max-reward-amount: 320
  max-active-rituals: 4
delivery:
  overflow: hold   # or: drop (locked to the player, never despawns)
```

Three profiles ship by default — `default`, `brutal` (harsher, slower) and `unstable` (generous,
faster). Assign one per machine with `/vm admin profile <machine> <profile>`.

## For developers

VoidMachine fires synchronous Bukkit events with an immutable view of the ritual:
`RitualStartEvent` (cancellable, before anything is rolled or taken), `RitualCommitEvent`,
`RitualRevealEvent`, `RitualJackpotEvent`, `RitualCompleteEvent` and `RitualRecoveryEvent`.
Listeners can observe; they cannot change a verdict. See [Architecture](docs/ARCHITECTURE.md).

### Building

```bash
./gradlew build          # compiles with Java 25, runs the full test suite, builds build/libs/VoidMachine-2.0.0.jar
./gradlew benchmark      # opt-in performance measurements
```

## Documentation

[Configuration](docs/CONFIGURATION.md) · [Commands](docs/COMMANDS.md) · [Permissions](docs/PERMISSIONS.md) ·
[Recovery](docs/RECOVERY.md) · [Architecture](docs/ARCHITECTURE.md) · [Security](docs/SECURITY.md) ·
[Testing](docs/TESTING.md) · [Compatibility](docs/COMPATIBILITY.md) · [Migration](docs/MIGRATION.md) ·
[Changelog](CHANGELOG.md)

## License

MIT — see [LICENSE](LICENSE). Created by Mordechai Neeman.
