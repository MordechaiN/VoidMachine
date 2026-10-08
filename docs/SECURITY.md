# Security

VoidMachine moves items, so its main threats are **duplication** (players getting more than the
verdict) and **loss** (players getting less). This page lists what was considered and how it is
handled. Report vulnerabilities privately to the author rather than in a public issue.

## Duplication and loss

| Attack or accident | Defence |
|---|---|
| Moving, splitting or swapping the offering while the screen is open | The item never enters the screen. On START the slot is re-checked; at capture (after the record is durable) it is checked again: same item (all components), at least the chosen amount, else nothing is taken |
| Shift-click, number keys, double-click, drag, drop key, off-hand swap in the screen | Every click and drag in VoidMachine screens is cancelled at the lowest priority, before other plugins; screens are identified by their holder, not their title |
| Double-clicking START, two players at one machine, two machines at once | One ritual per player, one per machine, a server-wide limit; the second request is refused before anything is recorded |
| Quitting, dying, teleporting, changing world, disconnecting at the reveal | None of these cancel or re-roll a ritual; they only defer delivery. The verdict is fixed in the record |
| Crashing the server (or waiting for a crash) at the right moment | The journal + ledger protocol: every crash point converges to exactly one payout ([Recovery](RECOVERY.md)) |
| Rolling back their own player data (e.g. with a backup) | Admin action; can re-add paid items. Check `/vm admin pending` around restores |
| Offering a filled shulker box or bundle and multiplying its contents | Refused by default (`offering.block-filled-containers`) |
| Over-stacked items from other plugins | The offering amount is capped (≤ 99) and rewards are always split into normal stack sizes |
| Huge rewards | `limits.max-reward-amount` (≤ 6400) caps every verdict; capped verdicts are flagged in the audit log |
| Items of other plugins that must not be multiplied (keys, vouchers, soul-bound items) | `offering.blocked-pdc-keys` and `blocked-materials` |
| Payout into armour or off-hand slots | Rewards go only into the 36 storage slots that free space is computed for |
| A failing drop (overflow = drop) | Only batches that actually spawned count as paid; the rest stays owed |
| Creative-mode players conjuring items to offer | Refused by default (`machines.allow-creative`) |
| Breaking, exploding, pushing or burning the machine to disturb a ritual | Machine blocks are protected (break, block and entity explosions, pistons, fluids, entity changes, fire, dispensers); a chunk unload resolves the ritual immediately |
| Respawn-anchor explosions or spawn setting | Right-clicks on machines never reach vanilla behaviour, for both hands; the charge glow is client-side only |

## Predictability and fairness

- Verdicts use `SecureRandom`, once per ritual, before anything is taken. The result is not derived
  from time, player names, machine positions or anything a player can influence.
- Odds are per profile and identical for every player; they do not depend on spectators, history,
  streaks or the amount offered. The exact percentages are shown in the offering screen.
- Presentation randomness (phase lengths, cue chances, fakeouts, jackpot variants) uses separate
  random streams drawn independently of the verdict. Phases before the reveal have identical lengths
  for every verdict (tested), so nobody can read the outcome from timing.
- Fakeouts only pretend a **worse** verdict and are rate-limited.
- There is no real-money mechanic, no economy hook, no "near miss" manipulation of odds, no streak
  bonus and no pressure to continue; a player cooldown exists by default.

## Admin tools

- Destructive actions (`remove`, `refund`, `release`) require a confirmation code bound to the same
  sender, valid once for 30 seconds, and re-check that the target has not changed.
- `voidmachine.admin.inspect` gives read-only access for moderators.
- All admin actions are written to the audit log with who did what.

## Input handling

- Player names, machine names and item names are inserted into messages as plain text or components,
  never parsed as MiniMessage — players cannot inject formatting or click events.
- YAML is read with SnakeYAML's safe constructor (no arbitrary types), with duplicate keys rejected.
- File names are derived from UUIDs or validated ids (`[a-z0-9][a-z0-9_-]{0,31}`); no user-controlled
  paths.
- Journal records are size-limited (1 MiB) and checksummed; a record that fails the checksum is
  quarantined, never trusted.

## Resource limits

Active rituals, spectators per ritual, display entities, particles and packets per tick, held rewards
per player and the audit queue are all bounded. Interaction spam is debounced; menu clicks are cheap
and never touch the disk. The plugin never loads chunks to run a ritual; an unloaded machine simply
resolves.

## Known limits

- Inventory-sync and offline-inventory-editing plugins can break the ledger/inventory pairing
  ([Recovery](RECOVERY.md#limits-of-the-guarantee-read-this)).
- `overflow: drop` hands items to Minecraft: owner-locked and invulnerable, but subject to normal world
  rules once dropped.
- With `block-unstackable: false` (default), enchanted tools and armour can be multiplied by a win.
  Set it to `true` if that does not fit your server.
