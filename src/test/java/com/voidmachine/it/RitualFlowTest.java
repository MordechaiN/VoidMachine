package com.voidmachine.it;

import com.voidmachine.api.event.RitualCompleteEvent;
import com.voidmachine.api.event.RitualJackpotEvent;
import com.voidmachine.api.event.RitualRevealEvent;
import com.voidmachine.api.event.RitualStartEvent;
import com.voidmachine.core.health.HealthMonitor;
import com.voidmachine.core.ledger.PlayerLedger;
import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.ritual.ActiveRitual;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end rituals on the real plugin: menu → commit → capture → presentation → payout → proof. */
class RitualFlowTest {

    private Harness h;

    private Harness boot() {
        return boot(UnaryOperator.identity());
    }

    private Harness boot(UnaryOperator<String> edit) {
        h = new Harness(edit);
        return h;
    }

    @AfterEach
    void tearDown() {
        if (h == null) return;
        try {
            h.assertNoProblemsLogged();
        } finally {
            h.close();
        }
    }

    private static boolean ledgerEmpty(TestPlayer p) throws PlayerLedger.LedgerFormatException {
        return PlayerLedger.decode(p.ledger()).isEmpty();
    }

    @Test
    void startsHealthyWithBundledFiles() {
        boot();
        assertNotNull(h.rt().settings(), "bundled config must validate");
        assertNotNull(h.rt().book(), "bundled rituals.yml must validate");
        assertEquals(HealthMonitor.State.HEALTHY, h.rt().health().state(), () -> h.rt().health().problems().toString());
    }

    @Test
    void doubledRitualPaysOnceAndIsProvenOnRejoin() throws Exception {
        boot();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer p = h.player("Alice", m);
        Harness.hold(p, Material.DIAMOND, 16);

        h.offerHeld(p, m);
        assertEquals(1, h.rt().rituals().active().size());
        h.await(() -> Harness.count(p, Material.DIAMOND) == 0, "offering to be captured");
        assertNotNull(p.savedLedger(), "capture mark must be saved together with the inventory");
        assertEquals(1, h.rt().journal().size());

        h.awaitNoRituals();
        assertEquals(32, Harness.count(p, Material.DIAMOND));
        // The live ledger is never proof: the record stays until a fresh load shows the payout was saved.
        assertEquals(1, h.rt().journal().size());

        h.quit(p);
        h.join(p);
        h.settle();
        assertEquals(32, Harness.count(p, Material.DIAMOND), "no second payout");
        assertEquals(0, h.rt().journal().size(), "record finalized after the fresh load proved the payout");
        assertTrue(ledgerEmpty(p), "ledger entry forgotten: " + p.ledger());
        assertEquals(HealthMonitor.State.HEALTHY, h.rt().health().state(), () -> h.rt().health().problems().toString());
    }

    @Test
    void lossConsumesAndForgetsImmediately() throws Exception {
        boot();
        Machine m = h.machine("m1", "t-loss", 0, 64, 0);
        TestPlayer p = h.player("Bob", m);
        Harness.hold(p, Material.EMERALD, 10);
        h.offerHeld(p, m);
        h.awaitNoRituals();
        h.settle();
        assertEquals(0, Harness.count(p, Material.EMERALD));
        assertEquals(0, h.rt().journal().size(), "a zero reward needs no proof");
        assertTrue(ledgerEmpty(p));
        assertEquals(1, h.rt().stats().global().rituals());
    }

    @Test
    void titheReturnsHalfRoundedDown() {
        boot();
        Machine m = h.machine("m1", "t-tithe", 0, 64, 0);
        TestPlayer p = h.player("Cara", m);
        Harness.hold(p, Material.IRON_INGOT, 15);
        h.offerHeld(p, m);
        h.awaitNoRituals();
        assertEquals(7, Harness.count(p, Material.IRON_INGOT));
    }

