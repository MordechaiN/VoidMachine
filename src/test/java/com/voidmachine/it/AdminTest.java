package com.voidmachine.it;

import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.journal.RecordKind;
import com.voidmachine.paper.item.ItemCodec;
import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.ritual.ActiveRitual;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Admin tools: permissions, confirmations, record decisions, diagnostics. */
class AdminTest {

    private static final Pattern CONFIRM = Pattern.compile("/vm admin confirm (\\S+)");

    private Harness h;
    private Machine m;
    private TestPlayer admin;
    private TestPlayer player;

    @BeforeEach
    void setUp() {
        h = new Harness();
        m = h.machine("m1", "t-double", 0, 64, 0);
        admin = h.player("Root", m);
        admin.setOp(true);
        player = h.player("Pat", m);
    }

    @AfterEach
    void tearDown() {
        try {
            h.assertNoProblemsLogged();
        } finally {
            h.close();
        }
    }

    private String said(TestPlayer p, String command) {
        Harness.messages(p);
        p.performCommand(command);
        return String.join("\n", Harness.messages(p));
    }

    private static String code(String output) {
        Matcher mt = CONFIRM.matcher(output);
        if (!mt.find()) fail("no confirmation code in: " + output);
        return mt.group(1);
    }

    private JournalRecord writeRecord(JournalRecord r) {
        h.rt().journal().create(r, ok -> {
            if (!ok) fail("journal write failed");
        });
        h.await(() -> h.rt().journal().get(r.ritualId()) != null, "record indexed");
        return r;
    }

    private JournalRecord review(int amount) {
        ItemStack t = new ItemStack(Material.DIAMOND);
        return writeRecord(JournalRecord.review(UUID.randomUUID(), player.getUniqueId(), player.getName(), System.currentTimeMillis(),
                ItemCodec.key(t), ItemCodec.encodeTemplate(t), amount, "v1-delivering"));
    }

    @Test
    void playersCannotUseAdminCommands() {
        String out = said(player, "vm admin list");
        assertFalse(out.contains("m1"), out);
        assertFalse(player.performCommand("vm admin remove m1") && h.rt().machines().byId("m1") == null);
        assertNotNull(h.rt().machines().byId("m1"));
        List<String> completions = h.server.getCommandMap().tabComplete(player, "vm ");
        assertFalse(completions != null && completions.contains("admin"), "admin is not suggested to players");
    }

    @Test
    void removeNeedsConfirmationFromTheSameAdmin() {
        String out = said(admin, "vm admin remove m1");
        assertNotNull(h.rt().machines().byId("m1"), "nothing happens before confirmation");
        String code = code(out);
        player.setOp(false);
        said(player, "vm admin confirm " + code);
        assertNotNull(h.rt().machines().byId("m1"), "another sender cannot confirm");
        said(admin, "vm admin confirm wrong");
        assertNotNull(h.rt().machines().byId("m1"));
        said(admin, "vm admin confirm " + code);
        assertNull(h.rt().machines().byId("m1"));
        said(admin, "vm admin confirm " + code);
        assertNull(h.rt().machines().byId("m1"), "a code works once");
    }

    @Test
    void removeRefusesABusyMachineWithoutForce() {
        Harness.hold(player, Material.DIAMOND, 4);
        h.offerHeld(player, m);
        h.await(() -> h.rt().rituals().byPlayer(player.getUniqueId()).state() == ActiveRitual.State.LIVE, "capture");
        String out = said(admin, "vm admin remove m1");
        assertTrue(out.contains("--force"), out);
        assertFalse(CONFIRM.matcher(out).find());
        String code = code(said(admin, "vm admin remove m1 --force"));
        said(admin, "vm admin confirm " + code);
        assertNull(h.rt().machines().byId("m1"));
        assertEquals(8, Harness.count(player, Material.DIAMOND), "the sealed verdict is paid when the machine is removed");
    }

