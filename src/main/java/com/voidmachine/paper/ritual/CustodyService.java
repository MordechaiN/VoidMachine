package com.voidmachine.paper.ritual;

import com.voidmachine.api.MachineView;
import com.voidmachine.api.RitualView;
import com.voidmachine.api.event.RitualRecoveryEvent;
import com.voidmachine.core.config.Settings;
import com.voidmachine.core.custody.CustodyEngine;
import com.voidmachine.core.health.HealthMonitor;
import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.ledger.PlayerLedger;
import com.voidmachine.paper.audit.AuditLog;
import com.voidmachine.paper.i18n.Messages;
import com.voidmachine.paper.item.BukkitCustodyPlayer;
import com.voidmachine.paper.item.ItemCodec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Everything that moves owed items into players' hands: payouts at reveal, retries of held rewards,
 * reconciliation when a player joins (with the ledger freshly loaded from their data file), and a
 * throttled sweep that verifies settled records of offline players.
 *
 * <p>All decisions are delegated to {@link CustodyEngine}; this class only provides the Bukkit side
 * and the messages. Main-thread confined.</p>
 */
public final class CustodyService {

    /** Who may not be paid by background retries yet (rituals that have not revealed). */
    public interface ActiveRituals {
        Set<UUID> unrevealedIds();

        Set<UUID> activeIds();
    }

    private final Plugin plugin;
    private final JournalService journal;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final NamespacedKey ledgerKey;
    private final NamespacedKey rewardKey;
    private final HealthMonitor health;
    private final AuditLog audit;
    private final Logger logger;
    private final long sessionStart;
    private final Map<UUID, ItemStack> templates = new HashMap<>();
    private final Set<UUID> undecodable = new HashSet<>();
    private final Map<UUID, Long> lastRetry = new HashMap<>();
    private final List<String> recoveryAlerts = new ArrayList<>();
    private ActiveRituals active = new ActiveRituals() {
        @Override
        public Set<UUID> unrevealedIds() {
            return Set.of();
        }

        @Override
        public Set<UUID> activeIds() {
            return Set.of();
        }
    };
    private Function<JournalRecord, MachineView> machineLookup = r -> null;
    private int recoveriesThisSession;
    private int paidThisSession;

    public CustodyService(Plugin plugin, JournalService journal, Messages messages, Supplier<Settings> settings,
                          NamespacedKey ledgerKey, NamespacedKey rewardKey, HealthMonitor health, AuditLog audit,
                          Logger logger, long sessionStart) {
        this.plugin = plugin;
        this.journal = journal;
        this.messages = messages;
        this.settings = settings;
        this.ledgerKey = ledgerKey;
        this.rewardKey = rewardKey;
        this.health = health;
        this.audit = audit;
        this.logger = logger;
        this.sessionStart = sessionStart;
    }

    public void bind(ActiveRituals active, Function<JournalRecord, MachineView> machineLookup) {
        this.active = active;
        this.machineLookup = machineLookup;
    }

    public BukkitCustodyPlayer custodyOf(Player p, UUID ritualId) {
        Settings s = settings.get();
        Settings.Overflow overflow = s == null ? Settings.Overflow.HOLD : s.delivery().overflow();
        return new BukkitCustodyPlayer(p, ledgerKey, rewardKey, overflow, ritualId, logger);
    }

