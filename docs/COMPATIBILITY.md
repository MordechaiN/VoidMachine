# Compatibility

## Server

| | Status |
|---|---|
| Paper 26.2 | Supported (`api-version: '26.2'`). Compiled against the Paper 26.2 API |
| Java 25 | Required |
| Forks of Paper (Purpur, Pufferfish…) | Expected to work; not tested. A presentation effect a fork does not support is skipped and reported once |
| Folia | Not supported (`folia-supported: false`): VoidMachine relies on the single main thread for its transaction ordering |
| Spigot / CraftBukkit | Not supported (Paper APIs: persistent data on players, `ItemStack#serializeAsBytes`, Adventure, `Inventory#getHolder(boolean)`) |
| Older Minecraft versions | Not supported by 2.x. Use 1.x for 1.21.x servers |

## Bedrock (Geyser / Floodgate)

- Screens are standard chest inventories; Geyser shows them as Bedrock inventories. Every click is
  handled by VoidMachine, so behaviour is identical to Java.
- Boss bars, titles, action bars, chat, sounds and most particles reach Bedrock clients.
- The floating item display (Java display entities) may not be shown by Geyser; nothing important
  depends on it.
- Hebrew is pre-ordered for Bedrock clients (`language.bedrock-rtl-fix`).
- Bedrock detection: Floodgate API if installed, else Geyser API, else the Floodgate UUID convention
  (upper 64 bits zero). Neither plugin is required. Detection is done once per player and cached.

**Needs verification on real clients:** the visual result of Hebrew reordering on Bedrock (tested
here as text transformation only) and how particles map on Bedrock for your Geyser version.

## Other plugins

| Plugin type | Notes |
|---|---|
| Protection (WorldGuard, GriefPrevention…) | Machines are registered explicitly and protected by VoidMachine; region flags do not block using a machine. Cancel `RitualStartEvent` to restrict use per region |
| Inventory sync across servers | Must sync the player's persistent data container with the inventory, or the guarantee does not hold. See [Recovery](RECOVERY.md#limits-of-the-guarantee-read-this) |
| Inventory backup/rollback, `/invsee`-style editors | Editing a player's inventory around a ritual can re-add paid items. Admin responsibility |
| Plugins that mark special items | Add their persistent-data key to `offering.blocked-pdc-keys` |
| Custom item plugins | Items are stored with all their components; a custom item comes back identical (tested with custom names and custom persistent data) |
| Economy (Vault) | Not used, not needed |
| DiscordSRV | Not integrated in 2.0 (V1 had an optional forwarder, off by default). Forward `RitualRevealEvent` / `RitualJackpotEvent` from a small bridge plugin |
| PlaceholderAPI | Not integrated (V1 declared it but never used it) |

VoidMachine registers no global listeners that change other plugins' behaviour: its click handler only
acts on its own screens, its protection handlers only on registered machine blocks, and its player
listeners only observe (they never cancel joins, quits, moves or teleports — the tether only pulls the
offering player back inside a small radius during their own ritual).

## Resource packs

Sounds in `rituals.yml` can use any namespace; non-`minecraft` sounds are accepted (with a warning)
for resource packs. Vanilla sounds and particles are validated against the server registry.
