package com.voidmachine.paper.ritual;

import com.voidmachine.api.event.RitualCommitEvent;
import com.voidmachine.api.event.RitualCompleteEvent;
import com.voidmachine.api.event.RitualStartEvent;
import com.voidmachine.core.config.Settings;
import com.voidmachine.core.custody.CustodyEngine;
import com.voidmachine.core.health.HealthMonitor;
import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.outcome.OutcomeEngine;
import com.voidmachine.core.outcome.Verdict;
import com.voidmachine.core.script.Ritualbook;
import com.voidmachine.core.script.Theme;
import com.voidmachine.core.stats.StatsBook;
import com.voidmachine.core.timeline.Pacing;
import com.voidmachine.paper.audit.AuditLog;
import com.voidmachine.paper.i18n.Messages;
import com.voidmachine.paper.item.ItemCodec;
import com.voidmachine.paper.item.OfferingRules;
import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.machine.MachineRegistry;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrates rituals. The order of operations is the safety argument:
 *
 * <ol>
 *   <li>validate, fire {@link RitualStartEvent}, roll the verdict (exactly once);</li>
 *   <li>write the journal record (fsync, off-thread) — nothing has been taken yet;</li>
 *   <li>on the main thread, re-validate and take the offering + write the ledger mark + save the player;</li>
 *   <li>presentation runs; it reads the verdict and never changes it;</li>
 *   <li>at the reveal, pay what fits; the rest is held durably and paid when there is room.</li>
 * </ol>
 *
 * Player events (quit, death, teleport) never refund or re-roll: they only defer delivery.
 * Main-thread confined.
 */
public final class RitualService implements CustodyService.ActiveRituals {

    public enum BeginResult {
        ACCEPTED, UNAVAILABLE, CONFIG_INVALID, NO_PERMISSION, PROFILE_PERMISSION, CREATIVE, WORLD_BLOCKED, MACHINE_DISABLED,
        MACHINE_BUSY, MACHINE_RESTING, PLAYER_COOLDOWN, ALREADY_IN_RITUAL, TOO_MANY_RITUALS, TOO_MANY_UNCLAIMED,
        OFFERING_CHANGED, OFFERING_REFUSED, CANCELLED_BY_PLUGIN, BAD_AMOUNT, PROFILE_MISSING
    }

    /** Hooks into the presentation layer. */
    public interface Presentation {
        /** Composes and starts the presentation for a ritual whose offering was just taken. */
        void start(ActiveRitual ritual);

        /** Removes every trace of the presentation (bars, displays, fake blocks, menus). */
        void cleanup(ActiveRitual ritual);

        /** Spectator narration, titles and announcements for the truth reveal (after payout). */
        void revealed(ActiveRitual ritual, CustodyEngine.PayoutResult payout);
    }

    private final JournalService journal;
    private final CustodyService custody;
    private final MachineRegistry machines;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final Supplier<Ritualbook> book;
    private final OutcomeEngine engine;
    private final HealthMonitor health;
    private final AuditLog audit;
    private final StatsBook stats;
    private final Logger logger;
    private Presentation presentation;

    private final Map<UUID, ActiveRitual> rituals = new LinkedHashMap<>();
    private final Map<UUID, ActiveRitual> byPlayer = new HashMap<>();
    private final Map<UUID, Long> playerCooldownUntil = new HashMap<>();
    private volatile boolean accepting = true;
    private long completed;
    private long aborted;

    public RitualService(JournalService journal, CustodyService custody, MachineRegistry machines, Messages messages,
                         Supplier<Settings> settings, Supplier<Ritualbook> book, OutcomeEngine engine,
                         HealthMonitor health, AuditLog audit, StatsBook stats, Logger logger) {
        this.journal = journal;
        this.custody = custody;
        this.machines = machines;
        this.messages = messages;
        this.settings = settings;
        this.book = book;
        this.engine = engine;
        this.health = health;
        this.audit = audit;
        this.stats = stats;
        this.logger = logger;
    }

