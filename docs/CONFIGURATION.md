# Configuration

VoidMachine reads three kinds of files from `plugins/VoidMachine/`:

| File | Purpose | Reload |
|---|---|---|
| `config.yml` | Rules: odds, limits, cooldowns, delivery, announcements, logging | `/vm admin reload` |
| `rituals.yml` | Choreography: pacing presets, themes, jackpot variants, ambient effects | `/vm admin reload` |
| `lang/he.yml`, `lang/en.yml` | Player-facing text (MiniMessage) | `/vm admin reload` |

Both YAML files are validated on start and on every reload. Problems are reported with their exact
path, what was found, what was expected and a hint, for example:

```
[VoidMachine] ERROR config.yml → offering.max-amount: is out of range | expected: between 1 and 99 | found: 0
```

- **Errors** at startup: the plugin starts in `CONFIG_INVALID` — new offerings are refused, while
  recovery, payouts of held rewards and admin commands keep working. Fix the file and run
  `/vm admin reload`.
- **Errors** on reload: the previous configuration stays active and the errors are shown.
- **Warnings** (unknown keys, missing optional sections, unknown materials in a block list) never
  stop anything.
- Duplicate keys and YAML syntax errors are reported with line and column.

Rituals that are already running keep the settings they started with.

## config.yml

`config-version: 2` must be present. Version 1 files are migrated automatically (see
[Migration](MIGRATION.md)).

### language

| Key | Default | Meaning |
|---|---|---|
| `default` | `he` | Language for players whose client language has no translation, and for broadcasts |
| `use-client-language` | `true` | Use each player's client language when a translation exists |
| `bedrock-rtl-fix` | `true` | Pre-order Hebrew text for Bedrock players (their client draws all text left-to-right) |

Add a language by creating `lang/<code>.yml` (for example `lang/de.yml`); a key it does not define
falls back to the default language, then to English. Your edits to `lang/he.yml` or `lang/en.yml` override the bundled files key by key.

### machines

| Key | Default | Range | Meaning |
|---|---|---|---|
| `core-block` | `RESPAWN_ANCHOR` | placeable block | Block a new machine becomes. The respawn anchor's charge glow is used by the choreography |
| `max-machines` | `10` | 1–500 | |
| `allowed-worlds` / `blocked-worlds` | `[]` | world names | Empty allowed list = every world; blocked wins |
| `allow-creative` | `false` | | Creative players can conjure items, so they cannot offer by default |
| `interact-cooldown-ms` | `400` | 0–10000 | Anti-spam gap between two right-clicks of one player |

### offering

| Key | Default | Range | Meaning |
|---|---|---|---|
| `max-amount` | `64` | 1–99 | Most items in one offering (one slot) |
| `blocked-materials` | books, filled maps | item materials | Never accepted |
| `block-filled-containers` | `true` | | Refuse shulker boxes and bundles that contain items. **Turning this off lets players multiply the contents.** |
| `block-unstackable` | `false` | | Refuse tools, armour and other unstackable items |
| `blocked-pdc-keys` | `[voidmachine:locked]` | `namespace:key` | Items carrying any of these persistent-data keys are refused (mark items of other plugins here) |

### limits

| Key | Default | Range | Meaning |
|---|---|---|---|
| `max-reward-amount` | `320` | 1–6400 | Hard cap on one ritual's reward. Must be ≥ `offering.max-amount`. A capped verdict is flagged in the audit log |
| `max-active-rituals` | `4` | 1–50 | Rituals running at once on the whole server |
| `max-unclaimed-per-player` | `5` | 1–100 | Held rewards one player may have before new offerings are refused |
| `max-ritual-duration-ticks` | `900` | 200–6000 | Watchdog: a longer ritual is resolved immediately (the verdict is kept) |
| `max-spectators-per-ritual` | `32` | 0–200 | Closest spectators who receive effects |
| `max-particles-per-tick` | `1200` | 0–20000 | Shared presentation budget per server tick; above it effects are thinned, never gameplay |
| `max-packets-per-tick` | `400` | 0–10000 | Same, for sounds/particle packets |
| `max-display-entities-per-ritual` | `3` | 0–8 | Floating item displays (Java clients) |

