<div align="center">

# ⬛ VoidMachine

**A cinematic item-sacrifice ritual machine for serious survival servers.**

*Place your offering. Press the button. The Void decides.*

<p>
  <a href="https://github.com/MordechaiNeeman/VoidMachine/actions">
    <img alt="Build" src="https://img.shields.io/github/actions/workflow/status/MordechaiNeeman/VoidMachine/ci.yml?branch=main&style=flat-square">
  </a>
  <a href="https://github.com/MordechaiNeeman/VoidMachine/releases">
    <img alt="Release" src="https://img.shields.io/github/v/release/MordechaiNeeman/VoidMachine?include_prereleases&sort=semver&style=flat-square">
  </a>
  <img alt="Paper" src="https://img.shields.io/badge/Paper-1.21.4+-d73b3e?style=flat-square">
  <img alt="Java" src="https://img.shields.io/badge/Java-21-5382a1?style=flat-square">
  <img alt="Bedrock" src="https://img.shields.io/badge/Geyser-compatible-4a4a4a?style=flat-square">
  <a href="LICENSE">
    <img alt="License" src="https://img.shields.io/badge/license-MIT-303030?style=flat-square">
  </a>
</p>

</div>

---

> ⚠️ **VoidMachine is not a casino plugin.**
> It is a crash-safe item sink with cinematic tension, built to create permanent moments in your server's mythology.

---

## 🌑 What is VoidMachine?

VoidMachine places a **physical ritual machine** in your Minecraft world. Players must walk up to it. They place an item into the offering slot, confirm their sacrifice, and wait.

The machine locks. Six void-themed reel symbols begin to spin. They decelerate, stop column by column — and the last symbol drops with a near-miss before the verdict is revealed.

The Void does not spin a wheel. It holds a ritual.

**No Vault. No virtual currency. No coins.** Every transaction moves real, physical item stacks directly from inventory to outcome. The machine is a voluntary, high-tension drain on your server's item economy.

---

## ⚡ The Ritual

The machine operates in three acts.

**I. The Offering**

The player right-clicks the machine block. A staging interface opens. They place an item — any item — in the offering slot. At any moment, they can take it back. No commitment has been made.

**II. The Commit**

The player presses **⚡ FEED THE VOID**. The item is captured to disk and `fsync`'d before it leaves their inventory. The machine locks. There is no going back.

**III. The Verdict**

Six reel symbols spin — cycling through seven void-themed icons at increasing speed. They slow. Stop, one column at a time. The last column teases a near-miss symbol before the final result drops.

| Outcome | What happens |
|---------|-------------|
| **Consumed** | The Void takes everything. Silence. |
| **Returned** | The machine was unmoved. Items return unchanged. |
| **Doubled** | The Void rewards the bold. ×2. |
| **Tripled** | A surge of impossible power. ×3. |
| **★ JACKPOT ×5** | The Void awakens. ×5 return — broadcast to the entire server. |

---

> 📸 *Placeholder: staging interface with sacrifice placed, FEED THE VOID button active.*

---

> 📸 *Placeholder: cinematic reel GUI mid-spin — symbols cycling, boss bar at tension phase.*

---

## 🗡️ Features

### Gameplay
- **Cinematic reel GUI** — 6 spinning columns, 7 void-themed symbols. Speed phases: fast → medium → slow. Columns stop left-to-right. The final column always delivers a near-miss before locking.
- **Physical world machine** — a registered block in the world. Players must physically visit it. Right-click to open.
- **Explicit commit step** — staging interface with a clear point-of-no-return. Item stays in the player's inventory until they confirm.
- **Jackpot broadcasts** — server-wide chat announcement and full-screen title for ×5 wins.
- **Named outcome profiles** — assign different weight sets to different machines (`default`, `brutal`, `unstable`).
- **Cooldowns and daily limits** — per-player and global cooldowns; daily ritual limits.
- **Discord integration** — optional webhook notifications for jackpots and tripled outcomes.

### Safety
- **Crash-safe WAL checkpoint** — item written to disk and `fsync`'d before leaving the player's inventory. Startup recovery scans incomplete checkpoints and restores lost items automatically.
- **Pre-rolled deterministic outcomes** — the result is decided the moment the animation starts. Disconnecting, reconnecting, or crashing mid-animation cannot change the outcome. There is no second roll.
- **Machine lock** — `AtomicBoolean.compareAndSet` prevents two concurrent rituals on the same machine block.
- **Death handling** — if the player dies during staging, the sacrifice item is injected into death drops. Dying during an active transaction is handled by abort + audit log.
- **Chunk unload protection** — active transactions force-aborted and items returned if the machine's chunk unloads.
- **Machine block protection** — registered blocks survive explosions (entity and block), piston push/pull, and liquid flow. Indestructible through all normal gameplay. Removable only via `/vm admin remove`.

