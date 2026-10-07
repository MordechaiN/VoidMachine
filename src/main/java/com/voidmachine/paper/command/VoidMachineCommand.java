package com.voidmachine.paper.command;

import com.voidmachine.core.config.ConfigProblem;
import com.voidmachine.core.config.Settings;
import com.voidmachine.core.health.HealthMonitor;
import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.journal.RecordKind;
import com.voidmachine.core.machine.MachineRecord;
import com.voidmachine.core.outcome.OutcomeDefinition;
import com.voidmachine.core.outcome.OutcomeTable;
import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.stats.StatsBook;
import com.voidmachine.core.timeline.FakeoutPlan;
import com.voidmachine.core.util.Ids;
import com.voidmachine.paper.VoidMachineRuntime;
import com.voidmachine.paper.audit.AuditLog;
import com.voidmachine.paper.config.ConfigService;
import com.voidmachine.paper.diag.Diagnostics;
import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.ritual.ActiveRitual;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /vm} — player commands use the player's language; admin commands answer in plain technical
 * English. Every admin subcommand checks its permission; destructive ones need {@code /vm admin confirm}.
 */
public final class VoidMachineCommand implements TabExecutor {

    private static final String ADMIN = "voidmachine.admin";
    private static final String INSPECT = "voidmachine.admin.inspect";

    private static final List<String> ADMIN_SUBS = List.of("create", "remove", "list", "status", "enable", "disable", "profile",
            "tp", "rituals", "pending", "inspect", "resolve", "refund", "release", "reload", "diagnostics", "health",
            "preview", "odds", "voidevent", "confirm");
    private static final List<String> READ_ONLY = List.of("list", "status", "rituals", "pending", "inspect", "diagnostics", "health", "odds");

    private final VoidMachineRuntime rt;

    public VoidMachineCommand(VoidMachineRuntime rt) {
        this.rt = rt;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "stats" -> stats(sender, args);
            case "top" -> top(sender, args);
            case "claim" -> claim(sender);
            case "admin" -> admin(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> help(sender);
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // Player commands
    // ------------------------------------------------------------------------------------------

    private void help(CommandSender sender) {
        if (sender instanceof Player p) {
            for (Component line : rt.messages().renderList(rt.messages().variant(p), "help.player")) p.sendMessage(line);
        }
        if (sender.hasPermission(ADMIN) || sender.hasPermission(INSPECT)) {
            info(sender, "Admin: /vm admin <" + String.join("|", ADMIN_SUBS) + ">");
        }
    }

    private void stats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("voidmachine.stats")) {
            deny(sender);
            return;
        }
        StatsBook book = rt.stats();
        if (args.length >= 2 && args[1].equalsIgnoreCase("server")) {
            StatsBook.Global g = book.global();
            if (sender instanceof Player p) {
                rt.messages().send(p, "stats.server",
                        Placeholder.unparsed("rituals", fmt(g.rituals())),
                        Placeholder.unparsed("offered", fmt(g.itemsOffered())),
                        Placeholder.unparsed("returned", fmt(g.itemsReturned())),
                        Placeholder.unparsed("jackpots", fmt(g.tiers().getOrDefault("JACKPOT", 0L))),
                        Placeholder.unparsed("fakeouts", fmt(g.fakeouts())),
                        Placeholder.unparsed("crowd", String.format(Locale.ROOT, "%.1f", g.averageCrowd())),
                        Placeholder.unparsed("duration", String.format(Locale.ROOT, "%.1f", g.averageDurationSeconds())),
                        Placeholder.unparsed("biggest", g.biggestJackpot().or(g::biggestWin)
                                .map(f -> f.playerName() + " — " + f.reward() + " " + f.itemKey()).orElse("—")),
                        Placeholder.unparsed("machine", g.busiestMachine().orElse("—")),
                        Placeholder.unparsed("hour", g.rituals() == 0 ? "—" : String.format(Locale.ROOT, "%02d:00", g.busiestHour())));
            } else {
                info(sender, "rituals=" + g.rituals() + " offered=" + g.itemsOffered() + " returned=" + g.itemsReturned()
                        + " outcomes=" + g.outcomes() + " fakeouts=" + g.fakeouts() + " recoveries=" + g.recoveries());
            }
            return;
        }
        if (!(sender instanceof Player p)) {
            info(sender, "Usage from console: /vm stats server");
            return;
        }
        Optional<StatsBook.PlayerStats> own = book.player(p.getUniqueId());
        if (own.isEmpty()) {
            rt.messages().send(p, "stats.none");
            return;
        }
        StatsBook.PlayerStats ps = own.get();
        rt.messages().send(p, "stats.player",
                Placeholder.unparsed("rituals", fmt(ps.rituals())),
                Placeholder.unparsed("offered", fmt(ps.itemsOffered())),
                Placeholder.unparsed("returned", fmt(ps.itemsReturned())),
                Placeholder.unparsed("wins", fmt(ps.wins())),
                Placeholder.unparsed("jackpots", fmt(ps.jackpots())),
                Placeholder.unparsed("biggest", ps.biggestResult().map(f -> f.reward() + " " + f.itemKey()).orElse("—")),
                Placeholder.unparsed("machine", ps.favoriteMachine().orElse("—")));
    }

