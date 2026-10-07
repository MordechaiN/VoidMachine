package com.voidmachine.it;

import com.voidmachine.core.text.BidiText;
import com.voidmachine.paper.machine.Machine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-player language, Hebrew by default, and pre-ordered Hebrew for Bedrock clients only. */
class LocalizationTest {

    private Harness h;
    private Machine m;

    @BeforeEach
    void setUp() {
        h = new Harness();
        m = h.machine("m1", "t-double", 0, 64, 0);
    }

    @AfterEach
    void tearDown() {
        try {
            h.assertNoProblemsLogged();
        } finally {
            h.close();
        }
    }

    private String claimNothing(TestPlayer p) {
        Harness.messages(p);
        p.performCommand("vm claim");
        List<String> said = Harness.messages(p);
        assertEquals(1, said.size(), said::toString);
        Harness.assertRendered(said);
        return said.getFirst();
    }

    private static boolean hasHebrew(String s) {
        return s.codePoints().anyMatch(c -> c >= 0x0590 && c <= 0x05FF);
    }

    @Test
    void englishClientGetsEnglish() {
        TestPlayer p = h.player("Ann", m);
        p.setLocale(Locale.US);
        String text = claimNothing(p);
        assertTrue(text.contains("holds nothing"), text);
    }

    @Test
    void hebrewClientGetsHebrewInLogicalOrder() {
        TestPlayer p = h.player("Dov", m);
        p.setLocale(Locale.forLanguageTag("he-IL"));
        String text = claimNothing(p);
        assertTrue(hasHebrew(text), text);
    }

    @Test
    void unknownLanguageFallsBackToTheDefaultHebrew() {
        TestPlayer p = h.player("Fay", m);
        p.setLocale(Locale.FRANCE);
        assertTrue(hasHebrew(claimNothing(p)));
    }

    @Test
    void bedrockHebrewIsPreOrderedForLeftToRightRendering() {
        TestPlayer java = h.player("Gal", m);
        java.setLocale(Locale.forLanguageTag("he-IL"));
        // Floodgate gives Bedrock players UUIDs with a zero upper half; without Floodgate that is the heuristic.
        TestPlayer bedrock = h.player(new TestPlayer(h.server, ".Gal", new UUID(0L, 0x1234L), h.ledgerKey), m);
        bedrock.setLocale(Locale.forLanguageTag("he-IL"));
        String logical = claimNothing(java);
        String visual = claimNothing(bedrock);
        assertNotEquals(logical, visual);
        assertEquals(BidiText.toVisual(logical, true), visual);
    }
}