    public void bind(Presentation presentation) {
        this.presentation = presentation;
    }

    // ------------------------------------------------------------------------------------------
    // Begin
    // ------------------------------------------------------------------------------------------

    /** Pre-checks shared by the menu (to show why the button is disabled) and {@link #begin}. */
    public Optional<BeginResult> precheck(Player p, Machine m) {
        Settings s = settings.get();
        if (s == null || book.get() == null) return Optional.of(BeginResult.CONFIG_INVALID);
        if (!accepting || !health.acceptsRituals()) return Optional.of(BeginResult.UNAVAILABLE);
        if (!p.hasPermission("voidmachine.use")) return Optional.of(BeginResult.NO_PERMISSION);
        if (!m.record().enabled()) return Optional.of(BeginResult.MACHINE_DISABLED);
        if (!s.machines().worldAllowed(m.record().world())) return Optional.of(BeginResult.WORLD_BLOCKED);
        Settings.Profile profile = s.profiles().get(m.record().profile());
        if (profile == null) return Optional.of(BeginResult.PROFILE_MISSING);
        if (!profile.permission().isEmpty() && !p.hasPermission(profile.permission())) return Optional.of(BeginResult.PROFILE_PERMISSION);
        if (p.getGameMode() == GameMode.CREATIVE && !s.machines().allowCreative()) return Optional.of(BeginResult.CREATIVE);
        if (byPlayer.containsKey(p.getUniqueId())) return Optional.of(BeginResult.ALREADY_IN_RITUAL);
        if (m.isBusy()) return Optional.of(BeginResult.MACHINE_BUSY);
        boolean bypass = p.hasPermission("voidmachine.bypass.cooldown");
        if (!bypass && m.restingMillis() > 0) return Optional.of(BeginResult.MACHINE_RESTING);
        if (!bypass && playerCooldownMillis(p.getUniqueId()) > 0) return Optional.of(BeginResult.PLAYER_COOLDOWN);
        if (rituals.size() >= s.limits().maxActiveRituals()) return Optional.of(BeginResult.TOO_MANY_RITUALS);
        if (custody.owed(p).size() >= s.limits().maxUnclaimedPerPlayer()) return Optional.of(BeginResult.TOO_MANY_UNCLAIMED);
        return Optional.empty();
    }