### Platform
- **Bedrock and Geyser compatible** — inventory GUIs and boss bars work identically on Java and Bedrock clients. No Java-specific input habits required.
- **Controller and touch input friendly** — no shift-click dependencies. Item placement uses natural inventory mechanics on all platforms.
- **Persistent machines** — machine registrations survive restarts. Stored to disk on every change.
- **Audit logging** — complete transaction history: player, item, amount, outcome, timestamp, machine location.

---

## 📜 Philosophy

Three principles drove every design decision:

**The item sink.** Server economies inflate over time. VoidMachine creates a voluntary, high-tension drain. Players choose to risk items — they are never forced.

**The emotional moment.** The ritual is slow by design. The ramp, the tension pause, the near-miss before the final column locks — each phase is architecture for a feeling. Win or lose, players remember it.

**The social layer.** Jackpots broadcast to the server. Tripled outcomes announce themselves. Losses are private and quiet. The machine creates mythology — the stories your players tell each other.

> *"I fed 64 netherite ingots to the Void. It gave me 320 back."*
> *"I lost everything. I'm going back."*

These are the moments that define a server's history.

---

## 🔧 Technical Architecture

VoidMachine is built for production survival servers.

- **Java 21** — modern language features throughout.
- **PaperMC 1.21.4+** — Adventure API (MiniMessage, BossBar, Component), Paper-specific event system.
- **Write-ahead log (WAL)** — item checkpoint written and `fsync`'d to disk before capture. Startup recovery scans and restores all incomplete checkpoints after a crash.
- **Pre-rolled outcomes** — the outcome is determined at animation start, before the first reel frame renders. The reel is presentation only. No outcome manipulation is possible through reconnect or crash.
- **Transaction state machine** — `CAPTURED → ANIMATING → DELIVERING → COMPLETED / FAILED`. Each state is crash-recoverable.
- **AtomicBoolean machine lock** — `compareAndSet(false, true)` prevents concurrent rituals. Unlocks on completion, abort, or chunk unload.
- **Animation pipeline** — phased ramp/tension/reveal system with BossBar, `ItemDisplay` entity (Java clients), particles, and cinematic inventory GUI driven by a configurable step scheduler.

### Package layout

```
com.voidmachine
├── animation/          StagingGui, CinematicGui, AnimationPipeline, AnimationWatchdog
│                       ReelSymbol, StagingGuiListener, CinematicGuiListener
├── audit/              AuditLogger
├── checkpoint/         CheckpointStore, PendingDeliveryQueue, StartupRecovery
├── command/            VoidMachineCommand
├── config/             PluginConfig, MessageManager
├── core/               Outcome, OutcomeRoller, Transaction
├── db/                 DatabaseManager, Storage, YamlStorage, SqlStorage
├── gui/                GuiManager (legacy player-GUI path)
├── integration/        DiscordHook
├── interaction/        ItemCaptureService, MachineInteractionListener
│                       MachineBlockListener, PlayerDeathListener
├── machine/            MachineBlock, MachineRegistry, MachineDataStore
├── service/            BlacklistService, CooldownService, ProcessingService, StatsService
├── transaction/        TransactionRegistry
└── util/               Effects, ItemValidator
```

---

## 📦 Installation