### cooldowns

| Key | Default | Range |
|---|---|---|
| `player-seconds` | `8` | 0–86400 |
| `machine-seconds` | `0` | 0–86400 |

`voidmachine.bypass.cooldown` skips both.

### outcomes

```yaml
outcomes:
  consumed: { multiplier: 0,   tier: loss }
  tithe:    { multiplier: 0.5, tier: partial }
  returned: { multiplier: 1,   tier: neutral }
  doubled:  { multiplier: 2,   tier: win }
  tripled:  { multiplier: 3,   tier: great }
  jackpot:  { multiplier: 5,   tier: jackpot }
```

- `multiplier`: `0`, `0.5`, `1/2`, `x3`… at most 4 decimals, at most ×100. The reward is
  `floor(offered × multiplier)`, capped by `limits.max-reward-amount`.
- `tier` decides how the reveal is staged and must agree with the multiplier: `loss` = 0,
  `partial` = between 0 and 1, `neutral` = 1, `win`/`great`/`jackpot` = above 1.
- Ids: lower case letters, digits, `_` and `-`, up to 32 characters. Add `outcomes.<id>.name` to the
  language files to give a new outcome a display name.

### profiles

```yaml
profiles:
  default:
    weights: { consumed: 64, tithe: 10, returned: 20, doubled: 4, tripled: 1.8, jackpot: 0.2 }
    theme: void          # from rituals.yml
    pacing: standard     # from rituals.yml
    permission: ""       # optional extra permission to use machines with this profile
```

Weights are relative (they need not add up to 100). Rules: finite, not negative, a non-zero weight
must be at least 0.000001, the total at most 1,000,000,000, at least one weight above zero.
Weights are converted to integer units once, so the odds are exact and identical for every roll.
`/vm admin odds <profile>` shows the resulting percentages and the expected return.

Expected return of the bundled profiles (share of offered items that comes back on average):
`default` 39.4%, `brutal` 16.7%, `unstable` 71.0%.

### presentation

| Key | Default | Meaning |
|---|---|---|
| `ritual-chamber-gui` | `false` | Open a chest "chamber" during the ritual. Off by default: the show happens in the world |
| `tether-player` / `tether-radius` | `true` / `1.5` (0.5–8) | Keep the offering player near the machine; the camera stays free, falling is never blocked |
| `spectator-bossbar` | `true` | Inner-circle spectators see the ritual bar |
| `narrate-to-spectators` | `true` | Short action-bar narration for spectators |
| `fakeouts.enabled` / `chance` | `true` / `0.08` (0–1) | Chance per eligible ritual (wins only) |
| `fakeouts.min-rituals-between-per-player` | `4` | |
| `fakeouts.global-cooldown-seconds` | `90` | |
| `fakeouts.patterns` | `[false-loss, escalation]` | `false-loss`: pretends a loss first. `escalation`: pretends a smaller win first |
| `announce.win` / `great` / `jackpot` | `none` / `area` / `server` | `none`, `area` (spectators), `server` (players with `voidmachine.notify`) |
| `announce.server-cooldown-seconds` | `30` | A server announcement inside the cooldown falls back to `area` |

A fakeout's decoy is always strictly worse than the real verdict. Fakeouts never change the verdict
or the payout, which are fixed before the ritual starts.

### spectators

| Key | Default | Range |
|---|---|---|
| `inner-radius` / `near-radius` / `far-radius` | `6` / `14` / `28` | must increase |
| `refresh-ticks` | `10` | 2–100 |
| `crowd.min-for-bonus` | `2` | onlookers before intensity rises |
| `crowd.max-intensity` | `1.6` | 1.0–3.0 |
| `crowd.full-at` | `6` | ≥ `min-for-bonus` |

