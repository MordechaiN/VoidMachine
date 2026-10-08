package com.voidmachine.core;

import com.voidmachine.core.budget.EffectBudget;
import com.voidmachine.core.health.HealthMonitor;
import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.spectator.CrowdModel;
import com.voidmachine.core.spectator.SpectatorTier;
import com.voidmachine.core.stats.StatsBook;
import com.voidmachine.core.stats.StatsStore;
import com.voidmachine.core.text.BidiText;
import com.voidmachine.core.util.Ids;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiscCoreTest {

    @TempDir
    Path dir;

    @Test
    void healthFailsClosedOnStorageAndConfig() {
        HealthMonitor h = new HealthMonitor(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        assertTrue(h.acceptsRituals());
        h.raise(HealthMonitor.Source.AUDIT, "disk");
        assertEquals(HealthMonitor.State.DEGRADED, h.state());
        assertTrue(h.acceptsRituals());
        h.raise(HealthMonitor.Source.QUARANTINE, "1 record");
        assertEquals(HealthMonitor.State.RECOVERY_REQUIRED, h.state());
        assertTrue(h.acceptsRituals());
        h.raise(HealthMonitor.Source.STORAGE, "read-only");
        assertEquals(HealthMonitor.State.STORAGE_ERROR, h.state());
        assertFalse(h.acceptsRituals());
        h.clear(HealthMonitor.Source.STORAGE);
        h.raise(HealthMonitor.Source.CONFIG, "bad weights");
        assertFalse(h.acceptsRituals());
        assertEquals(4, h.recentErrors().size());
    }

    @Test
    void budgetSharesFairlyAndNeverExceedsCaps() {
        EffectBudget b = new EffectBudget(100, 20);
        b.beginTick(4);
        EffectBudget.Allowance a1 = b.allowance();
        EffectBudget.Allowance a2 = b.allowance();
        assertEquals(25, a1.particles(80, 1), "a ritual gets at most its fair share");
        assertEquals(0, a1.particles(10, 1), "share exhausted");
        assertEquals(25, a2.particles(30, 2));
        assertTrue(a2.sound(3));
        assertFalse(a2.sound(1), "packet share (5) exhausted: 2 + 3 used");
        b.beginTick(1);
        EffectBudget.Allowance solo = b.allowance();
        assertEquals(100, solo.particles(500, 1));
        assertEquals(0, solo.particles(1, 1));
        assertTrue(b.deniedParticles() > 0);
    }

    @Test
    void crowdIntensityRisesAndCaps() {
        CrowdModel m = new CrowdModel(2, 1.6, 6);
        assertEquals(1.0, m.intensity(0));
        assertEquals(1.0, m.intensity(1));
        assertTrue(m.intensity(2) > 1.0);
        assertTrue(m.intensity(4) > m.intensity(2));
        assertEquals(1.6, m.intensity(6), 1e-9);
        assertEquals(1.6, m.intensity(60), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> new CrowdModel(0, 1.5, 3));
    }

    @Test
    void spectatorTiers() {
        SpectatorTier.Radii r = new SpectatorTier.Radii(6, 14, 28);
        assertEquals(SpectatorTier.INNER, r.classify(25));
        assertEquals(SpectatorTier.NEAR, r.classify(100));
        assertEquals(SpectatorTier.FAR, r.classify(700));
        assertEquals(SpectatorTier.NONE, r.classify(900));
        assertTrue(SpectatorTier.NEAR.hears(SpectatorTier.Audience.NEAR));
        assertFalse(SpectatorTier.FAR.hears(SpectatorTier.Audience.NEAR));
        assertTrue(SpectatorTier.OWNER.hears(SpectatorTier.Audience.OWNER));
        assertThrows(IllegalArgumentException.class, () -> new SpectatorTier.Radii(10, 5, 28));
    }

    @Test
    void bidiReordersHebrewForLeftToRightRenderers() {
        assertEquals("םולש", BidiText.toVisual("שלום", true));
        assertEquals("םלוע םולש", BidiText.toVisual("שלום עולם", true));
        // Numbers keep their internal order inside a right-to-left sentence.
        assertEquals("םימולהי 64 תלביק", BidiText.toVisual("קיבלת 64 יהלומים", true));
        // Brackets are mirrored inside right-to-left runs.
        assertEquals("(םולש)", BidiText.toVisual("(שלום)", true));
        // Pure left-to-right text is untouched.
        assertEquals("Hello 64", BidiText.toVisual("Hello 64", true));
    }

    @Test
    void bidiPreservesStylesAndAtoms() {
        Component c = Component.text("קיבלת ", NamedTextColor.GRAY)
                .append(Component.text("64", NamedTextColor.WHITE))
                .append(Component.translatable("item.minecraft.diamond", NamedTextColor.AQUA));
        Component visual = BidiText.toVisual(c, true);
        String plain = PlainTextComponentSerializer.plainText().serialize(visual);
        assertTrue(plain.contains("תלביק"), plain);
        assertTrue(plain.contains("64"), plain);
        assertTrue(plain.contains("item.minecraft.diamond") || plain.contains("Diamond"), "translatable kept as an atom: " + plain);
        assertTrue(plain.endsWith("תלביק"), "right-to-left sentence starts at the right edge: " + plain);
        Component latin = Component.text("Hello");
        assertSame(latin, BidiText.toVisual(latin, true));
    }

    @Test
    void statsRecordAndPersist() throws Exception {
        StatsBook book = new StatsBook();
        UUID p = UUID.randomUUID();
        book.record(new StatsBook.RitualSummary(p, "Steve", "altar", "minecraft:diamond", 64, "jackpot",
                OutcomeTier.JACKPOT, 320, "black_star", true, 5, 300, 1_000L, 21));
        book.record(new StatsBook.RitualSummary(p, "Steve", "altar", "minecraft:dirt", 10, "consumed",
                OutcomeTier.LOSS, 0, null, false, 0, 200, 2_000L, 21));
        assertEquals(2, book.global().rituals());
        assertEquals(74, book.global().itemsOffered());
        assertEquals(320, book.global().biggestJackpot().orElseThrow().reward());
        assertEquals(21, book.global().busiestHour());
        assertEquals(1, book.player(p).orElseThrow().jackpots());

        StatsStore store = new StatsStore(dir.resolve("stats.json"));
        store.save(book.copy());
        StatsBook back = store.load();
        assertEquals(2, back.global().rituals());
        assertEquals("altar", back.player(p).orElseThrow().favoriteMachine().orElseThrow());
        assertEquals(1L, back.global().jackpotVariants().get("black_star"));
    }

    @Test
    void corruptStatsAreMovedAsideNotOverwritten() throws Exception {
        Path f = dir.resolve("stats.json");
        Files.writeString(f, "{not json");
        StatsStore store = new StatsStore(f);
        assertThrows(java.io.IOException.class, store::load);
        assertFalse(Files.exists(f));
        try (var s = Files.list(dir)) {
            assertTrue(s.anyMatch(x -> x.getFileName().toString().startsWith("stats.json.corrupt-")));
        }
    }

    @Test
    void ids() {
        assertTrue(Ids.isValid("main_altar-2"));
        assertFalse(Ids.isValid("Main"));
        assertFalse(Ids.isValid("a.b"));
        assertFalse(Ids.isValid("../x"));
        assertFalse(Ids.isValid(""));
        assertEquals("main_altar", Ids.sanitize("Main Altar"));
        assertEquals("a_b", Ids.sanitize("a.b"));
        assertEquals(null, Ids.sanitize("!!!"));
    }
}
