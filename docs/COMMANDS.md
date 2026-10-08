# Commands

Main command `/voidmachine`, alias `/vm`. Tab completion only suggests what the sender may use.

## Players

| Command | Permission | What it does |
|---|---|---|
| `/vm` or `/vm help` | — | Short help in the player's language |
| `/vm stats` | `voidmachine.stats` | Your rituals, wins, jackpots, items offered and returned |
| `/vm stats server` | `voidmachine.stats` | Server totals |
| `/vm top [rituals\|jackpots\|returned]` | `voidmachine.stats` | Leaderboards |
| `/vm claim` | `voidmachine.claim` | Collect rewards the Void is holding (e.g. after a full inventory). Held rewards also come back automatically when there is room |

Using a machine is a right-click and needs `voidmachine.use` (plus the profile's extra permission,
if one is set).

## Admins

`voidmachine.admin` allows everything below. `voidmachine.admin.inspect` allows the read-only
commands (marked ●).

### Machines

| Command | What it does |
|---|---|
| `/vm admin create <id> [profile] [display name]` | Turns the block you look at (within 6 blocks) into a machine. Containers are refused. The block is changed to `machines.core-block` |
| `/vm admin remove <id> [--force] [--clear-block]` | Removes a machine after confirmation. A running ritual blocks removal unless `--force` (the ritual is resolved with its sealed verdict). The block stays unless `--clear-block` |
| `/vm admin list` ● | All machines and their state |
| `/vm admin status <id>` ● | Details of one machine |
| `/vm admin enable <id>` / `disable <id>` | A disabled machine refuses new offerings |
| `/vm admin profile <id> <profile>` | Assigns an odds profile |
| `/vm admin tp <id>` | Teleports you to a machine |
| `/vm admin odds <profile>` ● | Exact probabilities and expected return of a profile |

### Rituals and records

| Command | What it does |
|---|---|
| `/vm admin rituals` ● | Rituals running now |
| `/vm admin resolve <ritual>` | Ends a running ritual immediately; the player receives its sealed verdict |
| `/vm admin pending [player]` ● | Journal records not settled yet (offline players, held rewards, V1 reviews) |
| `/vm admin inspect <record>` ● | Everything about one record |
| `/vm admin refund <record>` | After confirmation, changes the record so the player gets their offering back instead of the verdict (only when the verdict returns less than the offering). Paid immediately if online, otherwise on join |
| `/vm admin release <record>` | After confirmation, deletes a record. **Anything it still owed is forfeited.** For players who will never return, or after compensating manually |

Record ids may be shortened to their first characters (at least 4), as shown by `pending`.

### Operations

| Command | What it does |
|---|---|
| `/vm admin reload` | Reloads `config.yml`, `rituals.yml` and the languages. A failed reload keeps the previous configuration and lists the errors |
| `/vm admin health` ● | Health state and every open problem |
| `/vm admin health recheck` | Probes the journal storage again (clears `STORAGE_ERROR` when writing works) and acknowledges recovery alerts |
| `/vm admin diagnostics` ● | Versions, Bedrock detection, machines, rituals, journal and quarantine counts, measured tick cost of the ritual director and ambient effects, effect budget usage, audit queue |
| `/vm admin preview <outcome> [variant\|false-loss\|escalation]` | Plays the full choreography of any verdict, jackpot variant or fakeout at the nearest machine, for you. Nothing is taken, recorded, paid, counted, announced, or fired as an API event |
| `/vm admin voidevent <id>` | Triggers an ambient surge on an idle machine |
| `/vm admin confirm <code>` | Confirms a destructive action |

### Confirmations

`remove`, `refund` and `release` first describe exactly what will happen and print a five-letter
code. Only `/vm admin confirm <code>` from the same sender within 30 seconds performs it. If the
machine or record changed in the meantime (for example the record was settled), nothing happens and
you are told why.
