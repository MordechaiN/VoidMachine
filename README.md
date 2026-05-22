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
> It is a crash-safe item sink with cinematic tension — built to create *permanent moments* in your server's mythology.

---

## 🌑 What is it?

A **physical ritual machine** placed in your Minecraft world. Players walk up to it, place an item, and press a button.

Six void symbols spin. They slow down. Stop column by column. The last column teases a near-miss — then the Void reveals its verdict.

Win or lose, nobody forgets their first time.

---

## ⚡ What your players experience

**Right-click the machine.** A cinematic staging screen opens.

They place their offering — anything. 64 diamonds. A stack of netherite ingots. The machine doesn't care. They can take it back until the moment they confirm.

**They press ⚡ FEED THE VOID.**

The ritual begins. Six reel columns start spinning through void-themed symbols — fast at first, then slower, then agonising. Columns lock in one at a time from left to right. The last column shows a near-miss symbol for a full second before the truth drops.

| Outcome | What happens |
|---------|-------------|
| **Consumed** | The Void takes everything. Silence. |
| **Returned** | The machine was unmoved. Items come back. |
| **Doubled** | The Void rewards the bold. ×2. |
| **Tripled** | A surge of impossible power. ×3. Server announcement fires. |
| **★ JACKPOT ×5** | The Void awakens. ×5 — broadcast to every online player. |

> 📸 *Screenshot: staging interface with sacrifice placed, FEED THE VOID button active.*

> 📸 *Screenshot: cinematic reel GUI mid-spin — symbols cycling, boss bar at tension phase.*

---

## 🗡️ Why your server needs this

**Economy sink that players actually want to use.**
Every successful ritual pulls items out of circulation. No forced drain, no admin interference — players volunteer their stacks. The machine is a natural, self-sustaining economic drain.

**The stories that define your server.**
*"I fed 64 netherite ingots to the Void. I got 320 back."*
*"Lost everything. Still going back."*
Jackpots broadcast to the whole server. Tripled outcomes announce themselves. Losses are private. The machine creates moments your community will talk about for months.

**High retention for zero work.**
No economy plugin required. No coins, no currencies, no complex setup. Drop the jar. Register a block. The server drama runs itself.

**Bedrock players included.**
Full ritual experience — boss bar, inventory GUI, particles — on Java and Bedrock alike. No shift-click, no keyboard shortcuts, no platform divide.

---

## ✨ Features

- 🎰 **Cinematic reel GUI** — 6 spinning columns, 7 void symbols, progressive slowdown, staggered column stops with per-outcome near-miss design
- 🪨 **Physical world machine** — a real block your players visit. Right-click to open.
- 🔒 **Crash-safe WAL checkpoint** — item written to disk before it leaves the player. Server crash mid-ritual? Items are safe. Always.
- 🎯 **Pre-rolled outcomes** — the result is locked the moment the ritual starts. No reconnect exploit, no timing manipulation.
- 📢 **Server-wide jackpot broadcasts** — full-screen title + chat message when someone wins ×5
- ⚙️ **Named outcome profiles** — different risk curves per machine (`default`, `brutal`, `unstable`)
- ⏱️ **Cooldowns + daily limits** — per-player and global
- 🛡️ **Explosion, piston, and liquid protection** — machine blocks are indestructible through gameplay
- 💀 **Death-safe** — dying during staging injects the item into death drops. No silent loss.
- 🌐 **Geyser / Bedrock compatible** — no Java-specific input required
- 🎮 **Controller and touch friendly** — tap-to-place. No shift-click habits needed.

---

## 📦 Installation