    @Test
    void presetOffersPartOfTheStack() {
        boot();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer p = h.player("Dan", m);
        Harness.hold(p, Material.GOLD_INGOT, 16);
        h.interact(p, m);
        h.openMenu(p);
        h.clickTop(p, 10); // preset "one"
        h.clickTop(p, 22);
        h.awaitNoRituals();
        assertEquals(15 + 2, Harness.count(p, Material.GOLD_INGOT));
    }

    @Test
    void itemComponentsSurviveTheRitual() {
        boot();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer p = h.player("Eve", m);
        ItemStack special = new ItemStack(Material.PAPER, 4);
        special.editMeta(meta -> {
            meta.customName(net.kyori.adventure.text.Component.text("Contract"));
            meta.getPersistentDataContainer().set(new NamespacedKey("other", "tag"), PersistentDataType.INTEGER, 7);
        });
        p.getInventory().setItem(0, special);
        p.getInventory().setHeldItemSlot(0);
        h.offerHeld(p, m);
        h.awaitNoRituals();
        ItemStack back = p.getInventory().getItem(0);
        assertNotNull(back);
        assertEquals(8, back.getAmount());
        assertTrue(back.isSimilar(special), "name and custom data must be identical");
    }

    @Test
    void jackpotPicksAVariantAndAnnounces() {
        boot();
        Machine m = h.machine("m1", "t-jackpot", 0, 64, 0);
        TestPlayer p = h.player("Finn", m);
        TestPlayer far = h.player("Gil", m);
        far.teleport(m.location().clone().add(500, 0, 500));
        Harness.hold(p, Material.DIAMOND, 4);
        h.offerHeld(p, m);
        h.awaitNoRituals();
        assertEquals(20, Harness.count(p, Material.DIAMOND));
        assertTrue(h.world.lightningEffects > 0, "the jackpot reveal strikes (visual) lightning");
        assertTrue(h.fired(RitualJackpotEvent.class).anyMatch(e -> e.variant() != null && !e.variant().isBlank()));
        assertTrue(h.fired(RitualCompleteEvent.class).anyMatch(e -> e.paid() == 20 && e.held() == 0));
        assertFalse(Harness.messages(far).isEmpty(), "server-wide jackpot announcement must reach far players");
    }