### ambient

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Idle machines with a player within `radius` (4–96, default 24) breathe |
| `void-events.enabled` | `true` | Rare surges on an idle machine |
| `void-events.min-interval-minutes` / `max-interval-minutes` | `10` / `45` | |

### delivery

| Key | Default | Meaning |
|---|---|---|
| `overflow` | `hold` | `hold`: the Void keeps what does not fit (durable) and returns it automatically when there is room; players can also use `/vm claim`. `drop`: dropped at the player's feet, owner-locked, never despawns, immune to damage |
| `retry-seconds` | `5` (1–600) | How often held rewards are retried for online players |

### logging

| Key | Default | Meaning |
|---|---|---|
| `level` | `info` | `quiet`, `info` (one line per ritual), `debug` |
| `audit.enabled` | `true` | JSON-lines audit trail in `audit/YYYY-MM-DD.jsonl` |
| `audit.retention-days` | `30` | 0 = keep forever |

### stats

| Key | Default |
|---|---|
| `enabled` | `true` |
| `save-interval-seconds` | `60` (10–3600) |

## rituals.yml

Pure presentation: nothing in this file can change a verdict or a payout. Validated against the
server's real sound and particle registries — an unknown sound or a particle that needs data
VoidMachine cannot supply is an error, not a silent no-op.

### pacing

Each phase has a length in ticks; `[min, max]` values are re-drawn for every ritual, independently
of the verdict.

| Phase | Shown for | Purpose |
|---|---|---|
| `awaken`, `capture`, `stir`, `ramp`, `instability`, `silence` | every ritual | Identical structure for every verdict — timing reveals nothing |
| `false-reveal`, `destabilize` | fakeouts only | The pretence and its collapse |
| `reveal` | every ritual | The verdict (jackpots use their variant's `hold` instead) |
| `aftermath` | every ritual | The dust settles |

Bundled presets, on average: `standard` ≈ 12.7 s, `swift` ≈ 9.1 s, `epic` ≈ 18 s — plus the fakeout
phases when one is played, and a jackpot's `hold` (110–140 ticks) instead of `reveal`. A pacing whose worst case exceeds `limits.max-ritual-duration-ticks` is rejected.

### themes

A theme has a script per phase (`phases`), per verdict tier (`reveals`), a list of `jackpots`
(each with `weight`, `hold`, `bossbar-color` and `cues`), `ambient.idle` and `ambient.void-event`.

### cues

Each cue has `at` (tick offset), optional `chance` (0–1) and `to` (`owner`, `inner`, `near`, `all`),
and exactly one of:

| Cue | Options |
|---|---|
| `sound: block.beacon.activate` | `volume`, `pitch` (number or ramp `"0.5..1.2"` following phase progress), `jitter` (0–0.5), `channel` |
| `particle: portal` | `count` (number or ramp), `spread`, `speed`, `anchor` (`machine`, `top`, `above`, `owner`), `shape` (`point`, `ring`, `column`, `spiral` with `radius`, `height`, `points`), `color`/`to-color`/`size` for coloured particles; `item` shows the offering |
| `lightning: true` | Visual only — no damage, no fire |
| `display: rise` | `rise`, `spin`, `pulse`, `implode`, `ascend`, `hide`, `reward` — the floating item (Java clients) |
| `charge: 0..4` | Respawn-anchor glow, sent to clients only (the real block never changes) |

A cue that fails at run time on an unusual server (for example an effect a fork does not support) is
skipped and reported once; it never stops a ritual.

## Files written by the plugin

| Path | Content |
|---|---|
| `machines.yml` | Registered machines (unreadable entries are kept, never dropped) |
| `journal/*.vmj` | One checksummed record per ritual that is not settled yet |
| `journal/quarantine/` | Records that failed their checksum, with a `.reason.txt` — never deleted automatically |
| `stats.json` | Lifetime statistics (a corrupt file is moved aside, not overwritten) |
| `audit/*.jsonl` | Audit trail |
| `backup/v1/` | Your V1 files after migration |