    private void top(CommandSender sender, String[] args) {
        if (!sender.hasPermission("voidmachine.stats")) {
            deny(sender);
            return;
        }
        String metric = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "rituals";
        Comparator<StatsBook.PlayerStats> cmp = switch (metric) {
            case "jackpots" -> Comparator.comparingLong(StatsBook.PlayerStats::jackpots);
            case "returned" -> Comparator.comparingLong(StatsBook.PlayerStats::itemsReturned);
            default -> Comparator.comparingLong(StatsBook.PlayerStats::rituals);
        };
        var top = rt.stats().top(cmp, 10);
        if (sender instanceof Player p) {
            rt.messages().send(p, "top.header", Placeholder.unparsed("metric", metric));
            int rank = 1;
            for (var e : top) {
                long value = switch (metric) {
                    case "jackpots" -> e.getValue().jackpots();
                    case "returned" -> e.getValue().itemsReturned();
                    default -> e.getValue().rituals();
                };
                rt.messages().send(p, "top.entry", Placeholder.unparsed("rank", Integer.toString(rank++)),
                        Placeholder.unparsed("player", e.getValue().name()), Placeholder.unparsed("value", fmt(value)));
            }
            if (top.isEmpty()) rt.messages().send(p, "stats.none");
        } else {
            for (var e : top) info(sender, e.getValue().name() + " " + metric);
        }
    }