    @Test
    void reviewRecordsAreNeverPaidAutomatically() {
        JournalRecord r = review(5);
        h.quit(player);
        h.join(player);
        h.settle();
        player.performCommand("vm claim");
        assertEquals(0, Harness.count(player, Material.DIAMOND));
        assertEquals(RecordKind.REVIEW, h.rt().journal().get(r.ritualId()).kind());
        String pending = said(admin, "vm admin pending");
        assertTrue(pending.contains(r.shortId()) && pending.contains("REVIEW"), pending);
    }

    @Test
    void refundOfAReviewPaysTheOfferingBack() {
        JournalRecord r = review(5);
        String out = said(admin, "vm admin refund " + r.shortId());
        assertEquals(RecordKind.REVIEW, h.rt().journal().get(r.ritualId()).kind(), "nothing before confirmation");
        said(admin, "vm admin confirm " + code(out));
        h.await(() -> Harness.count(player, Material.DIAMOND) == 5, "refund delivered to the online player");
        JournalRecord now = h.rt().journal().get(r.ritualId());
        assertEquals(RecordKind.CLAIM, now.kind());
        assertEquals(2, now.revision());
        h.quit(player);
        h.join(player);
        h.settle();
        assertEquals(5, Harness.count(player, Material.DIAMOND), "paid once");
        assertNull(h.rt().journal().get(r.ritualId()), "finalized after the fresh load");
    }

    @Test
    void releaseDeletesARecordAfterConfirmation() {
        JournalRecord r = review(5);
        said(admin, "vm admin confirm " + code(said(admin, "vm admin release " + r.shortId())));
        h.settle();
        assertNull(h.rt().journal().get(r.ritualId()));
    }

    @Test
    void staleConfirmationChangesNothing() {
        JournalRecord r = review(5);
        TestPlayer other = h.player("Root2", m);
        other.setOp(true);
        String refundCode = code(said(admin, "vm admin refund " + r.shortId()));
        said(other, "vm admin confirm " + code(said(other, "vm admin release " + r.shortId())));
        h.settle();
        String out = said(admin, "vm admin confirm " + refundCode);
        assertTrue(out.contains("no longer exists"), out);
        h.settle();
        assertNull(h.rt().journal().get(r.ritualId()));
        assertEquals(com.voidmachine.core.health.HealthMonitor.State.HEALTHY, h.rt().health().state(), "a stale confirmation is not a storage error");
        assertEquals(0, Harness.count(player, Material.DIAMOND));
    }

    @Test
    void resolveEndsARunningRitualWithItsSealedVerdict() {
        Harness.hold(player, Material.DIAMOND, 4);
        h.offerHeld(player, m);
        h.await(() -> h.rt().rituals().byPlayer(player.getUniqueId()).state() == ActiveRitual.State.LIVE, "capture");
        String id = h.rt().rituals().byPlayer(player.getUniqueId()).record().shortId();
        said(admin, "vm admin resolve " + id);
        assertTrue(h.rt().rituals().active().isEmpty());
        assertEquals(8, Harness.count(player, Material.DIAMOND));
    }

    @Test
    void readOnlyToolsWork() {
        assertTrue(said(admin, "vm admin list").contains("m1"));
        assertTrue(said(admin, "vm admin status m1").contains("m1"));
        String diag = said(admin, "vm admin diagnostics");
        assertTrue(diag.contains("Health: HEALTHY"), diag);
        String odds = said(admin, "vm admin odds default");
        assertTrue(odds.contains("jackpot"), odds);
        assertTrue(said(admin, "vm admin health").contains("HEALTHY"));
        assertTrue(said(admin, "vm admin rituals").length() > 0);
    }

    @Test
    void disabledMachineRefusesOfferings() {
        said(admin, "vm admin disable m1");
        Harness.hold(player, Material.DIAMOND, 4);
        h.interact(player, m);
        h.settle();
        assertTrue(h.rt().rituals().active().isEmpty());
        assertEquals(4, Harness.count(player, Material.DIAMOND));
        said(admin, "vm admin enable m1");
        h.offerHeld(player, m);
        h.awaitNoRituals();
        assertEquals(8, Harness.count(player, Material.DIAMOND));
    }

    @Test
    void consoleCanRunAdminCommands() {
        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "vm admin diagnostics"));
        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "vm admin pending"));
    }
}