    /**
     * Starts a ritual for the item currently in storage slot {@code slot}.
     *
     * @param expected the item the player selected (only its type and components matter)
     */
    public BeginResult begin(Player p, Machine m, int slot, ItemStack expected, int amount) {
        Optional<BeginResult> refused = precheck(p, m);
        if (refused.isPresent()) return refused.get();
        Settings s = settings.get();
        Ritualbook rb = book.get();
        if (amount < 1 || amount > s.offering().maxAmount()) return BeginResult.BAD_AMOUNT;
        ItemStack current = slot >= 0 && slot < 36 ? p.getInventory().getItem(slot) : null;
        if (current == null || current.isEmpty() || !current.isSimilar(expected) || current.getAmount() < amount) {
            return BeginResult.OFFERING_CHANGED;
        }
        if (OfferingRules.check(current, s.offering()).isPresent()) return BeginResult.OFFERING_REFUSED;

        RitualStartEvent event = new RitualStartEvent(p, m.view(), current.asOne(), amount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return BeginResult.CANCELLED_BY_PLUGIN;

        Settings.Profile profile = s.profiles().get(m.record().profile());
        Theme theme = rb.theme(profile.theme());
        Pacing pacing = rb.pacing(profile.pacing()).orElseThrow();
        // The one and only roll.
        Verdict verdict = engine.decide(profile.table(), amount, s.limits().maxRewardAmount());
        UUID id = UUID.randomUUID();
        ItemStack template = current.asOne();
        JournalRecord record = JournalRecord.forRitual(id, p.getUniqueId(), p.getName(), m.id(), m.locationKey(),
                profile.id(), System.currentTimeMillis(), ItemCodec.key(template), ItemCodec.encodeTemplate(template), verdict);
        ActiveRitual r = new ActiveRitual(id, p.getUniqueId(), p.getName(), m, s, profile, theme, pacing, template, slot,
                verdict, record, engine.random().nextSeed(), false);
        rituals.put(id, r);
        byPlayer.put(p.getUniqueId(), r);
        m.claim(id);
        audit.log(AuditLog.Event.RITUAL_STARTED, Map.of("ritual", id, "player", p.getName(), "machine", m.id(),
                "item", record.itemKey(), "amount", amount, "profile", profile.id()));
        audit.log(AuditLog.Event.MACHINE_LOCKED, Map.of("machine", m.id(), "ritual", id));
        journal.create(record, ok -> onRecordWritten(r, ok));
        return BeginResult.ACCEPTED;
    }

    /**
     * Admin preview at a machine: plays the full choreography of {@code outcome} for the admin only.
     * Nothing is taken, recorded, paid, counted, announced or fired as an API event.
     */
    public boolean startPreview(Player admin, Machine m, com.voidmachine.core.outcome.OutcomeDefinition outcome,
                                String variant, com.voidmachine.core.timeline.FakeoutPlan fakeout) {
        Settings s = settings.get();
        Ritualbook rb = book.get();
        if (s == null || rb == null || m.isBusy() || byPlayer.containsKey(admin.getUniqueId())) return false;
        Settings.Profile profile = s.profiles().get(m.record().profile());
        if (profile == null) return false;
        ItemStack template = admin.getInventory().getItemInMainHand().isEmpty()
                ? new ItemStack(org.bukkit.Material.DIAMOND) : admin.getInventory().getItemInMainHand().asOne();
        Verdict verdict = OutcomeEngine.verdictFor(outcome, 16, s.limits().maxRewardAmount());
        UUID id = UUID.randomUUID();
        JournalRecord record = JournalRecord.forRitual(id, admin.getUniqueId(), admin.getName(), m.id(), m.locationKey(),
                profile.id(), System.currentTimeMillis(), ItemCodec.key(template), new byte[]{0}, verdict);
        ActiveRitual r = new ActiveRitual(id, admin.getUniqueId(), admin.getName(), m, s, profile, rb.theme(profile.theme()),
                rb.pacing(profile.pacing()).orElseThrow(), template, -1, verdict, record, engine.random().nextSeed(), true);
        r.forcedVariant = variant;
        r.forcedFakeout = fakeout;
        r.state = ActiveRitual.State.LIVE;
        rituals.put(id, r);
        byPlayer.put(admin.getUniqueId(), r);
        m.claim(id);
        try {
            presentation.start(r);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Preview could not start", e);
            finish(r, "preview-error");
            return false;
        }
        return true;
    }

    private void onRecordWritten(ActiveRitual r, boolean durable) {
        if (r.state != ActiveRitual.State.PREPARING) {
            // Aborted while the record was being written (shutdown is handled by the runtime): nothing was taken.
            if (durable && r.state == ActiveRitual.State.ABORTED) journal.delete(r.id, null);
            return;
        }
        if (!durable) {
            abort(r, "storage", false);
            Player p = Bukkit.getPlayer(r.playerId);
            if (p != null) messages.send(p, "ritual.unavailable");
            return;
        }
        audit.log(AuditLog.Event.CHECKPOINT_WRITTEN, Map.of("ritual", r.id, "player", r.playerName));
        Player p = Bukkit.getPlayer(r.playerId);
        if (p == null || !p.isOnline() || p.isDead() || machines.byId(r.machine.id()) != r.machine) {
            abort(r, p == null || !p.isOnline() ? "owner-left" : p.isDead() ? "owner-died" : "machine-removed", true);
            return;
        }
        CustodyEngine.CaptureResult captured = CustodyEngine.capture(custody.custodyOf(p, r.id), r.record, r.template, r.slot);
        switch (captured) {
            case CAPTURED -> {
                r.state = ActiveRitual.State.LIVE;
                p.updateInventory();
                audit.log(AuditLog.Event.ITEM_CAPTURED, Map.of("ritual", r.id, "player", r.playerName,
                        "item", r.record.itemKey(), "amount", r.verdict.inputAmount()));
                audit.log(AuditLog.Event.OUTCOME_ROLLED, Map.of("ritual", r.id, "outcome", r.verdict.outcomeId(),
                        "reward", r.verdict.rewardAmount(), "capped", r.verdict.capped()));
                log(Settings.LogLevel.DEBUG, "Ritual " + r.record.shortId() + " sealed for " + r.playerName + ": "
                        + r.verdict.inputAmount() + "x " + r.record.itemKey() + " -> " + r.verdict.outcomeId());
                Bukkit.getPluginManager().callEvent(new RitualCommitEvent(r.view()));
                try {
                    presentation.start(r);
                } catch (RuntimeException e) {
                    logger.log(Level.SEVERE, "Presentation of ritual " + r.record.shortId() + " could not start; resolving it now "
                            + "(the verdict is unaffected)", e);
                    health.raise(HealthMonitor.Source.PRESENTATION, "a ritual presentation failed to start: " + e);
                    resolveNow(r, "presentation-error");
                    return;
                }
                audit.log(AuditLog.Event.ANIMATION_STARTED, Map.of("ritual", r.id, "ticks", r.timeline.totalTicks(),
                        "fakeout", r.fakeout == null ? "none" : r.fakeout.pattern().id()));
            }
            case SLOT_CHANGED -> {
                abort(r, "offering-moved", true);
                messages.send(p, "ritual.offering-moved");
            }
            case LEDGER_CORRUPT, ALREADY_CAPTURED -> {
                abort(r, "ledger-" + captured.name().toLowerCase(java.util.Locale.ROOT), true);
                messages.send(p, "recovery.problem");
            }
        }
    }

    /** Ends a ritual whose offering was never taken. */
    private void abort(ActiveRitual r, String reason, boolean deleteRecord) {
        r.state = ActiveRitual.State.ABORTED;
        rituals.remove(r.id);
        byPlayer.remove(r.playerId, r);
        r.machine.release(r.id, 0, null);
        if (deleteRecord) journal.delete(r.id, null);
        aborted++;
        if ("storage".equals(reason)) stats.recordFailure();
        audit.log(AuditLog.Event.TRANSACTION_ABORTED, Map.of("ritual", r.id, "player", r.playerName, "reason", reason,
                "taken", false));
        audit.log(AuditLog.Event.MACHINE_UNLOCKED, Map.of("machine", r.machine.id(), "ritual", r.id));
        log(Settings.LogLevel.INFO, "Ritual " + r.record.shortId() + " of " + r.playerName + " ended before anything was taken (" + reason + ")");
    }

    // ------------------------------------------------------------------------------------------
    // Reveal & finish (called by the presentation, or forced)
    // ------------------------------------------------------------------------------------------

    /** The moment of truth: pay what fits. Idempotent. */
    public void reveal(ActiveRitual r) {
        if (r.state != ActiveRitual.State.LIVE) return;
        r.state = ActiveRitual.State.REVEALED;
        if (r.preview) {
            presentation.revealed(r, new CustodyEngine.PayoutResult(CustodyEngine.PayoutStatus.SETTLED, 0, 0, 0));
            return;
        }
        Player p = Bukkit.getPlayer(r.playerId);
        CustodyEngine.PayoutResult payout;
        if (r.verdict.rewardAmount() == 0) {
            payout = new CustodyEngine.PayoutResult(CustodyEngine.PayoutStatus.SETTLED, 0, 0, 0);
        } else if (p != null && p.isOnline() && !p.isDead()) {
            payout = custody.pay(p, r.record);
        } else {
            payout = new CustodyEngine.PayoutResult(CustodyEngine.PayoutStatus.HELD, 0, 0, r.verdict.rewardAmount());
        }
        r.paidAtReveal = payout.given();
        r.heldAtReveal = Math.max(0, payout.remaining());
        r.ownerSawReveal = p != null && p.isOnline();

        if (r.verdict.rewardAmount() == 0) {
            // Nothing is owed: the record can go now. The ledger entry is forgotten once it is gone.
            UUID id = r.id;
            UUID owner = r.playerId;
            journal.delete(id, () -> {
                Player online = Bukkit.getPlayer(owner);
                if (online != null) CustodyEngine.forget(custody.custodyOf(online, null), id);
            });
        }
        stats.record(new StatsBook.RitualSummary(r.playerId, r.playerName, r.machine.id(), r.record.itemKey(),
                r.verdict.inputAmount(), r.verdict.outcomeId(), r.verdict.tier(), r.verdict.rewardAmount(),
                r.jackpotVariant, r.fakeout != null, r.crowdAtReveal, r.tick, System.currentTimeMillis(), LocalTime.now(java.time.ZoneId.systemDefault()).getHour()));
        audit.log(AuditLog.Event.REVEAL, Map.of("ritual", r.id, "player", r.playerName, "outcome", r.verdict.outcomeId(),
                "reward", r.verdict.rewardAmount(), "paid", payout.given(), "held", Math.max(0, payout.remaining()),
                "crowd", r.crowdAtReveal, "variant", r.jackpotVariant == null ? "-" : r.jackpotVariant));
        log(Settings.LogLevel.INFO, r.playerName + " offered " + r.verdict.inputAmount() + "x " + r.record.itemKey() + " at "
                + r.machine.id() + ": " + r.verdict.outcomeId().toUpperCase(java.util.Locale.ROOT) + " (reward " + r.verdict.rewardAmount()
                + (payout.remaining() > 0 ? ", " + payout.remaining() + " held" : "") + ")");
        try {
            presentation.revealed(r, payout);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Reveal presentation of ritual " + r.record.shortId() + " failed (the payout is done)", e);
            health.raise(HealthMonitor.Source.PRESENTATION, "a reveal presentation failed: " + e);
        }
    }

    /** Ends the ritual (reveals first if needed) and frees the machine and the player. Idempotent. */
    public void finish(ActiveRitual r, String reason) {
        if (r.state == ActiveRitual.State.DONE || r.state == ActiveRitual.State.ABORTED) return;
        if (r.state == ActiveRitual.State.PREPARING) {
            abort(r, reason, false);
            return;
        }
        if (r.state == ActiveRitual.State.LIVE) {
            try {
                reveal(r);
            } catch (RuntimeException e) {
                // The record stays in the journal; the retry loop and the next join pay what is owed.
                r.state = ActiveRitual.State.REVEALED;
                logger.log(Level.SEVERE, "Payout of ritual " + r.record.shortId() + " (" + r.playerName + ") failed; its record is kept "
                        + "and the reward is delivered by the retry/recovery path", e);
                health.raise(HealthMonitor.Source.RECOVERY, "payout of ritual " + r.record.shortId() + " failed: " + e);
            }
        }
        try {
            presentation.cleanup(r);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Cleanup of ritual " + r.record.shortId() + " failed", e);
        }
        r.state = ActiveRitual.State.DONE;
        rituals.remove(r.id);
        byPlayer.remove(r.playerId, r);
        if (r.preview) {
            r.machine.release(r.id, 0, null);
            return;
        }
        Settings s = r.settings;
        r.machine.release(r.id, s.cooldowns().machineSeconds() * 1000L, r.verdict.outcomeId());
        long now = System.currentTimeMillis();
        if (playerCooldownUntil.size() > 256) playerCooldownUntil.values().removeIf(until -> until <= now);
        playerCooldownUntil.put(r.playerId, now + s.cooldowns().playerSeconds() * 1000L);
        completed++;
        audit.log(AuditLog.Event.TRANSACTION_COMMITTED, Map.of("ritual", r.id, "player", r.playerName, "reason", reason,
                "paid", r.paidAtReveal, "held", r.heldAtReveal));
        audit.log(AuditLog.Event.MACHINE_UNLOCKED, Map.of("machine", r.machine.id(), "ritual", r.id));
        Bukkit.getPluginManager().callEvent(new RitualCompleteEvent(r.view(), r.paidAtReveal, r.heldAtReveal));
    }

    /** Skips the remaining presentation: reveal (pay) now and finish. Used by admins, unloads and shutdown. */
    public void resolveNow(ActiveRitual r, String reason) {
        if (r.state == ActiveRitual.State.PREPARING) return; // cannot resolve before the record is durable
        Player p = Bukkit.getPlayer(r.playerId);
        boolean wasLive = r.state == ActiveRitual.State.LIVE;
        if (wasLive && !r.preview) {
            log(Settings.LogLevel.INFO, "Ritual " + r.record.shortId() + " of " + r.playerName + " resolved immediately (" + reason + ")");
        }
        finish(r, reason);
        if (wasLive && p != null && p.isOnline()) messages.send(p, "ritual.resolved-early");
    }

    // ------------------------------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------------------------------

    /**
     * Stops accepting offerings and settles every ritual: unrevealed ones are revealed (paid) immediately.
     * Rituals still writing their record have taken nothing; their records are deleted after the I/O drains.
     */
    public List<UUID> shutdown() {
        accepting = false;
        List<UUID> preparing = new ArrayList<>();
        for (ActiveRitual r : List.copyOf(rituals.values())) {
            if (r.state == ActiveRitual.State.PREPARING) {
                preparing.add(r.id);
                abort(r, "shutdown", false);
            } else {
                resolveNow(r, "shutdown");
            }
        }
        return preparing;
    }

    public void setAccepting(boolean accepting) {
        this.accepting = accepting;
    }

    // ------------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------------

    /** Cheap check for hot paths (movement events). */
    public boolean isIdle() {
        return rituals.isEmpty();
    }

    public Collection<ActiveRitual> active() {
        return List.copyOf(rituals.values());
    }

    public ActiveRitual byPlayer(UUID player) {
        return byPlayer.get(player);
    }

    public ActiveRitual byId(UUID id) {
        return rituals.get(id);
    }

    @Override
    public Set<UUID> unrevealedIds() {
        Set<UUID> out = new HashSet<>();
        for (ActiveRitual r : rituals.values()) if (!r.revealed() && !r.preview) out.add(r.id);
        return out;
    }

    @Override
    public Set<UUID> activeIds() {
        return Set.copyOf(rituals.keySet());
    }

    public long playerCooldownMillis(UUID player) {
        Long until = playerCooldownUntil.get(player);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            playerCooldownUntil.remove(player);
            return 0;
        }
        return left;
    }

    public void forgetPlayer(UUID player) {
        if (playerCooldownMillis(player) == 0) playerCooldownUntil.remove(player);
    }

    public long completed() {
        return completed;
    }

    public long aborted() {
        return aborted;
    }

    public JournalService journal() {
        return journal;
    }

    private void log(Settings.LogLevel level, String message) {
        Settings s = settings.get();
        Settings.LogLevel configured = s == null ? Settings.LogLevel.INFO : s.logging().level();
        if (configured.includes(level)) logger.info(message);
    }

    static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver num(String key, long v) {
        return Placeholder.unparsed(key, Long.toString(v));
    }
}
