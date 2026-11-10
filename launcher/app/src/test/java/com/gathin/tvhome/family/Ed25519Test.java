package com.gathin.tvhome.family;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Ed25519Test {
    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    // RFC 8032, section 7.1
    private static final String PK1 = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String SIG1 = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";
    private static final String PK2 = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    private static final String SIG2 = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00";

    @Test
    public void rfcVectorsVerify() {
        assertTrue(Ed25519.verify(hex(PK1), new byte[0], hex(SIG1)));
        assertTrue(Ed25519.verify(hex(PK2), hex("72"), hex(SIG2)));
    }

    @Test
    public void aChangedMessageKeyOrSignatureFails() {
        assertFalse(Ed25519.verify(hex(PK1), hex("00"), hex(SIG1)));
        assertFalse(Ed25519.verify(hex(PK2), hex("73"), hex(SIG2)));
        assertFalse(Ed25519.verify(hex(PK2), hex("72"), hex(SIG1)));
        assertFalse(Ed25519.verify(hex(PK1), new byte[0], hex(SIG2)));
        byte[] bad = hex(SIG2);
        bad[10] ^= 1;
        assertFalse(Ed25519.verify(hex(PK2), hex("72"), bad));
    }

    @Test
    public void malformedInputsAreRefusedNotThrown() {
        assertFalse(Ed25519.verify(null, new byte[0], hex(SIG1)));
        assertFalse(Ed25519.verify(hex(PK1), new byte[0], new byte[63]));
        assertFalse(Ed25519.verify(new byte[31], new byte[0], hex(SIG1)));
        byte[] big = hex(SIG1);
        for (int i = 32; i < 64; i++) big[i] = (byte) 0xff;                    // s >= L
        assertFalse(Ed25519.verify(hex(PK1), new byte[0], big));
        byte[] notAPoint = new byte[32];
        java.util.Arrays.fill(notAPoint, (byte) 0xff);
        assertFalse(Ed25519.verify(notAPoint, new byte[0], hex(SIG1)));
    }
}