**Requirements:** [PaperMC](https://papermc.io/) 1.21.4+, Java 21+

1. Drop `VoidMachine-x.x.x.jar` into `plugins/`
2. Start the server
3. Look at the block you want to use as the machine, then run:
   ```
   /vm admin create <name> [profile]
   ```
4. Right-click that block to open the ritual

That's it. Config and messages generate automatically on first start.

> Default machine block: `CRYING_OBSIDIAN` — configurable in `config.yml`.

---

## 💻 Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/vm admin create <name> [profile]` | `voidmachine.admin` | Register the block you are looking at as a machine |
| `/vm admin remove <name>` | `voidmachine.admin` | Remove machine and restore the block to AIR |
| `/vm admin list` | `voidmachine.admin` | List all registered machines with locations and profiles |
| `/vm reload` | `voidmachine.admin` | Reload `config.yml` and `messages.yml` without restart |

> Machines are **indestructible** through normal gameplay. Remove only via `/vm admin remove`.

---

## ⚙️ Configuration

### Outcome profiles

Assign different risk curves to different machines:

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

Weights are normalized automatically. Use any positive numbers.

```
/vm admin create VoidAltar brutal
```

### Key settings

| Setting | Default | Description |
|---------|---------|-------------|
| `machine.core-block` | `CRYING_OBSIDIAN` | Block type for machine registration |
| `limits.max-insert-amount` | `64` | Maximum items per ritual |
| `limits.max-return-amount` | `2304` | Return cap (overflow protection) |
| `limits.clamp-on-overflow` | `true` | Clamp oversized returns rather than cancelling |
| `animation.steps` | `30` | Ramp phase step count |
| `world-animation.step-ticks` | `2` | Ticks per animation step |
| `world-animation.tension-lock-ticks` | `20` | Tension pause before reveal |
| `world-animation.max-duration-ticks` | `200` | Watchdog timeout |

### Storage

Default is YAML. For larger servers:

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

## 🌐 Compatibility

| Platform | Status |
|----------|--------|
| Paper 1.21.4+ | ✅ Fully supported |
| Spigot | ❌ Not supported — Paper APIs required |
| Java clients | ✅ Full experience |
| Bedrock via Geyser | ✅ Full experience |
| Controller input | ✅ No shift-click required |
| Touch / mobile | ✅ Natural tap-to-place |
| Folia | ❌ Not planned |

> **Bedrock note:** `ItemDisplay` entities (floating item above the machine during animation) are Java-only. Bedrock clients get the full ritual — boss bar, GUI, particles, reveal — without it.

---

## 🔒 Safety & Anti-Dupe

Players must never lose items to a bug.

| Threat | Mitigation |
|--------|-----------|
| Crash mid-capture | WAL checkpoint written and `fsync`'d before item leaves inventory. Startup recovery restores. |
| Crash mid-animation | Outcome pre-rolled and stored at animation start — same result on recovery. |
| Disconnect mid-animation | Abort transaction, unlock machine, return item. |
| Death during staging | Item injected into death drops. Never silently lost. |
| Death during active transaction | Abort + return attempt; checkpoint on disk for admin audit. |
| Concurrent machine access | `AtomicBoolean.compareAndSet` — one ritual per machine at a time. |
| Chunk unload during transaction | Force-abort detects unload, returns item. |
| Machine block destroyed | Immune to player break, explosions, pistons, liquid flow. |
| Stack over-consumption | Clamping at START press — excess returned before WAL is written. |
| Double-roll on reconnect | Outcome rolled once at animation start. No re-roll possible. |
| Shulker boxes / container NBT | Blocked via `BlacklistService`. |
| Jackpot overflow | `limits.max-return-amount` caps return. |

**Found a dupe vector?** Email `neeman2009@gmail.com` with `[VoidMachine SECURITY]` in the subject. Do not open a public issue until a fix ships.

---

## 🔧 Technical Architecture

Built for production survival servers. Java 21, PaperMC 1.21.4+, Adventure API.

**Write-ahead log (WAL)** — item checkpoint written and `fsync`'d to disk before capture. Startup recovery scans and restores all incomplete checkpoints after a crash.

**Pre-rolled outcomes** — the result is determined at animation start, before the first reel frame renders. The reel is presentation only. No outcome manipulation is possible through reconnect or crash.

**Transaction state machine** — `CAPTURED → ANIMATING → DELIVERING → COMPLETED / FAILED`. Each state crash-recoverable.

**AtomicBoolean machine lock** — `compareAndSet(false, true)` prevents concurrent rituals. Unlocks on completion, abort, or chunk unload.

**Animation pipeline** — phased ramp / tension / reveal system with BossBar, `ItemDisplay` entity (Java), particles, and cinematic inventory GUI driven by a configurable step scheduler.

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

## 🏗️ Architecture History

VoidMachine began as a simple GUI gambling plugin. What exists in v0.1.0-alpha is a full architectural rewrite.

**Phase 1 — Legacy GUI**
Basic inventory GUI, immediate item capture on open, no physical machine, no commit step.

**Phase 2 — Physical machine**
World-registered blocks. Admin commands. Machine registry with persistent disk storage. Block protection.

**Phase 3 — Transaction safety**
Write-ahead log. Startup recovery. Checkpoint `fsync`. Machine lock. Death handling. Chunk unload abort.

**Phase 4 — Cinematic ritual**
`AnimationPipeline` with ramp → tension → reveal phases. Boss bar. `ItemDisplay` entity. Particles. `CinematicGui`. `AnimationWatchdog`.

**Phase 5 — Staging commit step**
`StagingGui` — 5-row pre-commit interface with explicit START button. `committed` flag hard-locks GUI after press. `captureFromGui()` with crash-safe `onConsumed` callback.

**Phase 6 — Playtest hardening**
Animation timing reduced to ~5 s. Player death handling. Machine destruction blocked on all event paths. Watchdog abort timeout.

**Phase 7 — Reel animation + UX overhaul**
Outcome pre-rolled at animation start. `ReelSymbol` enum, 7 void symbols, cached `ItemStack`s. `CinematicGui` rewritten: 6 spinning columns, speed phases, staggered stops, per-outcome near-miss design. Bedrock/controller-safe staging: natural inventory pre-commit, clamping at START press.

---

## 🗺️ Roadmap

### Near-term
- [ ] **Multiblock structure validation** — crying obsidian frame requirement (config-optional)
- [ ] **Sound polish** — custom sounds per phase and per outcome
- [ ] **Reward-rate balancing** — per-item-type weight overrides

### Medium-term
- [ ] **Jackpot spectator mode** — nearby players pulled into shared cinematic view on ×5
- [ ] **Machine attunement** — server-wide void events triggered by jackpot streaks
- [ ] **Analytics dashboard** — per-machine sacrifice statistics

### Long-term
- [ ] **Mythic moments** — rare behaviours triggered by unusual inputs or consecutive sacrifices
- [ ] **PlaceholderAPI integration** — expose stats to other plugins
- [ ] **Jackpot replay** — replay last N jackpots for players who missed them

---

## 🔨 Building

```bash
./gradlew shadowJar
```

Output: `build/libs/VoidMachine-<version>.jar`

No local server or database required to build.

---

## 🤝 Contributing

1. Open an issue describing the change
2. Fork → branch → PR against `main`
3. One behaviour change per PR
4. Smoke-test against a real PaperMC server
5. 4-space indent, Javadoc on public classes and non-obvious methods

---

## 🐛 Reporting Issues

- **Bugs / feature requests:** [github.com/MordechaiNeeman/VoidMachine/issues](https://github.com/MordechaiNeeman/VoidMachine/issues)
- **Security / dupes:** `neeman2009@gmail.com` — subject `[VoidMachine SECURITY]`. Do not open public issue until fix ships.

Include: server software + build (`/version`), Java version, VoidMachine version, steps to reproduce, log excerpt.

---

## 📄 Credits

| | |
|-|-|
| **Author** | Mordechai Neeman · [neeman2009@gmail.com](mailto:neeman2009@gmail.com) · [@MordechaiNeeman](https://github.com/MordechaiNeeman) |
| **Repository** | [github.com/MordechaiNeeman/VoidMachine](https://github.com/MordechaiNeeman/VoidMachine) |
| **Issues** | [github.com/MordechaiNeeman/VoidMachine/issues](https://github.com/MordechaiNeeman/VoidMachine/issues) |

Built on: [PaperMC](https://papermc.io/) · [Adventure / MiniMessage](https://docs.advntr.dev/) · [Geyser](https://geysermc.org/) · [HikariCP](https://github.com/brettwooldridge/HikariCP)

---

## License

MIT © 2026 Mordechai Neeman — see [LICENSE](LICENSE) for details.

---

<div align="center">

*The Void does not negotiate. It only decides.*

</div>