    @Test
    void fullInventoryHoldsTheRestAndClaimPaysIt() {
        boot();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer p = h.player("Hana", m);
        for (int i = 1; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
        Harness.hold(p, Material.DIAMOND, 64);
        h.offerHeld(p, m);
        h.awaitNoRituals();
        assertEquals(64, Harness.count(p, Material.DIAMOND), "what fits is paid");
        assertEquals(1, h.rt().custody().owed(p).size());
        assertEquals(64, h.rt().custody().remaining(p, h.rt().custody().owed(p).getFirst()));

        Harness.messages(p);
        p.performCommand("vm claim");
        assertTrue(Harness.messages(p).stream().anyMatch(s -> s.contains("full")), "claim explains the full inventory");
        for (int i = 1; i < 3; i++) p.getInventory().setItem(i, null);
        p.performCommand("vm claim");
        assertEquals(128, Harness.count(p, Material.DIAMOND));
        assertTrue(h.rt().custody().owed(p).isEmpty());
    }

    @Test
    void dropOverflowLocksDropsToTheOwner() {
        boot(c -> c.replace("overflow: hold", "overflow: drop"));
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer p = h.player("Ivo", m);
        for (int i = 1; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
        p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        Harness.hold(p, Material.DIAMOND, 64);
        h.offerHeld(p, m);
        h.awaitNoRituals();
        List<Item> drops = h.world.getEntities().stream().filter(e -> e instanceof Item).map(e -> (Item) e).toList();
        assertEquals(64, Harness.count(p, Material.DIAMOND));
        assertEquals(64, drops.stream().mapToInt(i -> i.getItemStack().getAmount()).sum());
        for (Item drop : drops) {
            assertEquals(p.getUniqueId(), drop.getOwner());
            assertTrue(drop.isUnlimitedLifetime());
            assertTrue(drop.isInvulnerable());
        }
        assertTrue(h.rt().custody().owed(p).isEmpty(), "dropped rewards count as delivered");
    }

    @Test
    void failedDropStaysHeldInsteadOfVanishingOrDuplicating() {
        boot(c -> c.replace("overflow: hold", "overflow: drop"));
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer p = h.player("Ivy", m);
        for (int i = 1; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
        Harness.hold(p, Material.DIAMOND, 64);
        h.world.failDrops = true;
        h.offerHeld(p, m);
        h.awaitNoRituals();
        assertEquals(64, Harness.count(p, Material.DIAMOND), "what fit was paid");
        assertEquals(64, h.rt().custody().remaining(p, h.rt().custody().owed(p).getFirst()), "the failed drop stays owed");
        h.world.failDrops = false;
        h.problems.clear(); // the failed drop was logged, as it should be
        p.performCommand("vm claim");
        assertEquals(64, h.world.getEntities().stream().filter(e -> e instanceof Item).mapToInt(e -> ((Item) e).getItemStack().getAmount()).sum());
        assertEquals(64, Harness.count(p, Material.DIAMOND), "nothing paid twice");
        assertTrue(h.rt().custody().owed(p).isEmpty());
    }

    @Test
    void presentationCleansUpDisplaysAndBossBars() {
        boot();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer p = h.player("Jon", m);
        Harness.hold(p, Material.DIAMOND, 2);
        h.offerHeld(p, m);
        h.await(() -> {
            ActiveRitual r = h.rt().rituals().byPlayer(p.getUniqueId());
            return r != null && r.tick() > 40;
        }, "ritual to be under way");
        assertTrue(p.activeBossBars().iterator().hasNext(), "the owner sees the ritual bar");
        List<ItemDisplay> displays = h.world.getEntities().stream().filter(e -> e instanceof ItemDisplay).map(e -> (ItemDisplay) e).toList();
        assertFalse(displays.isEmpty(), "a display hovers over the machine");
        for (ItemDisplay d : displays) {
            assertFalse(d.isPersistent(), "displays are never saved with the chunk");
            assertTrue(d.getPersistentDataContainer().has(new NamespacedKey(h.plugin, "display")), "displays are tagged for orphan sweeps");
        }
        h.awaitNoRituals();
        assertEquals(0, h.displayEntities());
        assertFalse(p.activeBossBars().iterator().hasNext(), "bars are removed");
    }

    @Test
    void cancelledStartEventTakesNothing() {
        boot();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer p = h.player("Kai", m);
        h.server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void on(RitualStartEvent e) {
                e.setCancelled(true);
            }
        }, h.plugin);
        Harness.hold(p, Material.DIAMOND, 5);
        h.offerHeld(p, m);
        h.settle();
        assertTrue(h.rt().rituals().active().isEmpty());
        assertEquals(5, Harness.count(p, Material.DIAMOND));
        assertEquals(0, h.rt().journal().size());
    }

    @Test
    void revealEventCarriesTheSealedVerdict() {
        boot();
        Machine m = h.machine("m1", "t-tithe", 0, 64, 0);
        TestPlayer p = h.player("Lea", m);
        Harness.hold(p, Material.DIAMOND, 10);
        h.offerHeld(p, m);
        h.awaitNoRituals();
        assertTrue(h.fired(RitualRevealEvent.class).anyMatch(e -> e.ritual().outcomeId().equals("tithe") && e.ritual().rewardAmount() == 5));
    }

    @Test
    void adminPreviewTakesAndRecordsNothing() {
        boot();
        Machine m = h.machine("m1", "t-loss", 0, 64, 0);
        TestPlayer admin = h.player("Mia", m);
        admin.setOp(true);
        Harness.hold(admin, Material.DIAMOND, 3);
        admin.performCommand("vm admin preview jackpot");
        assertEquals(1, h.rt().rituals().active().size());
        h.awaitNoRituals();
        h.settle();
        assertEquals(3, Harness.count(admin, Material.DIAMOND));
        assertEquals(0, h.rt().journal().size());
        assertEquals(0, h.rt().stats().global().rituals());
        assertEquals(0, h.displayEntities());
    }
}