**Requirements**
- [PaperMC](https://papermc.io/) (or Paper fork) **1.21.4+**
- Java **21+**
- [Geyser](https://geysermc.org/) *(optional — for Bedrock crossplay)*

**Steps**

1. Drop `VoidMachine-x.x.x.jar` into your `plugins/` folder.
2. Start the server. Default `config.yml` and `messages.yml` are generated.
3. Walk up to the block you want to designate as the machine core and run:
   ```
   /vm admin create <name> [profile]
   ```
4. Right-click that block to open the staging interface.

> The core block material is set in `config.yml` under `machine.core-block`.
> Default is `CRYING_OBSIDIAN`.

---

## 💻 Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/vm admin create <name> [profile]` | `voidmachine.admin` | Register the block you are looking at as a machine |
| `/vm admin remove <name>` | `voidmachine.admin` | Remove a registered machine (block survives; registration deleted) |
| `/vm admin list` | `voidmachine.admin` | List all registered machines with locations and profiles |
| `/vm reload` | `voidmachine.admin` | Reload `config.yml` and `messages.yml` without restart |

> Machines are **indestructible** through normal gameplay. They can only be removed via `/vm admin remove`.
> Admin break permission does not grant block-break access — use the command.

---

## ⚙️ Configuration

### Outcome profiles

Named weight sets let you assign different risk curves to different machines.

```yaml
profiles:
  default:
    DESTROYED:  40
    RETURNED:   30
    DOUBLED:    20
    TRIPLED:    8
    JACKPOT_X5: 2

  brutal:
    DESTROYED:  60
    RETURNED:   25
    DOUBLED:    12
    TRIPLED:    2
    JACKPOT_X5: 1

  unstable:
    DESTROYED:  35
    RETURNED:   20
    DOUBLED:    25
    TRIPLED:    15
    JACKPOT_X5: 5
```

Weights are normalised automatically. You can use any positive numbers.

Assign a profile when creating a machine:
```
/vm admin create VoidAltar brutal
```

### Key settings

| Setting | Default | Description |
|---------|---------|-------------|
| `machine.core-block` | `CRYING_OBSIDIAN` | Block type for machine registration |
| `limits.max-insert-amount` | `64` | Maximum items accepted per ritual |
| `limits.max-return-amount` | `2304` | Return cap (overflow protection) |
| `limits.clamp-on-overflow` | `true` | Clamp oversized returns rather than cancelling |
| `animation.steps` | `30` | Ramp phase step count |
| `world-animation.step-ticks` | `2` | Ticks per animation step |
| `world-animation.tension-lock-ticks` | `20` | Tension pause before reveal (ticks) |
| `world-animation.max-duration-ticks` | `200` | Watchdog timeout — must exceed total animation length |

### Storage

Default storage is YAML. For larger servers, switch to MariaDB/MySQL:

```yaml
storage:
  type: mysql
  mysql:
    host: localhost
    port: 3306
    database: voidmachine
    username: voidmachine
    password: changeme
    use-ssl: false
    pool-size: 8
    table-prefix: vm_
```

---

## 🔒 Safety & Anti-Dupe

VoidMachine is built around one rule: **players must never lose items to a bug.**

| Threat | Mitigation |
|--------|-----------|
| Crash mid-capture | WAL checkpoint written and `fsync`'d before item leaves inventory. Startup recovery restores on next launch. |
| Crash mid-animation | Outcome pre-rolled and stored in `AnimationContext` at animation start — same result guaranteed on recovery. |
| Disconnect mid-animation | `ItemCaptureService.abortTransaction` cancels animation, unlocks machine, returns item. |
| Death during staging | Item injected into `PlayerDeathEvent.getDrops()`. Never silently lost. |
| Death during active transaction | Abort + item return attempt; checkpoint on disk for admin audit if timing is extreme. |
| Concurrent machine access | `AtomicBoolean.compareAndSet` lock — only one player can hold a machine at a time. |
| Chunk unload during transaction | `MachineBlockListener.onChunkUnload` detects and force-aborts affected transactions. |
| Machine block destroyed | Registered blocks immune to player break, explosions, pistons, and liquid flow. |
| Silent stack over-consumption | Clamping happens at START press — excess returned to inventory before WAL is written. |
| Double-roll on reconnect | Outcome rolled once at animation start, stored in context. No re-roll is possible. |
| Shulker boxes / container NBT | Blocked via `BlacklistService`. |
| Stack-size overflow on jackpot | `limits.max-return-amount` caps return; `limits.clamp-on-overflow` controls behaviour. |

**Found a duplication vector?** Email `neeman2009@gmail.com` with `[VoidMachine SECURITY]` in the subject. Please do not open a public issue until a fix is available.

---

## 🌐 Compatibility

| Platform | Status |
|----------|--------|
| Paper 1.21.4+ | ✅ Fully supported |
| Spigot | ❌ Not supported — Paper-specific APIs required |
| Java clients | ✅ Full experience |
| Bedrock via Geyser | ✅ Full experience (inventory GUI, boss bar, particles) |
| Controller input | ✅ No shift-click or key-specific input required |
| Touch / mobile | ✅ Natural tap-to-place inventory interaction |
| Folia | ❌ Not planned |

> **Bedrock note:** `ItemDisplay` entities (the floating sacrifice item shown above the machine during animation) are Java-only. Bedrock clients receive the full ritual experience — boss bar, inventory GUI, particles, chat reveal — without the floating entity.

---

## 🗺️ Roadmap

### Near-term
- [ ] **Multiblock structure validation** — require a crying obsidian cross frame around the core block; machine refuses to activate without it. Config-optional (`machine.require-structure`).
- [ ] **Sound polish** — custom sound sequences per phase: ramp escalation, tension silence, per-outcome reveal sounds.
- [ ] **Reward-rate balancing** — per-item-type weight overrides; economy telemetry to tune rates over time.

### Medium-term
- [ ] **Jackpot spectator mode** — nearby players pulled into a shared cinematic view on ×5 reveal.
- [ ] **Lore and events** — machine attunement states; server-wide void events triggered by jackpot streaks; machine "history" displayed on signage.
- [ ] **Analytics dashboard** — per-machine and server-wide sacrifice statistics with economy health indicators.

### Long-term
- [ ] **Mythic moments** — rare machine behaviours triggered by unusual item inputs or consecutive sacrifices.
- [ ] **PlaceholderAPI integration** — expose machine stats and player sacrifice history to other plugins.
- [ ] **Spectator recording** — replay last N jackpots for players who missed them.

---

## 🏗️ Architecture History

VoidMachine began as a simple GUI gambling plugin. What exists in v0.1.0-alpha is a full architectural rewrite.

**Phase 1 — Legacy GUI**
Basic inventory GUI, immediate item capture on open, no physical machine, no commit step. Results delivered through a chat message.

**Phase 2 — Physical machine**
World-registered blocks. Admin commands. Machine registry with persistent disk storage. Block protection.

**Phase 3 — Transaction safety**
Write-ahead log. Startup recovery. Checkpoint `fsync`. Machine lock with `AtomicBoolean`. Death handling. Chunk unload abort. Full `TransactionRegistry`.

**Phase 4 — Cinematic ritual**
`AnimationPipeline` with ramp → tension → reveal phase scheduling. Boss bar with colour and name changes. `ItemDisplay` entity (Java). Particle system. `CinematicGui` inventory animation. `AnimationWatchdog`.

**Phase 5 — Staging commit step**
`StagingGui` — 5-row pre-commit interface with explicit START button. `committed` flag hard-locks GUI after press. `ItemCaptureService.captureFromGui()`. Crash-safe `onConsumed` callback at point-of-no-return.

**Phase 6 — Playtest hardening**
Animation timing reduced to ~5 s. Player death handling (staging and active transaction). Machine destruction blocked via all event paths. `AnimationWatchdog` abort timeout. UX lore added to staging buttons.

**Phase 7 — Reel animation + UX overhaul**
Outcome pre-rolled at animation start (before first GUI frame). `ReelSymbol` enum with 7 void-themed symbols and cached `ItemStack`s. `CinematicGui` rewritten: 6 spinning reel columns, speed phases (fast/medium/slow), staggered column stops, per-outcome near-miss symbol design. Bedrock/controller-safe staging interaction model: pane protection only, natural inventory mechanics pre-commit, clamping moved to START press.

---

## 🔨 Building

```bash
./gradlew shadowJar
```

Output: `build/libs/VoidMachine-<version>.jar`

No local server or database required to build. The plugin makes no calls on load that require a running Bukkit instance.

---

## 🤝 Contributing

PRs are welcome. Before opening one:

1. Open an issue describing the change.
2. Fork → branch → PR against `main`.
3. Keep PRs focused — one behavioural change per PR.
4. Run a smoke test against a real PaperMC server. Unit tests are limited by the Bukkit main-thread model.
5. Match existing style: 4-space indent, full Javadoc on public classes and non-obvious methods.

---

## 🐛 Reporting Issues

- **Bugs / feature requests:** [github.com/MordechaiNeeman/VoidMachine/issues](https://github.com/MordechaiNeeman/VoidMachine/issues)
- **Security / dupe exploits:** email `neeman2009@gmail.com` with `[VoidMachine SECURITY]` in the subject. Do not open a public issue until a fix ships.

Include in any report: server software + build (`/version`), Java version, VoidMachine version, reproduction steps, and server log excerpt around the incident.

---

## 📄 Credits

| | |
|-|-|
| **Author** | Mordechai Neeman · [neeman2009@gmail.com](mailto:neeman2009@gmail.com) · [@MordechaiNeeman](https://github.com/MordechaiNeeman) |
| **Repository** | [github.com/MordechaiNeeman/VoidMachine](https://github.com/MordechaiNeeman/VoidMachine) |
| **Issues** | [github.com/MordechaiNeeman/VoidMachine/issues](https://github.com/MordechaiNeeman/VoidMachine/issues) |
| **Discussions** | [github.com/MordechaiNeeman/VoidMachine/discussions](https://github.com/MordechaiNeeman/VoidMachine/discussions) |

Built on:
- [PaperMC](https://papermc.io/) — server platform
- [Adventure / MiniMessage](https://docs.advntr.dev/) — text and component API
- [Geyser](https://geysermc.org/) — Bedrock crossplay bridge
- [HikariCP](https://github.com/brettwooldridge/HikariCP) — connection pooling (shaded)

---

## License

MIT © 2026 Mordechai Neeman — see [LICENSE](LICENSE) for the full text.

---

<div align="center">

*The Void does not negotiate. It only decides.*

</div>
