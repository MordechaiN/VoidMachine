# Recovery and transaction safety

This document explains how VoidMachine guarantees that an offering is never lost and never paid twice,
what happens after every kind of interruption, and what an admin can do when something needs a human.

## The guarantee

For every ritual whose offering was taken, the player eventually owns exactly
**offering removed + reward added** — no matter when the server crashes, the player quits or dies,
a save fails, or a chunk unloads. If the offering was never taken, nothing happened at all.

"Eventually" means: as soon as the player is online, alive and has inventory room.

## The protocol

```
 PREPARING ──(record durable)──► capture ──► LIVE ──(presentation)──► REVEALED ──► DONE
     │                             │                    │
     │ write failed / player left   │ slot changed /      │ quit, death, chunk unload, shutdown:
     │ / machine removed            │ ledger unreadable   │ never cancel — only defer delivery
     ▼                             ▼
  ABORTED (nothing taken)        ABORTED (nothing taken)
```

1. **Seal.** The player presses the button. The verdict is rolled once (SecureRandom), the reward is
   computed and capped. Nothing has been taken yet.
2. **Record.** A journal record (`journal/<ritual>.vmj`) is written on a background thread: temp file,
   `fsync`, atomic rename, directory `fsync`. Each record carries a CRC32C checksum. If the write fails,
   the ritual is aborted, nothing is taken, and the server switches to `STORAGE_ERROR` (new offerings
   refused until `/vm admin health recheck` succeeds).
3. **Capture** (main thread, one tick): the offering is removed from the exact slot (all or nothing,
   and only if it is still the same item and amount), a capture mark is added to the **player's
   ledger** (stored in the player's own persistent data), and the player's data is saved. Inventory and
   ledger are saved together in the same file, atomically, by Paper — either both changes reach the
   disk or neither does.
4. **Presentation.** Purely visual. It reads the sealed verdict; it can never change it.
5. **Payout** at the reveal: what fits is placed in the 36 storage slots; the ledger's paid counter is
   updated in the same tick, and the player is saved again. What does not fit is **held** (default) or
   **dropped** owner-locked at the player's feet (`delivery.overflow: drop`).
6. **Proof.** A record with a reward is deleted only when a *freshly loaded* ledger — read from disk
   when the player joins, or from the saved file of an offline player — shows it fully paid. The
   in-memory ledger is never treated as proof, because a save can fail silently.
   A record with no reward (a loss) is deleted right after the reveal; there is nothing to prove.

## Record kinds

| Kind | Created by | Paid when |
|---|---|---|
| `RITUAL` | Every offering | Only if the ledger shows its capture mark (otherwise the offering was never taken and the record is discarded) |
| `CLAIM` | V1 migration (refunds, pending deliveries), admin refunds | Unconditionally, until the ledger shows it paid |
| `REVIEW` | V1 migration, when V1 may already have delivered | **Never automatically.** An admin decides: `refund` (becomes a CLAIM) or `release` |

## What happens when…

| Situation | Result |
|---|---|
| Crash before the record is durable | Nothing was taken. Nothing to do |
| Crash after the record, before capture | On join: the record is discarded; the player is told their offering was never taken |
| Crash after capture, before payout | On join: the player is told the ritual was interrupted, shown the verdict, and paid |
| Crash after payout, payout saved | On join: the fresh ledger proves it; the record is closed. Nothing is paid twice |
| Crash after payout, save failed silently | The player's data on disk is from before the payout, so they do not have the reward — and the ledger on disk says unpaid. On join: paid once |
| Player quits mid-ritual | The ritual continues; the reveal holds the reward; paid on next join |
| Player dies mid-ritual | Payout waits until respawn |
| Chunk or world unloads | The ritual resolves immediately (sealed verdict, paid now) |
| Graceful shutdown | Running rituals resolve immediately; rituals still writing their record are dropped (nothing taken) |
| Inventory full | Held; retried every `delivery.retry-seconds` and whenever the player closes an inventory or drops something; `/vm claim` |
| Plugin reload while players are online | Their ledger is in memory, so records are paid if owed but closed only after their next join |
| Corrupt journal record | Moved to `journal/quarantine/` with a `.reason.txt`; health `RECOVERY_REQUIRED`; nothing deleted |
| A settled record cannot be deleted (disk error) | `STORAGE_ERROR`; the player's ledger entry is kept, so after the disk recovers the record is finalized again — never paid twice |
| Unreadable player ledger | Left untouched as evidence; that player's records are not touched; admins alerted; new offerings from that player are refused (nothing taken) |
| Ledger owes items but the record is missing | Entry kept as evidence; admins alerted |
| Item in a record cannot be decoded (e.g. after a downgrade) | Left untouched; admins alerted |

Players see these messages (Hebrew or English): *"The Void returned what it was holding"*,
*"Your ritual was interrupted — but the Void had already decided: …"*, *"The ritual never began.
Your … was never taken."*, *"The Void is unsettled. An admin has been notified — nothing of yours is
lost."*

## Admin runbook

1. **`/vm admin health`** — what is wrong, since when.
2. **`/vm admin pending [player]`** — unsettled records. Most settle by themselves when the player
   joins; `REVIEW` records need you.
3. **`/vm admin inspect <record>`** — full detail: player, machine, item, verdict, note.
4. Decide:
   - `refund <record>` — the player gets their offering back (V1 reviews: only if V1 did not deliver
     it already — check the V1 logs or ask the player).
   - `release <record>` — delete it. Anything still owed is forfeited; compensate manually first if
     needed.
5. **Quarantined files** (`journal/quarantine/`): read the `.reason.txt`. A record that is only
   damaged can be inspected as text (JSON after the first line). To restore one, fix it and recompute
   the header, or compensate the player manually and delete the file. VoidMachine never deletes them.
6. **`/vm admin health recheck`** — re-probes storage and acknowledges recovery alerts.

Every step above is in the audit log (`audit/YYYY-MM-DD.jsonl`) with the record id.

## Limits of the guarantee (read this)

- **Paper must save player data reliably.** VoidMachine calls `Player#saveData()`; Paper writes the
  file atomically but does not report I/O errors. The protocol is designed so that a failed save never
  loses items (see the table) — but a disk that silently corrupts files is out of scope.
- **Inventory-sync plugins** (cross-server inventories, e.g. via a database) must sync the player's
  persistent data container together with the inventory. If they sync only the inventory, the ledger
  and the items can diverge between servers. Run VoidMachine on one server, or verify your sync plugin.
- **Plugins that edit inventories of offline players** or restore inventory backups can re-add items
  the ledger already counts as paid (a duplication). Restoring a backup is an admin decision; check
  `/vm admin pending` before and after.
- **`overflow: drop`**: once dropped, items follow Minecraft rules (they are owner-locked, invulnerable
  and never despawn, but e.g. a hopper next to the player could take them).