    /** Decoded single-item template of a record; {@code null} (and an admin alert) if it cannot be decoded. */
    public ItemStack template(JournalRecord r) {
        ItemStack cached = templates.get(r.ritualId());
        if (cached != null) return cached.clone();
        if (undecodable.contains(r.ritualId())) return null;
        try {
            ItemStack t = ItemCodec.decodeTemplate(r.itemTemplate());
            templates.put(r.ritualId(), t);
            return t.clone();
        } catch (RuntimeException e) {
            undecodable.add(r.ritualId());
            alert("record " + r.shortId() + " of " + r.playerName() + ": item " + r.itemKey()
                    + " cannot be read by this server version (" + e.getMessage() + "); left untouched");
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Payout
    // ------------------------------------------------------------------------------------------

    /** Pays what fits now. Never call for a ritual that has not revealed yet. */
    public CustodyEngine.PayoutResult pay(Player p, JournalRecord r) {
        if (p.isDead() || !p.isValid()) {
            return new CustodyEngine.PayoutResult(CustodyEngine.PayoutStatus.HELD, 0, 0, -1);
        }
        ItemStack template = template(r);
        if (template == null) {
            return new CustodyEngine.PayoutResult(CustodyEngine.PayoutStatus.HELD, 0, 0, -1);
        }
        CustodyEngine.PayoutResult res = CustodyEngine.pay(custodyOf(p, r.ritualId()), r, template, Integer.MAX_VALUE);
        if (res.given() > 0) {
            paidThisSession += res.given();
            p.updateInventory();
            audit.log(AuditLog.Event.REWARD_DELIVERED, Map.of("ritual", r.ritualId(), "player", p.getName(),
                    "item", r.itemKey(), "given", res.given(), "paid", res.paidTotal(), "remaining", res.remaining()));
        }
        if (res.status() == CustodyEngine.PayoutStatus.HELD && res.remaining() > 0) {
            audit.log(AuditLog.Event.REWARD_HELD, Map.of("ritual", r.ritualId(), "player", p.getName(),
                    "item", r.itemKey(), "held", res.remaining()));
        }
        if (res.status() == CustodyEngine.PayoutStatus.LEDGER_CORRUPT) {
            alert("ledger of " + p.getName() + " is unreadable; their records are left untouched");
        }
        return res;
    }

    /** Remaining reward of a record according to the live ledger (0 when settled or not captured). */
    public int remaining(Player p, JournalRecord r) {
        try {
            PlayerLedger.Entry e = PlayerLedger.decode(p.getPersistentDataContainer().get(ledgerKey, PersistentDataType.STRING)).get(r.ritualId());
            if (e == null) return r.requiresCaptureMark() ? 0 : r.rewardAmount();
            return Math.max(0, r.rewardAmount() - e.paid());
        } catch (PlayerLedger.LedgerFormatException ex) {
            return 0;
        }
    }

    /** Records with items still owed to an online player (excluding unrevealed rituals). */
    public List<JournalRecord> owed(Player p) {
        Set<UUID> hold = active.unrevealedIds();
        List<JournalRecord> out = new ArrayList<>();
        for (JournalRecord r : journal.forPlayer(p.getUniqueId())) {
            if (hold.contains(r.ritualId())) continue;
            if (remaining(p, r) > 0) out.add(r);
        }
        return out;
    }

    /** Pays held rewards if there is room. Returns items given. */
    public int payOwed(Player p, boolean announceNothing) {
        int total = 0;
        int stillHeld = 0;
        for (JournalRecord r : owed(p)) {
            CustodyEngine.PayoutResult res = pay(p, r);
            total += res.given();
            if (res.given() > 0) {
                messages.send(p, "claims.returned", amount("amount", res.given()), item(r));
            }
            if (res.remaining() > 0) stillHeld += res.remaining();
        }
        if (total == 0 && announceNothing) {
            messages.send(p, stillHeld > 0 ? "claims.no-room" : "claims.nothing", amount("amount", stillHeld));
        }
        return total;
    }

    /** Called every few seconds: retries held rewards for online players (throttled per player). */
    public void retryTick() {
        // Decoded templates are only needed while their record exists.
        templates.keySet().removeIf(id -> journal.get(id) == null);
        undecodable.removeIf(id -> journal.get(id) == null);
        Settings s = settings.get();
        long every = (s == null ? 5 : s.delivery().retrySeconds()) * 1000L;
        long now = System.currentTimeMillis();
        for (UUID id : journal.playersWithRecords()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || p.isDead()) continue;
            Long last = lastRetry.get(id);
            if (last != null && now - last < every) continue;
            lastRetry.put(id, now);
            payOwed(p, false);
        }
    }

    /** A player probably made room (closed an inventory, dropped something): retry soon. */
    public void nudge(UUID player) {
        lastRetry.remove(player);
    }

    // ------------------------------------------------------------------------------------------
    // Reconciliation
    // ------------------------------------------------------------------------------------------

    /**
     * Join: the player's ledger was just loaded from their data file, so it proves what was saved.
     * Must run once per join, before anything else touches the ledger.
     */
    public void reconcile(Player p, CustodyEngine.Mode mode) {
        List<JournalRecord> records = journal.forPlayer(p.getUniqueId());
        PlayerLedger ledger;
        try {
            ledger = PlayerLedger.decode(p.getPersistentDataContainer().get(ledgerKey, PersistentDataType.STRING));
        } catch (PlayerLedger.LedgerFormatException e) {
            alert("ledger of " + p.getName() + " is unreadable (" + e.getMessage() + "); " + records.size()
                    + " record(s) left untouched for an admin");
            messages.send(p, "recovery.problem");
            return;
        }
        if (records.isEmpty() && ledger.isEmpty()) return;
        Set<UUID> activeIds = active.activeIds();
        CustodyEngine.Plan plan = CustodyEngine.plan(records, ledger, mode, activeIds);
        if (plan.isEmpty()) return;
        audit.log(AuditLog.Event.RECOVERY_STARTED, Map.of("player", p.getName(), "mode", mode.name(),
                "decisions", plan.decisions().size()));
        BukkitCustodyPlayer custody = custodyOf(p, null);
        for (CustodyEngine.Decision d : plan.decisions()) {
            JournalRecord r = d.record();
            boolean interrupted = r.createdAtMillis() < sessionStart && r.requiresCaptureMark();
            switch (d.action()) {
                case DISCARD_NOT_CAPTURED -> {
                    journal.delete(r.ritualId(), null);
                    recovered(p, r, "discard", 0);
                    if (interrupted) messages.send(p, "recovery.not-taken", item(r), amount("amount", r.inputAmount()));
                }
                case FINALIZE, FINALIZE_OVERPAID -> {
                    UUID id = r.ritualId();
                    journal.delete(id, () -> {
                        Player online = Bukkit.getPlayer(p.getUniqueId());
                        if (online != null) CustodyEngine.forget(custodyOf(online, null), id);
                    });
                    if (d.action() == CustodyEngine.Action.FINALIZE_OVERPAID) {
                        alert("record " + r.shortId() + " of " + p.getName() + " was over-paid (" + d.paid() + " of "
                                + r.rewardAmount() + "); finalized");
                    }
                    if (interrupted && r.rewardAmount() == 0) {
                        messages.send(p, "recovery.interrupted", outcome(p, r));
                        messages.send(p, "recovery.consumed", item(r), amount("amount", r.inputAmount()));
                    }
                    recovered(p, r, "finalize", 0);
                }
                case PAY -> {
                    if (active.unrevealedIds().contains(r.ritualId())) continue;
                    if (interrupted && d.paid() == 0) messages.send(p, "recovery.interrupted", outcome(p, r));
                    CustodyEngine.PayoutResult res = pay(p, r);
                    if (res.given() > 0) {
                        messages.send(p, interrupted && d.paid() == 0 ? "recovery.paid" : "claims.returned",
                                amount("amount", res.given()), item(r));
                    }
                    if (res.remaining() > 0) {
                        messages.send(p, "claims.waiting", amount("amount", res.remaining()), item(r));
                    }
                    recovered(p, r, "pay", res.given());
                }
                case AWAIT_VERIFICATION -> {
                    // Fully paid this session; the next join proves it reached disk.
                }
            }
        }
        for (UUID id : plan.forgettableEntries()) CustodyEngine.forget(custody, id);
        for (PlayerLedger.Entry e : plan.orphanEntries()) {
            alert("ledger of " + p.getName() + " owes " + (e.reward() - e.paid()) + " item(s) for ritual "
                    + e.ritualId().toString().substring(0, 8) + " but its journal record is missing (deleted or lost); entry kept as evidence");
            audit.log(AuditLog.Event.RECOVERY_FAILED, Map.of("player", p.getName(), "ritual", e.ritualId(),
                    "owed", e.reward() - e.paid(), "reason", "journal record missing"));
        }
        audit.log(AuditLog.Event.RECOVERY_COMPLETED, Map.of("player", p.getName(), "mode", mode.name()));
    }

    /**
     * Verifies settled records of offline players by reading their saved data, a few players per call
     * (each read may touch the disk). Unsettled records are left for the player's return.
     */
    public int sweepOffline(int maxPlayers) {
        int checked = 0;
        int finalized = 0;
        Set<UUID> activeIds = active.activeIds();
        for (UUID id : journal.playersWithRecords()) {
            if (checked >= maxPlayers) break;
            if (Bukkit.getPlayer(id) != null) continue;
            OfflinePlayer off = Bukkit.getOfflinePlayer(id);
            if (!off.hasPlayedBefore()) continue;
            checked++;
            PlayerLedger ledger;
            try {
                ledger = PlayerLedger.decode(off.getPersistentDataContainer().get(ledgerKey, PersistentDataType.STRING));
            } catch (PlayerLedger.LedgerFormatException | RuntimeException e) {
                continue;
            }
            CustodyEngine.Plan plan = CustodyEngine.plan(journal.forPlayer(id), ledger, CustodyEngine.Mode.FRESH_LOAD, activeIds);
            for (CustodyEngine.Decision d : plan.decisions()) {
                if (d.action() == CustodyEngine.Action.FINALIZE || d.action() == CustodyEngine.Action.DISCARD_NOT_CAPTURED) {
                    // Interrupted rituals of offline players are kept so they get a message on return.
                    if (d.record().createdAtMillis() < sessionStart && d.record().requiresCaptureMark()
                            && (d.action() == CustodyEngine.Action.DISCARD_NOT_CAPTURED || d.record().rewardAmount() == 0)) {
                        continue;
                    }
                    journal.delete(d.record().ritualId(), null);
                    finalized++;
                }
            }
        }
        return finalized;
    }

    // ------------------------------------------------------------------------------------------

    private void recovered(Player p, JournalRecord r, String action, int paid) {
        recoveriesThisSession++;
        audit.log(AuditLog.Event.RECOVERY_APPLIED, Map.of("player", p.getName(), "ritual", r.ritualId(),
                "action", action, "item", r.itemKey(), "paid", paid));
        ItemStack t = template(r);
        if (t == null) return;
        MachineView mv = machineLookup.apply(r);
        if (mv == null) mv = new MachineView(r.machineId(), r.machineId(), "", 0, 0, 0, r.profileId(), false);
        RitualView view = new RitualView(r.ritualId(), r.playerId(), r.playerName(), mv, r.profileId(), t, r.inputAmount(),
                r.outcomeId(), r.tier(), r.multiplier().toDisplayString(), r.rewardAmount());
        Bukkit.getPluginManager().callEvent(new RitualRecoveryEvent(view, paid));
    }

    private void alert(String message) {
        logger.warning("[Recovery] " + message);
        synchronized (recoveryAlerts) {
            recoveryAlerts.add(message);
            if (recoveryAlerts.size() > 50) recoveryAlerts.removeFirst();
        }
        health.raise(HealthMonitor.Source.RECOVERY, message);
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("voidmachine.admin.alerts")) {
                admin.sendMessage(Component.text("[VoidMachine] " + message, net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        }
    }

    public List<String> recoveryAlerts() {
        synchronized (recoveryAlerts) {
            return List.copyOf(recoveryAlerts);
        }
    }

    public int recoveriesThisSession() {
        return recoveriesThisSession;
    }

    public int paidThisSession() {
        return paidThisSession;
    }

    public void forgetPlayer(UUID id) {
        lastRetry.remove(id);
    }

    private TagResolver item(JournalRecord r) {
        ItemStack t = template(r);
        return Placeholder.component("item", t == null ? Component.text(r.itemKey()) : ItemCodec.name(t));
    }

    private TagResolver outcome(Player p, JournalRecord r) {
        String key = "outcomes." + r.outcomeId() + ".name";
        Component name = messages.has(key) ? messages.render(p, key) : Component.text(r.outcomeId());
        return TagResolver.resolver(Placeholder.component("outcome", name),
                Placeholder.unparsed("reward", Integer.toString(r.rewardAmount())));
    }

    static TagResolver amount(String name, int value) {
        return Placeholder.unparsed(name, Integer.toString(value));
    }

    public Plugin plugin() {
        return plugin;
    }
}
