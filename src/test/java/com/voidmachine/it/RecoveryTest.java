package com.voidmachine.it;

import com.voidmachine.core.ledger.PlayerLedger;
import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.ritual.ActiveRitual;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interruptions of every kind. The invariant in each case: the player ends with exactly
 * {@code before - offered + reward} items — never less, never more.
 */
class RecoveryTest {

    private Harness h;
    private Machine m;
    private TestPlayer p;

    @BeforeEach
    void setUp() {
        h = new Harness();
        m = h.machine("m1", "t-double", 0, 64, 0);
        p = h.player("Ada", m);
        Harness.hold(p, Material.DIAMOND, 16);
        p.persist(); // an autosave: the diamonds are on disk before the ritual
    }

    @AfterEach
    void tearDown() {
        try {
            h.assertNoProblemsLogged();
        } finally {
            h.close();
        }
    }

    private void startAndAwaitCapture() {
        h.offerHeld(p, m);
        h.await(() -> {
            ActiveRitual r = h.rt().rituals().byPlayer(p.getUniqueId());
            return r != null && r.state() == ActiveRitual.State.LIVE;
        }, "capture");
        assertEquals(0, Harness.count(p, Material.DIAMOND));
    }

    private void assertFinalizedAfterNextJoin() throws PlayerLedger.LedgerFormatException {
        h.quit(p);
        h.join(p);
        h.settle();
        assertEquals(0, h.rt().journal().size(), "record finalized once a fresh load proves the payout");
        assertTrue(PlayerLedger.decode(p.ledger()).isEmpty());
    }

    @Test
    void quitDuringRitualIsPaidOnReturn() throws Exception {
        startAndAwaitCapture();
        h.quit(p);
        h.awaitNoRituals();
        assertEquals(1, h.rt().journal().size(), "the verdict waits in the journal");
        h.join(p);
        h.settle();
        assertEquals(32, Harness.count(p, Material.DIAMOND));
        assertFinalizedAfterNextJoin();
        assertEquals(32, Harness.count(p, Material.DIAMOND));
    }

    @Test
    void deathDuringRitualPaysAfterRespawn() {
        startAndAwaitCapture();
        p.setHealth(0);
        assertTrue(p.isDead());
        h.awaitNoRituals();
        assertEquals(0, Harness.count(p, Material.DIAMOND), "nothing is paid into a dead player's inventory");
        p.respawn();
        h.ticks(3);
        assertEquals(32, Harness.count(p, Material.DIAMOND));
    }

    @Test
    void crashAfterCaptureIsPaidAtRestart() throws Exception {
        startAndAwaitCapture();
        h.crashAndRestart();
        h.settle();
        assertEquals(32, Harness.count(p, Material.DIAMOND));
        List<String> said = Harness.messages(p);
        assertTrue(said.stream().anyMatch(s -> s.contains("returned what it was holding")), said::toString);
        assertFinalizedAfterNextJoin();
        assertEquals(32, Harness.count(p, Material.DIAMOND));
    }

    @Test
    void crashAfterSavedPayoutDoesNotPayTwice() throws Exception {
        startAndAwaitCapture();
        h.awaitNoRituals();
        assertEquals(32, Harness.count(p, Material.DIAMOND));
        h.crashAndRestart();
        h.settle();
        assertEquals(32, Harness.count(p, Material.DIAMOND));
        assertEquals(0, h.rt().journal().size());
    }

    @Test
    void crashAfterUnsavedPayoutPaysAgainExactlyOnce() throws Exception {
        startAndAwaitCapture();
        p.saveSilentlyFails = true; // the payout reaches the inventory but never the disk
        h.awaitNoRituals();
        assertEquals(32, Harness.count(p, Material.DIAMOND));
        p.saveSilentlyFails = false;
        h.crashAndRestart();
        h.settle();
        assertEquals(32, Harness.count(p, Material.DIAMOND), "restored to the saved state, then paid once");
        assertFinalizedAfterNextJoin();
    }

    @Test
    void crashBeforeCaptureGivesBackNothingBecauseNothingWasTaken() {
        h.offerHeld(p, m);
        // The record is durable (the I/O drains in crashAndRestart) but the capture never ran.
        h.crashAndRestart();
        h.settle();
        assertEquals(16, Harness.count(p, Material.DIAMOND));
        assertEquals(0, h.rt().journal().size());
        assertTrue(Harness.messages(p).stream().anyMatch(s -> s.contains("never taken")));
    }

    @Test
    void gracefulShutdownMidRitualPaysBeforeStopping() throws Exception {
        startAndAwaitCapture();
        h.restartGracefully();
        h.settle();
        assertEquals(32, Harness.count(p, Material.DIAMOND));
        assertEquals(0, h.displayEntities());
        assertTrue(h.rt().rituals().active().isEmpty());
        assertFinalizedAfterNextJoin();
    }

    @Test
    void offeringMovedBeforeCaptureAbortsWithoutTakingAnything() {
        h.offerHeld(p, m);
        // Same tick as START, before the durable write returns: the player moves the stack.
        ItemStack stack = p.getInventory().getItem(0);
        p.getInventory().setItem(0, null);
        p.getInventory().setItem(5, stack);
        h.settle();
        assertTrue(h.rt().rituals().active().isEmpty());
        assertEquals(16, Harness.count(p, Material.DIAMOND));
        assertEquals(0, h.rt().journal().size());
        assertTrue(!m.isBusy(), "machine released");
    }

    @Test
    void quitBeforeCaptureAbortsWithoutTakingAnything() {
        h.offerHeld(p, m);
        h.quit(p);
        h.settle();
        assertTrue(h.rt().rituals().active().isEmpty());
        assertEquals(0, h.rt().journal().size());
        h.join(p);
        h.settle();
        assertEquals(16, Harness.count(p, Material.DIAMOND));
    }

    @Test
    void chunkUnloadResolvesTheRitualImmediately() {
        startAndAwaitCapture();
        Chunk chunk = h.world.getChunkAt(0, 0);
        h.server.getPluginManager().callEvent(new ChunkUnloadEvent(chunk, true));
        assertTrue(h.rt().rituals().active().isEmpty());
        assertEquals(32, Harness.count(p, Material.DIAMOND));
        assertEquals(0, h.displayEntities());
    }

    @Test
    void reloadDuringRitualKeepsItsSettings() {
        startAndAwaitCapture();
        p.setOp(true);
        p.performCommand("vm admin reload");
        h.awaitNoRituals();
        assertEquals(32, Harness.count(p, Material.DIAMOND));
    }
}
