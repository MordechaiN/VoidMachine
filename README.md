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

The machine locks. A dark ritual chamber fills the screen. The boss bar crawls forward, then freezes at the edge of revelation. Three seconds of silence — then the Void reveals its verdict with a flash of coloured light.

Win or lose, nobody forgets their first time.

Even when idle, the machine breathes — portal smoke drifts from the block, soul-fire flickers in the dark, and an occasional amethyst chime carries through the room. Something dangerous is sleeping here.

---

## ⚡ What your players experience

**Right-click the machine.** A cinematic staging screen opens.

They place their offering — anything. 64 diamonds. A stack of netherite ingots. The machine doesn't care. They can take it back until the moment they confirm.

**They press ⚡ FEED THE VOID.**

The machine locks. A ritual chamber opens — a dark three-slot screen, nothing to distract. The boss bar begins to fill. Then it freezes. The screen goes silent. Three seconds of dread. Then the chamber flashes its verdict colour and the result drops — bold, unambiguous, impossible to miss on any platform.

Nearby players? They heard the drone through the wall. They're already watching.

| Outcome | What happens |
|---------|-------------|
| **Consumed** | The Void takes everything. Silence. Chamber turns red. |
| **Returned** | The machine was unmoved. Items come back. Chamber turns grey. |
| **Doubled** | The Void rewards the bold. ×2. Chamber turns green. |
| **Tripled** | A surge of impossible power. ×3. Chamber turns gold. Server-wide announcement. |
| **★ JACKPOT ×5** | The Void awakens. ×5. Triple lightning. Sound heard 64 blocks away. Full-server broadcast. |

> 📸 *Screenshot: staging interface — one open slot, glowing START button active.*

> 📸 *Screenshot: ritual chamber at reveal — gold border, TRIPLED verdict, boss bar held.*

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

- ⬛ **Ritual chamber GUI** — dark 3-slot screen, minimal panes, bold colour-coded verdict. Works on Java, Bedrock, controller, touch, and mobile without any platform-specific input
- 🌑 **Idle ambient effects** — portal smoke, soul-fire flicker, and amethyst chime pulse from all idle machines every 30 s. The machine feels alive even when no ritual is running
- 👁️ **Social visibility** — nearby players hear the ritual build, hear the tension hum, and feel the jackpot triple-lightning from 64 blocks away
- 🪨 **Physical world machine** — a real block your players visit. Right-click to open.
- 🔒 **Crash-safe WAL checkpoint** — item written to disk before it leaves the player. Server crash mid-ritual? Items are safe. Always.
- 🎯 **Pre-rolled outcomes** — the result is locked the moment the ritual starts, before the first animation frame. No reconnect exploit, no timing manipulation.
- 📢 **Server-wide jackpot broadcasts** — triple lightning, world-space sound at 64 blocks, full-screen title + chat when someone wins ×5
- ⚙️ **Named outcome profiles** — different risk curves per machine (`default`, `brutal`, `unstable`)
- ⏱️ **Cooldowns + daily limits** — per-player and global
- 🛡️ **Explosion, piston, and liquid protection** — machine blocks are indestructible through gameplay
- 💀 **Death-safe** — dying during staging injects the item into death drops. No silent loss.
- 🎮 **Bedrock / controller / touch first** — designed for lowest-precision input. No shift-click, no keyboard shortcuts, no Java-specific habits required.

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

> Default machine block: `RESPAWN_ANCHOR` — configurable via `machine.core-material` in `config.yml`.

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
| `machine.core-material` | `RESPAWN_ANCHOR` | Block type for machine registration |
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

**Pre-rolled outcomes** — the result is determined at animation start, stored in `AnimationContext`, and never re-rolled. The ritual GUI is presentation only. No outcome manipulation is possible through disconnect, reconnect, or crash.

**Transaction state machine** — `CAPTURED → ANIMATING → DELIVERING → COMPLETED / FAILED`. Each state crash-recoverable.

**AtomicBoolean machine lock** — `compareAndSet(false, true)` prevents concurrent rituals. Unlocks on completion, abort, or chunk unload.

**Animation pipeline** — phased ramp / tension / reveal system with BossBar, `ItemDisplay` entity (Java), particles, and cinematic inventory GUI driven by a configurable step scheduler.

### Package layout

```
com.voidmachine
├── animation/          StagingGui, CinematicGui, AnimationPipeline, AnimationWatchdog
│                       AmbientEffectScheduler, StagingGuiListener, CinematicGuiListener
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

**Phase 7 — Commit-step UX + pre-rolled outcomes**
Outcome pre-rolled and stored in `AnimationContext` before the first animation frame. Staging GUI: natural inventory pre-commit, clamping at START press, cursor resolution on confirm. `StagingGuiListener` simplified — pane protection only, no shift-click routing.

**Phase 8 — Atmosphere + GUI redesign**
`AmbientEffectScheduler`: idle portal smoke, soul-fire flicker, ambient hum, 30-second attract flash — all idle machines feel alive. Social visibility: world-space sounds during ramp, tension, and reveal so nearby spectators hear the ritual. Jackpot enhanced to triple lightning + 64-block world sound. `StagingGui` rebuilt as a clean 3-row layout (5-row removed) — single open input slot, glowing START button, column-aligned header → input → confirm. `CinematicGui` rewritten as a ritual chamber: all reel machinery removed, single bold status pane with colour-coded reveal. Designed for Bedrock, controller, touch, and mobile first.

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
