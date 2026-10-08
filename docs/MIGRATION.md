# Upgrading from VoidMachine 1.x

2.0 is a rewrite with new data formats. The upgrade is automatic and keeps everything that matters,
but read this page first.

## Before you upgrade

1. Update the server to **Paper 26.2** and **Java 25** (2.x does not run on older versions).
2. Stop the server cleanly. Rituals that V1 left unfinished are converted, but a clean stop leaves
   fewer of them.
3. Make a backup of `plugins/VoidMachine/` (VoidMachine also backs up what it converts, but your own
   backup is the safest).
4. Replace the jar and start the server. Read the console: every conversion step is reported with
   `[Migration]`.

## What is converted

| V1 file | What happens | Original kept in |
|---|---|---|
| `config.yml` | Converted to the V2 format with the **same odds** (see below); comments and layout of the V2 template; every dropped or changed setting is reported | `backup/v1/config.yml` |
| `messages.yml` | Not converted (V2 texts are new). Copy your customisations into `lang/he.yml` / `lang/en.yml` | `backup/v1/messages.yml` |
| `machines.yml` | Converted; invalid names become valid ids (e.g. `Dark Altar` → `dark_altar`, display name kept); entries that cannot be read are reported and **kept** in the file, never dropped | `backup/v1/machines.yml` |
| `checkpoints/tx-*.dat` (unfinished V1 rituals) | V1 had taken the item but never stored the outcome → becomes a **claim** that gives the offering back on the player's next join. If V1 was already delivering (it may have paid), it becomes a **review** record for an admin | `backup/v1/checkpoints/` |
| `pending_deliveries.yml` | Each owed stack becomes a claim, paid on the player's next join (delivering-state entries become review records) | `backup/v1/pending_deliveries.yml` |
| `global_stats.yml` | Lifetime totals imported into `stats.json` | `backup/v1/global_stats.yml` |

Conversion runs **once**: a `.v1-migrated` marker prevents converting the same files again (which
could recreate claims that were already paid). Files that could not be converted stay where they are
and are reported; health shows `RECOVERY_REQUIRED` until an admin looks at them.

### Odds

V1 outcomes map to V2 outcomes: destroyed → `consumed`, returned → `returned`, doubled → `doubled`,
tripled → `tripled`, jackpot_x5 → `jackpot`. V1 profiles keep their names (made valid if needed) and
their weights; the new `tithe` outcome (half back) gets weight 0 in converted profiles, so the odds are
exactly what they were. V1 multipliers are kept (clamped to V2's maximum of ×100).

### Settings that changed meaning

| V1 | V2 |
|---|---|
| `limits.max-insert-amount` | `offering.max-amount`, at most 99 (one inventory slot) |
| `limits.max-return-amount` | `limits.max-reward-amount` |
| `limits.clamp-on-overflow` | Removed: rewards are always capped |
| `cooldown.per-player-seconds` | `cooldowns.player-seconds` — **now enforced** (V1 did not enforce it) |
| `cooldown.global-seconds`, `cooldown.daily-limit` | Removed (V1 did not enforce the daily limit) |
| fakeout `chance-1-in` | `presentation.fakeouts.chance` (= 1 / value) |
| crowd `min-players` (counted the offering player) | `spectators.crowd.min-for-bonus` (onlookers only, so value − 1) |
| broadcast list | `presentation.announce.great` / `jackpot` (`server` if V1 broadcast them, else `area`) |
| `plugin.debug` | `logging.level: debug` |

## Behaviour changes players will notice

- **Items never move into the screen.** Players choose a stack from their own inventory and an amount.
- **The verdict is fixed when they press the button**, recorded before the item is taken.
- **Leaving, dying or a crash never loses an offering**; the verdict is delivered on return.
- **A full inventory never loses a reward**: the Void holds it (`/vm claim`).
- **Exact odds** are shown in the screen.
- Hebrew is the default language; players with an English client see English.
- New verdict *Tithe* (half back) in the default profiles of fresh installs.
- The player cooldown (8 s by default) is enforced.

## Behaviour changes admins will notice

- New permissions: `voidmachine.claim`, `voidmachine.admin.inspect`, `voidmachine.admin.alerts`.
  `voidmachine.history`, `voidmachine.top` and `voidmachine.reload` no longer exist (use
  `voidmachine.stats` and `voidmachine.admin`). The `/void` alias was removed; `/vm` remains.
- Commands moved under `/vm admin`. See [Commands](COMMANDS.md).
- `config.yml` is validated strictly; an invalid file refuses offerings instead of running with
  surprises.
- Choreography moved to `rituals.yml`.
- **DiscordSRV forwarding was removed.** V1 could post big results to a Discord channel through
  DiscordSRV (`events.discord`, off by default). V2 does not include that bridge; the migration notes
  tell you if you had it enabled. To keep it, listen to `RitualRevealEvent` / `RitualJackpotEvent` in a
  small bridge plugin (or a script plugin) and post through DiscordSRV's API.
- The PlaceholderAPI soft dependency was removed (V1 declared it but never used it).

## Downgrading

Not supported. V1 cannot read V2 records. If you must go back, first let every player with pending
records join (or settle them with `/vm admin pending`), then restore your backup.
