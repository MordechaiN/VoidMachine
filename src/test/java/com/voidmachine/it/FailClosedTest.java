package com.voidmachine.it;

import com.voidmachine.core.health.HealthMonitor;
import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.ledger.PlayerLedger;
import com.voidmachine.paper.item.ItemCodec;
import com.voidmachine.paper.machine.Machine;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When something is wrong, VoidMachine refuses new offerings, keeps everything it holds, and says why. */
class FailClosedTest {

    private Harness h;

    @AfterEach
    void tearDown() {
        if (h != null) h.close();
    }

    private void claimFor(TestPlayer p, int amount) {
        ItemStack t = new ItemStack(Material.DIAMOND);
        JournalRecord r = JournalRecord.claim(UUID.randomUUID(), p.getUniqueId(), p.getName(), System.currentTimeMillis(),
                ItemCodec.key(t), ItemCodec.encodeTemplate(t), amount, "test");
        h.rt().journal().create(r, ok -> { });
        h.await(() -> h.rt().journal().get(r.ritualId()) != null, "claim written");
    }

    @Test
    void invalidConfigRefusesOfferingsButStillPaysWhatIsOwed() throws Exception {
        h = new Harness(c -> c.replace("max-amount: 64", "max-amount: 0"));
        assertNull(h.rt().settings());
        assertEquals(HealthMonitor.State.CONFIG_INVALID, h.rt().health().state());
        assertTrue(h.problems.stream().anyMatch(r -> r.getMessage().contains("max-amount")), "the console names the bad key");
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer p = h.player("Ola", m);
        Harness.hold(p, Material.EMERALD, 4);
        h.interact(p, m);
        assertFalse(Harness.hasMenuOpen(p));
        assertEquals(4, Harness.count(p, Material.EMERALD));
        Harness.assertRendered(Harness.messages(p));

        claimFor(p, 3);
        h.quit(p);
        h.join(p);
        h.settle();
        assertEquals(3, Harness.count(p, Material.DIAMOND), "recovery works without a valid configuration");

        Path config = h.dataDir().resolve("config.yml");
        Files.writeString(config, Files.readString(config).replace("max-amount: 0", "max-amount: 64"));
        p.setOp(true);
        p.performCommand("vm admin reload");
        assertNotNull(h.rt().settings());
        assertEquals(HealthMonitor.State.HEALTHY, h.rt().health().state());
        h.offerHeld(p, m);
        h.awaitNoRituals();
        assertEquals(8, Harness.count(p, Material.EMERALD));
    }

    @Test
    void badReloadKeepsThePreviousConfiguration() throws Exception {
        h = new Harness();
        var before = h.rt().settings();
        Path config = h.dataDir().resolve("config.yml");
        Files.writeString(config, Files.readString(config).replace("max-reward-amount: 320", "max-reward-amount: lots"));
        h.server.dispatchCommand(h.server.getConsoleSender(), "vm admin reload");
        assertTrue(h.rt().settings() == before, "previous settings stay active");
        assertEquals(HealthMonitor.State.HEALTHY, h.rt().health().state());
    }

    @Test
    void brokenStorageRefusesOfferingsAndTakesNothing() throws Exception {
        h = new Harness();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer p = h.player("Pia", m);
        Harness.hold(p, Material.DIAMOND, 6);
        Path journal = h.dataDir().resolve("journal");
        Path aside = h.dataDir().resolve("journal-aside");
        Files.move(journal, aside);
        Files.writeString(journal, "not a directory");

        h.offerHeld(p, m);
        h.settle();
        assertTrue(h.rt().rituals().active().isEmpty());
        assertEquals(6, Harness.count(p, Material.DIAMOND), "nothing is taken without a durable record");
        assertEquals(HealthMonitor.State.STORAGE_ERROR, h.rt().health().state());
        assertTrue(Harness.messages(p).stream().anyMatch(s -> s.contains("Nothing was taken")));

        h.interact(p, m);
        assertFalse(Harness.hasMenuOpen(p), "no menu while storage is broken");

        Files.delete(journal);
        Files.move(aside, journal);
        p.setOp(true);
        p.performCommand("vm admin health recheck");
        h.settle();
        assertEquals(HealthMonitor.State.HEALTHY, h.rt().health().state());
        h.offerHeld(p, m);
        h.awaitNoRituals();
        assertEquals(12, Harness.count(p, Material.DIAMOND));
    }

    @Test
    void corruptRecordIsQuarantinedNotDeleted() throws Exception {
        h = new Harness();
        Path bad = h.dataDir().resolve("journal").resolve(UUID.randomUUID() + ".vmj");
        Files.createDirectories(bad.getParent());
        Files.writeString(bad, "VMJ2 00000000\n{\"truncated\":");
        h.restartGracefully();
        assertFalse(Files.exists(bad));
        Path quarantine = h.dataDir().resolve("journal").resolve("quarantine");
        try (Stream<Path> files = Files.list(quarantine)) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith(bad.getFileName().toString())), "kept for an admin");
        }
        assertEquals(HealthMonitor.State.RECOVERY_REQUIRED, h.rt().health().state());
        assertTrue(h.rt().health().acceptsRituals(), "one bad record does not stop everyone else");
    }

    @Test
    void unreadableLedgerIsNeverOverwritten() {
        h = new Harness();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer p = new TestPlayer(h.server, "Quinn", h.ledgerKey);
        p.getPersistentDataContainer().set(h.ledgerKey, PersistentDataType.STRING, "garbage");
        p.persist();
        h.player(p, m);
        h.settle();
        assertTrue(Harness.messages(p).stream().anyMatch(s -> s.contains("admin has been notified")));
        Harness.hold(p, Material.DIAMOND, 5);
        h.offerHeld(p, m);
        h.settle();
        assertTrue(h.rt().rituals().active().isEmpty());
        assertEquals(5, Harness.count(p, Material.DIAMOND));
        assertEquals("garbage", p.ledger(), "evidence is left untouched");
        assertEquals(0, h.rt().journal().size());
    }

    @Test
    void owedEntryWithoutRecordIsKeptAsEvidence() throws Exception {
        h = new Harness();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer p = new TestPlayer(h.server, "Rae", h.ledgerKey);
        PlayerLedger ledger = PlayerLedger.empty();
        UUID lost = UUID.randomUUID();
        ledger.markCaptured(lost, 10);
        p.getPersistentDataContainer().set(h.ledgerKey, PersistentDataType.STRING, ledger.encode());
        p.persist();
        h.player(p, m);
        h.settle();
        assertTrue(PlayerLedger.decode(p.ledger()).contains(lost));
        assertEquals(HealthMonitor.State.RECOVERY_REQUIRED, h.rt().health().state());
        assertTrue(h.problems.stream().anyMatch(r -> r.getMessage().contains("journal record is missing")));
    }
}
