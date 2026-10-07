package com.voidmachine.it;

import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.menu.OfferingMenu;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hostile timing: simultaneous players, repeated clicks, every click type on the menu. */
class ConcurrencyTest {

    private Harness h;

    @AfterEach
    void tearDown() {
        if (h == null) return;
        try {
            h.assertNoProblemsLogged();
        } finally {
            h.close();
        }
    }

    @Test
    void twoPlayersPressStartOnTheSameMachine() {
        h = new Harness();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer a = h.player("Ann", m);
        TestPlayer b = h.player("Ben", m);
        Harness.hold(a, Material.DIAMOND, 8);
        Harness.hold(b, Material.EMERALD, 8);
        h.interact(a, m);
        h.interact(b, m);
        h.openMenu(a);
        h.openMenu(b);
        Harness.messages(b);
        h.clickTop(a, 22);
        h.clickTop(b, 22);
        assertEquals(1, h.rt().rituals().active().size());
        List<String> said = Harness.messages(b);
        assertTrue(said.stream().anyMatch(s -> s.contains("Ann")), "B is told who occupies the machine: " + said);
        Harness.assertRendered(said);
        h.awaitNoRituals();
        assertEquals(16, Harness.count(a, Material.DIAMOND));
        assertEquals(8, Harness.count(b, Material.EMERALD), "nothing was taken from the second player");
    }

    @Test
    void repeatedStartClicksStartOneRitual() {
        h = new Harness();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer a = h.player("Ann", m);
        Harness.hold(a, Material.DIAMOND, 8);
        h.interact(a, m);
        h.openMenu(a);
        for (int i = 0; i < 20; i++) h.clickTop(a, 22);
        assertEquals(1, h.rt().rituals().active().size());
        h.awaitNoRituals();
        assertEquals(16, Harness.count(a, Material.DIAMOND));
    }

    @Test
    void interactSpamOpensOneMenuAndNeverThrows() {
        h = new Harness();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer a = h.player("Ann", m);
        Harness.hold(a, Material.DIAMOND, 8);
        for (int i = 0; i < 200; i++) h.interact(a, m);
        assertInstanceOf(OfferingMenu.class, a.getOpenInventory().getTopInventory().getHolder(false));
        h.clickTop(a, 22);
        for (int i = 0; i < 200; i++) h.interact(a, m);
        assertEquals(1, h.rt().rituals().active().size());
        h.awaitNoRituals();
        assertEquals(16, Harness.count(a, Material.DIAMOND));
    }

    @Test
    void everyClickOnTheMenuIsCancelledAndMovesNothing() {
        h = new Harness();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer a = h.player("Ann", m);
        Harness.hold(a, Material.DIAMOND, 8);
        a.getInventory().setItem(9, new ItemStack(Material.GOLD_INGOT, 3));
        h.interact(a, m);
        h.openMenu(a);
        ItemStack[] before = a.getInventory().getContents().clone();
        int topSize = a.getOpenInventory().getTopInventory().getSize();
        int total = topSize + 36;
        for (ClickType type : ClickType.values()) {
            for (int raw = 0; raw < total; raw++) {
                if (raw == 22) continue; // START
                InventoryClickEvent e = h.click(a, raw, type);
                assertTrue(e.isCancelled(), type + " on raw slot " + raw + " must be cancelled");
            }
        }
        InventoryDragEvent drag = new InventoryDragEvent(a.getOpenInventory(), null, new ItemStack(Material.DIAMOND),
                false, Map.of(13, new ItemStack(Material.DIAMOND)));
        h.server.getPluginManager().callEvent(drag);
        assertTrue(drag.isCancelled());
        for (int i = 0; i < before.length; i++) {
            ItemStack x = before[i];
            ItemStack y = a.getInventory().getContents()[i];
            assertEquals(x == null || x.isEmpty() ? null : x, y == null || y.isEmpty() ? null : y, "slot " + i);
        }
        assertTrue(h.rt().rituals().active().isEmpty(), "no click other than START begins a ritual");
    }

    @Test
    void tappingAnotherStackSelectsIt() {
        h = new Harness();
        Machine m = h.machine("m1", "t-double", 0, 64, 0);
        TestPlayer a = h.player("Ann", m);
        Harness.hold(a, Material.DIAMOND, 8);
        a.getInventory().setItem(9, new ItemStack(Material.GOLD_INGOT, 3));
        h.interact(a, m);
        h.openMenu(a);
        h.clickOwn(a, 9);
        h.clickTop(a, 22);
        h.awaitNoRituals();
        assertEquals(8, Harness.count(a, Material.DIAMOND));
        assertEquals(6, Harness.count(a, Material.GOLD_INGOT));
    }

    @Test
    void globalRitualLimitIsEnforced() {
        h = new Harness(c -> c.replace("max-active-rituals: 4", "max-active-rituals: 1"));
        Machine m1 = h.machine("m1", "t-double", 0, 64, 0);
        Machine m2 = h.machine("m2", "t-double", 40, 64, 0);
        TestPlayer a = h.player("Ann", m1);
        TestPlayer b = h.player("Ben", m2);
        Harness.hold(a, Material.DIAMOND, 8);
        Harness.hold(b, Material.DIAMOND, 8);
        h.offerHeld(a, m1);
        h.interact(b, m2);
        h.openMenu(b);
        h.clickTop(b, 22);
        assertEquals(1, h.rt().rituals().active().size());
        assertEquals(8, Harness.count(b, Material.DIAMOND));
        h.awaitNoRituals();
    }

    @Test
    void manySimultaneousRitualsAllSettleExactly() {
        h = new Harness(c -> c.replace("max-active-rituals: 4", "max-active-rituals: 8"));
        List<TestPlayer> players = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Machine m = h.machine("m" + i, i % 2 == 0 ? "t-double" : "t-loss", i * 24, 64, 0);
            TestPlayer p = h.player("P" + i, m);
            Harness.hold(p, Material.DIAMOND, 10 + i);
            players.add(p);
            // spectators near every machine
            h.player("S" + i, m);
        }
        for (int i = 0; i < 8; i++) h.offerHeld(players.get(i), h.rt().machines().byId("m" + i));
        assertEquals(8, h.rt().rituals().active().size());
        h.awaitNoRituals();
        for (int i = 0; i < 8; i++) {
            int expected = i % 2 == 0 ? 2 * (10 + i) : 0;
            assertEquals(expected, Harness.count(players.get(i), Material.DIAMOND), "player " + i);
        }
        assertEquals(0, h.displayEntities());
        assertFalse(h.rt().budget().deniedParticles() < 0);
    }
}