    private void claim(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            info(sender, "Only players can claim.");
            return;
        }
        if (!p.hasPermission("voidmachine.claim")) {
            deny(sender);
            return;
        }
        rt.custody().payOwed(p, true);
    }

    // ------------------------------------------------------------------------------------------
    // Admin
    // ------------------------------------------------------------------------------------------

    private void admin(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        boolean readOnly = READ_ONLY.contains(sub);
        if (!(sender.hasPermission(ADMIN) || (readOnly && sender.hasPermission(INSPECT)))) {
            deny(sender);
            return;
        }
        switch (sub) {
            case "create" -> create(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            case "status" -> status(sender, args);
            case "enable", "disable" -> enable(sender, args, sub.equals("enable"));
            case "profile" -> profile(sender, args);
            case "tp" -> tp(sender, args);
            case "rituals" -> rituals(sender);
            case "pending" -> pending(sender, args);
            case "inspect" -> inspect(sender, args);
            case "resolve" -> resolve(sender, args);
            case "refund" -> refund(sender, args);
            case "release" -> release(sender, args);
            case "reload" -> reload(sender);
            case "diagnostics" -> Diagnostics.report(rt).forEach(l -> info(sender, l));
            case "health" -> health(sender, args);
            case "preview" -> preview(sender, args);
            case "odds" -> odds(sender, args);
            case "voidevent" -> voidEvent(sender, args);
            case "confirm" -> confirm(sender, args);
            default -> {
                info(sender, "/vm admin create <id> [profile] [display name]  — turn the block you look at into a machine");
                info(sender, "/vm admin remove <id> — remove a machine (confirmation)");
                info(sender, "/vm admin list | status <id> | enable <id> | disable <id> | profile <id> <profile> | tp <id>");
                info(sender, "/vm admin rituals | pending [player] | inspect <ritual> | resolve <ritual>");
                info(sender, "/vm admin refund <ritual> | release <ritual>  (confirmation)");
                info(sender, "/vm admin reload | diagnostics | health [recheck] | odds <profile>");
                info(sender, "/vm admin preview <outcome> [jackpot-variant|false-loss|escalation] | voidevent <id>");
            }
        }
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            error(sender, "Run this in-game while looking at the block that should become the machine.");
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /vm admin create <id> [profile] [display name]");
            return;
        }
        Settings s = rt.settings();
        if (s == null) {
            error(sender, "The configuration is invalid; fix it first (/vm admin health).");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!Ids.isValid(id)) {
            error(sender, "Invalid id '" + args[1] + "': " + Ids.RULE);
            return;
        }
        String profile = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "default";
        if (!s.profiles().containsKey(profile)) {
            error(sender, "Unknown profile '" + profile + "'. Profiles: " + s.profileIds());
            return;
        }
        if (rt.machines().byId(id) != null) {
            error(sender, "A machine with id '" + id + "' already exists.");
            return;
        }
        if (rt.machines().size() >= s.machines().maxMachines()) {
            error(sender, "machines.max-machines (" + s.machines().maxMachines() + ") reached.");
            return;
        }
        Block target = p.getTargetBlockExact(6);
        if (target == null || target.getType().isAir()) {
            error(sender, "Look at a block within 6 blocks.");
            return;
        }
        if (target.getState() instanceof org.bukkit.block.Container) {
            error(sender, "Refusing to replace a container (its contents would be lost).");
            return;
        }
        if (rt.machines().at(target) != null) {
            error(sender, "That block is already a machine.");
            return;
        }
        String display = args.length >= 4 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : id;
        MachineRecord record = new MachineRecord(id, display, target.getWorld().getName(), target.getX(), target.getY(), target.getZ(),
                profile, true, System.currentTimeMillis(), p.getName());
        Machine machine = new Machine(record);
        if (!rt.machines().add(machine)) {
            error(sender, "Could not register the machine (duplicate id or location).");
            return;
        }
        try {
            rt.saveMachines();
        } catch (IOException e) {
            rt.machines().remove(id);
            error(sender, "machines.yml could not be written (" + e.getMessage() + "); nothing was changed.");
            return;
        }
        Material core = Material.matchMaterial(s.machines().coreBlock());
        if (core != null && target.getType() != core) target.setType(core);
        rt.audit().log(AuditLog.Event.MACHINE_CREATED, Map.of("machine", id, "by", p.getName(), "location", record.locationKey(),
                "profile", profile));
        ok(sender, "Machine '" + id + "' created at " + record.locationKey() + " (profile " + profile + ").");
    }

    private void remove(CommandSender sender, String[] args) {
        Machine m = machineArg(sender, args);
        if (m == null) return;
        boolean force = Arrays.asList(args).contains("--force");
        if (m.isBusy() && !force) {
            error(sender, "A ritual is running on '" + m.id() + "'. Wait, or add --force to resolve it immediately (the player still receives their verdict).");
            return;
        }
        boolean clearBlock = Arrays.asList(args).contains("--clear-block");
        String code = rt.confirmations().request(sender.getName(), "remove machine " + m.id(), () -> {
            if (rt.machines().byId(m.id()) != m) {
                error(sender, "Machine '" + m.id() + "' was already removed or replaced; nothing changed.");
                return;
            }
            if (m.isBusy() && !force) {
                error(sender, "A ritual started on '" + m.id() + "' meanwhile; nothing changed. Add --force to resolve it.");
                return;
            }
            if (m.isBusy()) {
                ActiveRitual r = rt.rituals().byId(m.activeRitual());
                if (r != null) rt.rituals().resolveNow(r, "machine-removed");
            }
            rt.machines().remove(m.id());
            try {
                rt.saveMachines();
            } catch (IOException e) {
                error(sender, "machines.yml could not be written: " + e.getMessage() + " (the machine is removed until restart)");
            }
            Block b = m.blockIfLoaded();
            if (clearBlock && b != null && b.getType() == Material.matchMaterial(rt.settings() == null ? "RESPAWN_ANCHOR" : rt.settings().machines().coreBlock())) {
                b.setType(Material.AIR);
            }
            rt.audit().log(AuditLog.Event.MACHINE_REMOVED, Map.of("machine", m.id(), "by", sender.getName(), "clearedBlock", clearBlock));
            ok(sender, "Machine '" + m.id() + "' removed" + (clearBlock ? " and its block cleared." : ". The block was left in place (add --clear-block to remove it)."));
        });
        warn(sender, "This removes machine '" + m.id() + "' at " + m.locationKey() + ". Pending rewards are not affected.");
        warn(sender, "Type /vm admin confirm " + code + " within 30 seconds.");
    }

    private void list(CommandSender sender) {
        if (rt.machines().size() == 0) {
            info(sender, "No machines. Look at a block and run /vm admin create <id>.");
            return;
        }
        info(sender, "Machines (" + rt.machines().size() + "):");
        for (Machine m : rt.machines().all()) {
            String state = !m.record().enabled() ? "disabled" : m.world() == null ? "dormant (world not loaded)"
                    : m.isBusy() ? "RITUAL" : !m.isChunkLoaded() ? "idle (unloaded)" : "idle";
            info(sender, " " + m.id() + " @ " + m.locationKey() + " [" + m.record().profile() + "] " + state);
        }
    }

    private void status(CommandSender sender, String[] args) {
        Machine m = machineArg(sender, args);
        if (m == null) return;
        MachineRecord r = m.record();
        Settings s = rt.settings();
        info(sender, "Machine " + r.id() + " \"" + r.displayName() + "\"");
        info(sender, " location: " + r.locationKey() + (m.world() == null ? " (world not loaded)" : m.isChunkLoaded() ? "" : " (chunk unloaded)"));
        info(sender, " profile: " + r.profile() + (s != null && !s.profiles().containsKey(r.profile()) ? " (MISSING — machine refuses offerings)" : ""));
        info(sender, " enabled: " + r.enabled() + ", created by " + r.createdBy());
        Block b = m.blockIfLoaded();
        if (b != null && s != null && !b.getType().name().equals(s.machines().coreBlock())) {
            warn(sender, " block is " + b.getType() + ", expected " + s.machines().coreBlock() + " (changed outside VoidMachine?)");
        }
        if (m.isBusy()) {
            ActiveRitual a = rt.rituals().byId(m.activeRitual());
            if (a != null) {
                info(sender, " ritual: " + a.record().shortId() + " by " + a.playerName() + ", state " + a.state() + ", phase "
                        + a.phase() + ", tick " + a.tick() + "/" + (a.timeline() == null ? "?" : a.timeline().totalTicks()));
            }
        }
        info(sender, " rituals this session: " + m.ritualsThisSession() + ", last outcome: " + m.lastOutcome()
                + (m.restingMillis() > 0 ? ", resting " + (m.restingMillis() / 1000) + "s" : ""));
        long pending = rt.journal().all().stream().filter(j -> j.machineId().equals(r.id())).count();
        info(sender, " pending records from this machine: " + pending);
        info(sender, " health: " + rt.health().state());
    }

    private void enable(CommandSender sender, String[] args, boolean enabled) {
        Machine m = machineArg(sender, args);
        if (m == null) return;
        rt.machines().update(m, m.record().withEnabled(enabled));
        saveOrWarn(sender);
        rt.audit().log(AuditLog.Event.MACHINE_UPDATED, Map.of("machine", m.id(), "by", sender.getName(), "enabled", enabled));
        ok(sender, "Machine '" + m.id() + "' " + (enabled ? "enabled" : "disabled") + ".");
    }

    private void profile(CommandSender sender, String[] args) {
        Machine m = machineArg(sender, args);
        if (m == null) return;
        if (args.length < 3) {
            error(sender, "Usage: /vm admin profile <id> <profile>");
            return;
        }
        Settings s = rt.settings();
        String profile = args[2].toLowerCase(Locale.ROOT);
        if (s == null || !s.profiles().containsKey(profile)) {
            error(sender, "Unknown profile '" + profile + "'" + (s == null ? "" : ". Profiles: " + s.profileIds()));
            return;
        }
        rt.machines().update(m, m.record().withProfile(profile));
        saveOrWarn(sender);
        rt.audit().log(AuditLog.Event.MACHINE_UPDATED, Map.of("machine", m.id(), "by", sender.getName(), "profile", profile));
        ok(sender, "Machine '" + m.id() + "' now uses profile '" + profile + "' (running rituals keep their profile).");
    }

    private void tp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            error(sender, "Players only.");
            return;
        }
        Machine m = machineArg(sender, args);
        if (m == null) return;
        Location l = m.location();
        if (l == null) {
            error(sender, "World '" + m.record().world() + "' is not loaded.");
            return;
        }
        p.teleportAsync(l.add(0.5, 1.2, 2.5).setDirection(new org.bukkit.util.Vector(0, -0.2, -1)), PlayerTeleportEvent.TeleportCause.COMMAND);
    }

    private void rituals(CommandSender sender) {
        var active = rt.rituals().active();
        if (active.isEmpty()) {
            info(sender, "No rituals running.");
            return;
        }
        for (ActiveRitual r : active) {
            info(sender, " " + r.record().shortId() + " " + r.playerName() + " @" + r.machine().id() + " " + r.state() + " " + r.phase()
                    + " t=" + r.tick() + (r.isPreview() ? " (preview)" : "") + " age=" + (r.ageMillis() / 1000) + "s");
        }
    }

    private void pending(CommandSender sender, String[] args) {
        UUID filter = null;
        if (args.length >= 2) {
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (op == null) {
                error(sender, "Unknown player '" + args[1] + "'.");
                return;
            }
            filter = op.getUniqueId();
        }
        List<JournalRecord> list = new ArrayList<>(rt.journal().all());
        UUID f = filter;
        list.removeIf(r -> f != null && !r.playerId().equals(f));
        list.sort(Comparator.comparingLong(JournalRecord::createdAtMillis));
        if (list.isEmpty()) {
            info(sender, "No pending records.");
            return;
        }
        info(sender, list.size() + " record(s). REVIEW records need a decision (refund/release); others settle automatically when the player joins.");
        int shown = 0;
        for (JournalRecord r : list) {
            if (shown++ >= 30) {
                info(sender, " ... " + (list.size() - 30) + " more (filter by player)");
                break;
            }
            Player online = Bukkit.getPlayer(r.playerId());
            String owed = online == null ? "player offline" : rt.custody().remaining(online, r) + " owed now";
            info(sender, " " + r.shortId() + " " + r.kind() + " " + r.playerName() + " " + r.inputAmount() + "x " + r.itemKey() + " -> "
                    + r.outcomeId() + " reward " + r.rewardAmount() + " (" + owed + ")" + (r.note().isEmpty() ? "" : " [" + r.note() + "]"));
        }
    }

    private void inspect(CommandSender sender, String[] args) {
        JournalRecord r = recordArg(sender, args);
        if (r == null) return;
        info(sender, "Record " + r.ritualId() + " (revision " + r.revision() + ", " + r.kind() + ")");
        info(sender, " player: " + r.playerName() + " (" + r.playerId() + ")");
        info(sender, " machine: " + r.machineId() + " @ " + r.machineLocation() + ", profile " + r.profileId());
        info(sender, " created: " + java.time.Instant.ofEpochMilli(r.createdAtMillis()));
        info(sender, " offering: " + r.inputAmount() + "x " + r.itemKey());
        info(sender, " verdict: " + r.outcomeId() + " (" + r.tier() + ", x" + r.multiplier().toDisplayString() + ") reward " + r.rewardAmount());
        Player online = Bukkit.getPlayer(r.playerId());
        if (online != null) info(sender, " still owed (live ledger): " + rt.custody().remaining(online, r));
        ActiveRitual a = rt.rituals().byId(r.ritualId());
        if (a != null) info(sender, " ritual running: " + a.state() + " " + a.phase());
        if (!r.note().isEmpty()) info(sender, " note: " + r.note());
    }

    private void resolve(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "Usage: /vm admin resolve <ritual-id>");
            return;
        }
        ActiveRitual a = rt.rituals().active().stream().filter(r -> r.id().toString().startsWith(args[1].toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
        if (a == null) {
            error(sender, "No running ritual matches '" + args[1] + "' (see /vm admin rituals).");
            return;
        }
        rt.rituals().resolveNow(a, "admin:" + sender.getName());
        rt.audit().log(AuditLog.Event.ADMIN_ACTION, Map.of("action", "resolve", "ritual", a.id(), "by", sender.getName()));
        ok(sender, "Ritual " + a.record().shortId() + " resolved; the player received the sealed verdict (" + a.verdict().outcomeId() + ").");
    }

    private void refund(CommandSender sender, String[] args) {
        JournalRecord r = recordArg(sender, args);
        if (r == null) return;
        if (rt.rituals().byId(r.ritualId()) != null) {
            error(sender, "That ritual is still running; wait for it to finish (or /vm admin resolve it) first.");
            return;
        }
        if (r.rewardAmount() >= r.inputAmount()) {
            error(sender, "The verdict already returns at least the offering (" + r.rewardAmount() + " >= " + r.inputAmount() + ").");
            return;
        }
        String code = rt.confirmations().request(sender.getName(), "refund " + r.shortId(), () -> {
            if (!unchanged(sender, r)) return;
            JournalRecord refund = r.asRefund(sender.getName());
            rt.journal().replace(refund, ok -> {
                if (!ok) {
                    error(sender, "The journal could not be written; nothing changed.");
                    return;
                }
                rt.stats().recordRefund();
                rt.audit().log(AuditLog.Event.ADMIN_ACTION, Map.of("action", "refund", "ritual", r.ritualId(), "by", sender.getName(),
                        "player", r.playerName(), "amount", r.inputAmount(), "item", r.itemKey()));
                ok(sender, "Refund recorded: " + r.playerName() + " will receive " + r.inputAmount() + "x " + r.itemKey()
                        + " (now if online, otherwise when they join).");
                Player p = Bukkit.getPlayer(r.playerId());
                if (p != null) rt.custody().payOwed(p, false);
            });
        });
        warn(sender, "Refund " + r.playerName() + "'s offering of " + r.inputAmount() + "x " + r.itemKey() + " instead of the verdict '"
                + r.outcomeId() + "'" + (r.kind() == RecordKind.REVIEW ? " (V1 review record: make sure V1 did not already deliver it)" : "") + ".");
        warn(sender, "Type /vm admin confirm " + code + " within 30 seconds.");
    }

    private void release(CommandSender sender, String[] args) {
        JournalRecord r = recordArg(sender, args);
        if (r == null) return;
        if (rt.rituals().byId(r.ritualId()) != null) {
            error(sender, "That ritual is still running.");
            return;
        }
        String code = rt.confirmations().request(sender.getName(), "release " + r.shortId(), () -> {
            if (!unchanged(sender, r)) return;
            rt.journal().delete(r.ritualId(), null);
            rt.audit().log(AuditLog.Event.ADMIN_ACTION, Map.of("action", "release", "ritual", r.ritualId(), "by", sender.getName(),
                    "player", r.playerName(), "reward", r.rewardAmount(), "item", r.itemKey()));
            ok(sender, "Record " + r.shortId() + " released. Anything it still owed will NOT be delivered.");
        });
        warn(sender, "Releasing deletes record " + r.shortId() + " (" + r.playerName() + ", reward " + r.rewardAmount() + "x " + r.itemKey()
                + "). Anything still owed is forfeited. Only do this for players who will never return or after compensating manually.");
        warn(sender, "Type /vm admin confirm " + code + " within 30 seconds.");
    }

    private void reload(CommandSender sender) {
        ConfigService.Outcome out = rt.reload();
        if (out.ok()) {
            ok(sender, "Reloaded config.yml, rituals.yml and languages (" + out.problems().size() + " warning(s)). Running rituals keep their settings.");
        } else {
            error(sender, "Reload failed with " + out.errors().size() + " error(s); the previous configuration stays active:");
            for (ConfigProblem p : out.errors().stream().limit(8).toList()) error(sender, " " + p.render());
        }
        for (ConfigProblem p : out.problems().stream().filter(p -> p.severity() == ConfigProblem.Severity.WARNING).limit(5).toList()) {
            warn(sender, " " + p.render());
        }
    }

    private void health(CommandSender sender, String[] args) {
        HealthMonitor h = rt.health();
        info(sender, "Health: " + h.state() + (h.acceptsRituals() ? " (accepting offerings)" : " (offerings refused)"));
        for (HealthMonitor.Problem p : h.problems()) {
            warn(sender, " " + p.source() + " since " + p.since() + ": " + p.detail());
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("recheck")) {
            if (!sender.hasPermission(ADMIN)) {
                deny(sender);
                return;
            }
            rt.journal().probe(ok -> {
                if (ok) {
                    h.clear(HealthMonitor.Source.PRESENTATION);
                    if (rt.quarantinedAtStart() == 0) h.clear(HealthMonitor.Source.QUARANTINE);
                    ok(sender, "Storage probe succeeded. Health: " + h.state());
                } else {
                    error(sender, "Storage probe failed: " + h.problem(HealthMonitor.Source.STORAGE).map(HealthMonitor.Problem::detail).orElse("?"));
                }
            });
            h.clear(HealthMonitor.Source.RECOVERY);
        }
    }

    private void preview(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            error(sender, "Players only.");
            return;
        }
        Settings s = rt.settings();
        if (s == null || args.length < 2) {
            error(sender, "Usage: /vm admin preview <outcome> [jackpot-variant|false-loss|escalation] (stand near a machine)");
            return;
        }
        OutcomeDefinition outcome = s.outcomes().get(args[1].toLowerCase(Locale.ROOT));
        if (outcome == null) {
            error(sender, "Unknown outcome. Outcomes: " + s.outcomes().keySet());
            return;
        }
        Machine nearest = null;
        double best = 24 * 24;
        for (Machine m : rt.machines().all()) {
            Location l = m.center();
            if (l == null || !l.getWorld().equals(p.getWorld())) continue;
            double d = l.distanceSquared(p.getLocation());
            if (d < best) {
                best = d;
                nearest = m;
            }
        }
        if (nearest == null) {
            error(sender, "No machine within 24 blocks.");
            return;
        }
        String variant = null;
        FakeoutPlan fake = null;
        if (args.length >= 3) {
            String extra = args[2].toLowerCase(Locale.ROOT);
            if (extra.equals("false-loss") && outcome.tier().isWin()) {
                fake = new FakeoutPlan(FakeoutPlan.Pattern.FALSE_LOSS, "consumed", OutcomeTier.LOSS);
            } else if (extra.equals("escalation") && (outcome.tier() == OutcomeTier.GREAT || outcome.tier() == OutcomeTier.JACKPOT)) {
                fake = new FakeoutPlan(FakeoutPlan.Pattern.ESCALATION, "returned", OutcomeTier.NEUTRAL);
            } else {
                variant = extra;
            }
        }
        if (!rt.rituals().startPreview(p, nearest, outcome, variant, fake)) {
            error(sender, "Cannot preview now (machine busy, configuration invalid, or you are in a ritual).");
            return;
        }
        rt.audit().log(AuditLog.Event.ADMIN_ACTION, Map.of("action", "preview", "machine", nearest.id(), "by", p.getName(), "outcome", outcome.id()));
        ok(sender, "Preview of '" + outcome.id() + "' at " + nearest.id() + " — visual only, nothing is taken or given, only you see it.");
    }

    private void odds(CommandSender sender, String[] args) {
        Settings s = rt.settings();
        if (s == null) {
            error(sender, "The configuration is invalid.");
            return;
        }
        String id = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "default";
        Settings.Profile p = s.profiles().get(id);
        if (p == null) {
            error(sender, "Unknown profile. Profiles: " + s.profileIds());
            return;
        }
        info(sender, "Profile '" + id + "' (theme " + p.theme() + ", pacing " + p.pacing() + "):");
        for (OutcomeTable.Entry e : p.table().entries()) {
            info(sender, String.format(Locale.ROOT, " %-10s x%-5s %7.3f%%", e.definition().id(), e.definition().multiplier().toDisplayString(),
                    100.0 * p.table().probabilities().get(e.definition().id())));
        }
        info(sender, String.format(Locale.ROOT, " Expected return: %.3f items per item offered (%.1f%% of offered items leave the economy)",
                p.table().expectedReturn(), 100 * (1 - p.table().expectedReturn())));
    }

    private void voidEvent(CommandSender sender, String[] args) {
        Machine m = machineArg(sender, args);
        if (m == null) return;
        if (rt.ambient().triggerVoidEvent(m)) ok(sender, "Void event triggered at " + m.id() + ".");
        else error(sender, "Cannot trigger now (ambient disabled, machine busy or unloaded).");
    }

    private void confirm(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "Usage: /vm admin confirm <code>");
            return;
        }
        String done = rt.confirmations().confirm(sender.getName(), args[1]);
        if (done == null) error(sender, "No pending action with that code (expired after 30 seconds, or issued to someone else).");
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** A confirmation acts on the record as it was shown; if it settled or changed meanwhile, nothing happens. */
    private boolean unchanged(CommandSender sender, JournalRecord shown) {
        JournalRecord now = rt.journal().get(shown.ritualId());
        if (now == null) {
            error(sender, "Record " + shown.shortId() + " no longer exists (it was settled meanwhile); nothing changed.");
            return false;
        }
        if (now.revision() != shown.revision() || rt.rituals().byId(shown.ritualId()) != null) {
            error(sender, "Record " + shown.shortId() + " changed since you looked at it; nothing changed. Run the command again.");
            return false;
        }
        return true;
    }

    private Machine machineArg(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "Usage: /vm admin " + args[0] + " <machine-id>");
            return null;
        }
        Machine m = rt.machines().byId(args[1].toLowerCase(Locale.ROOT));
        if (m == null) error(sender, "No machine '" + args[1] + "'. See /vm admin list.");
        return m;
    }

    private JournalRecord recordArg(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].length() < 4) {
            error(sender, "Usage: /vm admin " + args[0] + " <record-id> (at least the first 4 characters; see /vm admin pending)");
            return null;
        }
        String prefix = args[1].toLowerCase(Locale.ROOT);
        List<JournalRecord> matches = rt.journal().all().stream().filter(r -> r.ritualId().toString().startsWith(prefix)).toList();
        if (matches.isEmpty()) {
            error(sender, "No record matches '" + args[1] + "'.");
            return null;
        }
        if (matches.size() > 1) {
            error(sender, "'" + args[1] + "' matches " + matches.size() + " records; type more characters.");
            return null;
        }
        return matches.getFirst();
    }

    private void saveOrWarn(CommandSender sender) {
        try {
            rt.saveMachines();
        } catch (IOException e) {
            error(sender, "machines.yml could not be written: " + e.getMessage() + " (change applies until restart)");
        }
    }

    private void deny(CommandSender sender) {
        if (sender instanceof Player p) rt.messages().send(p, "generic.no-permission");
        else sender.sendMessage("You do not have permission.");
    }

    private static void info(CommandSender s, String text) {
        s.sendMessage(Component.text(text, s instanceof ConsoleCommandSender ? null : NamedTextColor.GRAY));
    }

    private static void ok(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.GREEN));
    }

    private static void warn(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.GOLD));
    }

    private static void error(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.RED));
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    // ------------------------------------------------------------------------------------------
    // Tab completion
    // ------------------------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("help", "stats", "top", "claim"));
            if (sender.hasPermission(ADMIN) || sender.hasPermission(INSPECT)) base.add("admin");
            return filter(base, args[0]);
        }
        if (args[0].equalsIgnoreCase("stats") && args.length == 2) return filter(List.of("server"), args[1]);
        if (args[0].equalsIgnoreCase("top") && args.length == 2) return filter(List.of("rituals", "jackpots", "returned"), args[1]);
        if (!args[0].equalsIgnoreCase("admin")) return List.of();
        boolean admin = sender.hasPermission(ADMIN);
        if (!admin && !sender.hasPermission(INSPECT)) return List.of();
        if (args.length == 2) return filter(admin ? ADMIN_SUBS : READ_ONLY, args[1]);
        String sub = args[1].toLowerCase(Locale.ROOT);
        Settings s = rt.settings();
        if (args.length == 3) {
            return switch (sub) {
                case "remove", "status", "enable", "disable", "profile", "tp", "voidevent" ->
                        filter(rt.machines().all().stream().map(Machine::id).toList(), args[2]);
                case "inspect", "refund", "release" -> filter(rt.journal().all().stream().map(JournalRecord::shortId).toList(), args[2]);
                case "resolve" -> filter(rt.rituals().active().stream().map(r -> r.record().shortId()).toList(), args[2]);
                case "odds" -> s == null ? List.of() : filter(s.profileIds(), args[2]);
                case "preview" -> s == null ? List.of() : filter(List.copyOf(s.outcomes().keySet()), args[2]);
                case "pending" -> filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
                case "health" -> filter(List.of("recheck"), args[2]);
                default -> List.of();
            };
        }
        if (args.length == 4) {
            if ((sub.equals("create") || sub.equals("profile")) && s != null) return filter(s.profileIds(), args[3]);
            if (sub.equals("remove")) return filter(List.of("--force", "--clear-block"), args[3]);
            if (sub.equals("preview") && rt.book() != null) {
                List<String> opts = new ArrayList<>(List.of("false-loss", "escalation"));
                rt.book().themes().values().forEach(t -> t.jackpots().forEach(j -> opts.add(j.id())));
                return filter(opts, args[3]);
            }
        }
        if (args.length == 5 && sub.equals("remove")) return filter(List.of("--force", "--clear-block"), args[4]);
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).sorted().toList();
    }

    static Duration seconds(long s) {
        return Duration.ofSeconds(s);
    }
}
