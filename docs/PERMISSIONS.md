# Permissions

| Permission | Default | Grants |
|---|---|---|
| `voidmachine.use` | everyone | Offer items to machines |
| `voidmachine.stats` | everyone | `/vm stats`, `/vm stats server`, `/vm top` |
| `voidmachine.claim` | everyone | `/vm claim` |
| `voidmachine.notify` | everyone | Receive server-wide announcements of big wins and jackpots |
| `voidmachine.bypass.cooldown` | op | Ignore player and machine cooldowns |
| `voidmachine.admin` | op | All admin commands; includes `voidmachine.admin.inspect` and `voidmachine.admin.alerts` |
| `voidmachine.admin.inspect` | op | Read-only admin commands: list, status, rituals, pending, inspect, diagnostics, health, odds |
| `voidmachine.admin.alerts` | op | Recovery and safety alerts in chat (always also in the server log) |
| `voidmachine.*` | op | Everything above |

Profiles can require an extra permission (`profiles.<id>.permission` in `config.yml`), for example
`voidmachine.profile.vip` for a machine that only some players may use.

Giving someone `voidmachine.admin.inspect` without `voidmachine.admin` is a good fit for moderators:
they can investigate a player's report (`pending`, `inspect`) without being able to change anything.
