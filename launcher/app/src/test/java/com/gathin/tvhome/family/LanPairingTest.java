package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class LanPairingTest {
    private static final class Mem implements LanPairing.Store {
        List<LanPairing.Phone> phones = new ArrayList<>();

        @Override public List<LanPairing.Phone> load() {
            return new ArrayList<>(phones);
        }

        @Override public void save(List<LanPairing.Phone> p) {
            phones = new ArrayList<>(p);
        }
    }

    private long now = 1_000_000;
    private final Mem mem = new Mem();
    private final LanPairing pairing = new LanPairing(mem, () -> now);

    @Test public void theRightCodeGivesATokenThatNamesThePhone() {
        String code = pairing.newCode();
        assertEquals(6, code.length());
        String token = pairing.pair(code, "Mama");
        assertNotNull(token);
        assertEquals(64, token.length());
        assertEquals("Mama", pairing.phoneOf(token).name);
    }

    @Test public void aCodeWorksOnce() {
        String code = pairing.newCode();
        assertNotNull(pairing.pair(code, "A"));
        assertNull(pairing.pair(code, "B"));
    }

    @Test public void aCodeExpires() {
        String code = pairing.newCode();
        now += LanPairing.CODE_TTL_MS + 1;
        assertNull(pairing.pair(code, "A"));
    }

    @Test public void aNewCodeReplacesTheOldOne() {
        String first = pairing.newCode();
        String second = pairing.newCode();
        if (first.equals(second)) return;
        assertNull(pairing.pair(first, "A"));
        assertNotNull(pairing.pair(second, "A"));
    }

    @Test public void aClosedCodeDoesNotWork() {
        String code = pairing.newCode();
        pairing.closeCode();
        assertNull(pairing.pair(code, "A"));
    }

    @Test public void theTvKeepsOnlyAHashOfTheToken() {
        String token = pairing.pair(pairing.newCode(), "A");
        for (LanPairing.Phone p : mem.phones) {
            assertNotEquals(token, p.tokenHash);
            assertFalse(p.tokenHash.contains(token));
            assertEquals(64, p.tokenHash.length());
        }
    }

    @Test public void anUnknownOrEmptyTokenIsNobody() {
        pairing.pair(pairing.newCode(), "A");
        assertNull(pairing.phoneOf("0000"));
        assertNull(pairing.phoneOf(""));
        assertNull(pairing.phoneOf(null));
    }

    @Test public void aRevokedPhoneIsNobodyAndOthersStay() {
        String a = pairing.pair(pairing.newCode(), "A");
        String b = pairing.pair(pairing.newCode(), "B");
        pairing.revoke(pairing.phoneOf(a).id);
        assertNull(pairing.phoneOf(a));
        assertEquals("B", pairing.phoneOf(b).name);
        pairing.revokeAll();
        assertNull(pairing.phoneOf(b));
        assertTrue(pairing.phones().isEmpty());
    }

    @Test public void fiveWrongCodesBlockPairingEvenForTheRightOne() {
        String code = pairing.newCode();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < LanPairing.MAX_FAILS; i++) assertNull(pairing.pair(wrong, "X"));
        assertNull(pairing.pair(code, "A"));
        now += LanPairing.FAIL_WINDOW_MS + 1;
        pairing.newCode();
        assertNotNull(pairing.pair(pairing.newCode(), "A"));
    }

    @Test public void thereIsRoomForEightPhones() {
        for (int i = 0; i < LanPairing.MAX_PHONES; i++) assertNotNull(pairing.pair(pairing.newCode(), "P" + i));
        assertNull(pairing.pair(pairing.newCode(), "one more"));
    }

    @Test public void namesAreCleanedAndCut() {
        String t = pairing.pair(pairing.newCode(), "  Mama\u0007\nPhone with a very long name that goes on and on  ");
        String name = pairing.phoneOf(t).name;
        assertTrue(name.length() <= LanPairing.NAME_MAX);
        assertFalse(name.contains("\n"));
        assertEquals("phone", pairing.phoneOf(pairing.pair(pairing.newCode(), "")).name);
    }
}
