package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QrCodeTest {
    @Test public void reedSolomonMatchesTheSpecExample() {
        // ISO/IEC 18004 Annex I: "01234567" in version 1-M, ten error correction codewords.
        byte[] data = {0x10, 0x20, 0x0C, 0x56, 0x61, (byte) 0x80, (byte) 0xEC, 0x11, (byte) 0xEC, 0x11, (byte) 0xEC, 0x11, (byte) 0xEC, 0x11, (byte) 0xEC, 0x11};
        byte[] ecc = QrCode.reedSolomonRemainder(data, QrCode.reedSolomonDivisor(10));
        byte[] want = {(byte) 0xA5, 0x24, (byte) 0xD4, (byte) 0xC1, (byte) 0xED, 0x36, (byte) 0xC7, (byte) 0x87, 0x2C, 0x55};
        for (int i = 0; i < want.length; i++) assertEquals("codeword " + i, want[i], ecc[i]);
    }

    @Test public void formatBitsOfLevelMMatchTheTable() {
        int[] table = {0b101010000010010, 0b101000100100101, 0b101111001111100, 0b101101101001011,
                0b100010111111001, 0b100000011001110, 0b100111110010111, 0b100101010100000};
        for (int mask = 0; mask < 8; mask++) assertEquals("mask " + mask, table[mask], QrCode.formatBits(mask));
    }

    @Test public void dataCapacityOfLevelMMatchesTheSpec() {
        int[] want = {0, 16, 28, 44, 64, 86, 108};
        for (int v = 1; v <= 6; v++) assertEquals("version " + v, want[v], QrCode.dataCodewords(v));
    }

    @Test public void aShortAddressMakesVersion3WithFinderPatterns() {
        boolean[][] m = QrCode.encode("http://192.168.1.132:8787/?c=123456");
        assertEquals(29, m.length);
        // The three finder patterns: dark corners and a dark centre, light ring.
        int[][] at = {{0, 0}, {22, 0}, {0, 22}};
        for (int[] p : at) {
            assertTrue(m[p[1]][p[0]]);
            assertTrue(m[p[1] + 3][p[0] + 3]);
            assertFalse(m[p[1] + 1][p[0] + 1]);
            assertTrue(m[p[1] + 6][p[0] + 6]);
        }
        // Timing pattern alternates.
        for (int i = 8; i < 21; i++) assertEquals(i % 2 == 0, m[6][i]);
        // The dark module.
        assertTrue(m[29 - 8][8]);
    }

    @Test public void theSameTextGivesTheSameCodeAndAnotherTextAnotherCode() {
        boolean[][] a = QrCode.encode("http://192.168.1.132:8787/?c=123456");
        boolean[][] b = QrCode.encode("http://192.168.1.132:8787/?c=123456");
        boolean[][] c = QrCode.encode("http://192.168.1.132:8787/?c=654321");
        assertTrue(java.util.Arrays.deepEquals(a, b));
        assertFalse(java.util.Arrays.deepEquals(a, c));
    }

    @Test(expected = IllegalArgumentException.class) public void aTextOverTheCapacityIsRefused() {
        QrCode.encode(new String(new char[120]).replace('\0', 'a'));
    }

    @Test public void allVersionsOneToSixDrawWithoutError() {
        for (int len : new int[] {5, 14, 26, 42, 62, 84, 106}) {
            boolean[][] m = QrCode.encode(new String(new char[len]).replace('\0', 'x'));
            assertTrue(m.length >= 21 && m.length <= 41);
        }
    }
}
