package com.voidmachine.core.ledger;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLedgerTest {

    @Test
    void roundTrip() throws Exception {
        PlayerLedger l = PlayerLedger.empty();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        l.markCaptured(a, 128);
        l.recordPaid(b, 5, 5);
        PlayerLedger back = PlayerLedger.decode(l.encode());
        assertEquals(new PlayerLedger.Entry(a, 0, 128), back.get(a));
        assertTrue(back.get(b).settled());
    }

    @Test
    void emptyEncodesToNull() throws Exception {
        assertNull(PlayerLedger.empty().encode());
        assertTrue(PlayerLedger.decode(null).isEmpty());
        assertTrue(PlayerLedger.decode("").isEmpty());
    }

    @Test
    void doubleCaptureIsRefused() {
        PlayerLedger l = PlayerLedger.empty();
        UUID a = UUID.randomUUID();
        l.markCaptured(a, 1);
        assertThrows(IllegalStateException.class, () -> l.markCaptured(a, 1));
    }

    @Test
    void malformedLedgersAreRejected() {
        String id = UUID.randomUUID().toString();
        for (String bad : new String[]{"v9|" + id + ":0:1", "v1|nope:0:1", "v1|" + id + ":x:1", "v1|" + id + ":-1:1",
                "v1|" + id + ":0", "v1|" + id + ":0:1|" + id + ":0:1"}) {
            assertThrows(PlayerLedger.LedgerFormatException.class, () -> PlayerLedger.decode(bad), bad);
        }
    }
}
