package com.voidmachine.core.property;

import com.voidmachine.core.journal.JournalCodec;
import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.ledger.PlayerLedger;
import com.voidmachine.core.outcome.Multiplier;
import com.voidmachine.core.outcome.OutcomeDefinition;
import com.voidmachine.core.outcome.OutcomeEngine;
import com.voidmachine.core.outcome.OutcomeTable;
import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.outcome.RandomSource;
import com.voidmachine.core.outcome.Verdict;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seeded randomized properties over the pure core (thousands of generated cases per property). */
class PropertyTest {

    private static final SplittableRandom SEEDS = new SplittableRandom(0x5EED);

    private static String randomText(SplittableRandom r, int max) {
        StringBuilder sb = new StringBuilder();
        int n = r.nextInt(max + 1);
        String alphabet = "abcXYZ019 _-;:=/\"\\{}[]אבגדה✦\u200f";
        for (int i = 0; i < n; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static JournalRecord randomRecord(SplittableRandom r) {
        byte[] template = new byte[1 + r.nextInt(400)];
        for (int i = 0; i < template.length; i++) template[i] = (byte) r.nextInt(256);
        int input = 1 + r.nextInt(99);
        int den = 1 + r.nextInt(5);
        Multiplier m = new Multiplier(r.nextInt(Multiplier.MAX_FACTOR * den + 1), den);
        OutcomeTier tier = m.isZero() ? OutcomeTier.LOSS : m.compareToOne() < 0 ? OutcomeTier.PARTIAL
                : m.isOne() ? OutcomeTier.NEUTRAL : OutcomeTier.WIN;
        int reward = (int) Math.min(5000, m.apply(input));
        Verdict v = new Verdict("o" + r.nextInt(1000), tier, m, input, reward, reward == 5000);
        return JournalRecord.forRitual(UUID.randomUUID(), UUID.randomUUID(), randomText(r, 16), "m" + r.nextInt(99),
                "world:" + r.nextInt() + ":64:" + r.nextInt(), "p" + r.nextInt(9), r.nextLong(Long.MAX_VALUE), "minecraft:x", template, v);
    }

    @Test
    void journalCodecRoundTripsAndDetectsEverySingleBitFlip() throws Exception {
        SplittableRandom r = SEEDS.split();
        for (int i = 0; i < 300; i++) {
            JournalRecord rec = randomRecord(r);
            byte[] bytes = JournalCodec.encode(rec);
            JournalRecord back = JournalCodec.decode(bytes);
            assertEquals(rec.ritualId(), back.ritualId());
            assertEquals(rec.playerName(), back.playerName());
            assertEquals(rec.rewardAmount(), back.rewardAmount());
            assertEquals(rec.multiplier(), back.multiplier());
            assertEquals(rec.createdAtMillis(), back.createdAtMillis());
            assertArrayEquals(rec.itemTemplate(), back.itemTemplate());
            // Flip one random bit anywhere: the record must be rejected, never silently misread.
            for (int k = 0; k < 20; k++) {
                byte[] flipped = bytes.clone();
                int pos = r.nextInt(flipped.length);
                flipped[pos] ^= (byte) (1 << r.nextInt(8));
                assertThrows(JournalCodec.CorruptRecordException.class, () -> JournalCodec.decode(flipped),
                        "bit flip at byte " + pos + " was not detected");
            }
            // Any truncation is rejected.
            byte[] cut = java.util.Arrays.copyOf(bytes, r.nextInt(bytes.length));
            assertThrows(JournalCodec.CorruptRecordException.class, () -> JournalCodec.decode(cut));
        }
    }

    @Test
    void garbageNeverDecodes() {
        SplittableRandom r = SEEDS.split();
        for (int i = 0; i < 2000; i++) {
            byte[] junk = new byte[r.nextInt(300)];
            for (int k = 0; k < junk.length; k++) junk[k] = (byte) r.nextInt(256);
            if (r.nextBoolean() && junk.length > 6) System.arraycopy("VMJ2 ".getBytes(StandardCharsets.US_ASCII), 0, junk, 0, 5);
            assertThrows(JournalCodec.CorruptRecordException.class, () -> JournalCodec.decode(junk));
        }
    }

    @Test
    void ledgerRoundTripsAndRejectsMutations() throws Exception {
        SplittableRandom r = SEEDS.split();
        for (int i = 0; i < 1000; i++) {
            PlayerLedger l = PlayerLedger.empty();
            int n = r.nextInt(8);
            for (int k = 0; k < n; k++) {
                UUID id = UUID.randomUUID();
                int reward = r.nextInt(10_000);
                l.recordPaid(id, r.nextInt(reward + 1), reward);
            }
            String enc = l.encode();
            PlayerLedger back = PlayerLedger.decode(enc);
            assertEquals(l.asMap(), back.asMap());
            if (enc != null) {
                String broken = enc.substring(0, r.nextInt(enc.length())) + "|zz";
                assertThrows(PlayerLedger.LedgerFormatException.class, () -> PlayerLedger.decode(broken));
            }
        }
    }

    @Test
    void multiplierStorageFormRoundTripsAndRewardsNeverOverflow() {
        SplittableRandom r = SEEDS.split();
        for (int i = 0; i < 5000; i++) {
            int d = 1 + r.nextInt(1000);
            int n = r.nextInt(Multiplier.MAX_FACTOR * d + 1);
            Multiplier m = new Multiplier(n, d);
            assertEquals(m, Multiplier.parse(m.toStorageString()));
            long amount = r.nextInt(Integer.MAX_VALUE);
            long reward = m.apply(amount);
            assertTrue(reward >= 0 && reward <= amount * Multiplier.MAX_FACTOR, m + " x " + amount + " = " + reward);
            assertEquals((amount * m.numerator()) / m.denominator(), reward, "rounds down exactly");
        }
    }

    @Test
    void outcomeEngineNeverPicksZeroWeightsAndNeverExceedsTheCap() {
        SplittableRandom r = SEEDS.split();
        for (int t = 0; t < 200; t++) {
            Map<OutcomeDefinition, Double> weights = new LinkedHashMap<>();
            int outcomes = 1 + r.nextInt(6);
            boolean anyPositive = false;
            for (int k = 0; k < outcomes; k++) {
                int num = r.nextInt(6);
                Multiplier m = new Multiplier(num, 1);
                OutcomeTier tier = num == 0 ? OutcomeTier.LOSS : num == 1 ? OutcomeTier.NEUTRAL : OutcomeTier.WIN;
                double w = r.nextInt(3) == 0 ? 0 : 0.001 + r.nextDouble() * 100;
                anyPositive |= w > 0;
                weights.put(new OutcomeDefinition("o" + k, m, tier), w);
            }
            if (!anyPositive) continue;
            OutcomeTable table = OutcomeTable.of("p", weights);
            OutcomeEngine engine = new OutcomeEngine(RandomSource.seeded(r.nextLong()));
            int cap = 1 + r.nextInt(200);
            for (int k = 0; k < 200; k++) {
                int input = 1 + r.nextInt(99);
                Verdict v = engine.decide(table, input, cap);
                OutcomeDefinition chosen = weights.keySet().stream().filter(d -> d.id().equals(v.outcomeId())).findFirst().orElseThrow();
                assertTrue(weights.get(chosen) > 0, "zero-weight outcome chosen");
                assertTrue(v.rewardAmount() <= Math.max(cap, 0), "cap exceeded");
                assertEquals(Math.min(cap, chosen.multiplier().apply(input)), v.rewardAmount());
            }
        }
    }
}
