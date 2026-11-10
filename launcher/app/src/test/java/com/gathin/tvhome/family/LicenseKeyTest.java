package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Keys made by tools/license/mint.py from the private key bytes(range(32)). */
public class LicenseKeyTest {
    private static final byte[] PUBLIC = LicenseKey.hex("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8");
    private static final String HOME = "TVH1.dGllcj1ob21lO2V4cD0yMDI3MTIzMTtkZXY9YWIxMmNkMzQsZWY1NmFiNzg.PjhloHcgnXl84uchZeNmlu37301hdk86GJ2h3mAcU8rDHGPuLGhgTbZQZzMNMgFxfoWHtyHm51HhNMaHje76Bw";
    private static final String FAMILY = "TVH1.dGllcj1mYW1pbHk7ZGV2PWFiMTJjZDM0.a4mgHu6lVC1qMbDyAI1ZpBt_BGypDT7BDRNWTAtzsvFiVqvKbTo7RnPSyd1fTGVo-Bq5w-xWbqnO5U_tqNP7Dw";

    private static LicenseKey.Result check(String key, String device, int today) {
        return LicenseKey.check(key, PUBLIC, device, today);
    }

    @Test
    public void aKeyForThisTvGivesItsTier() {
        LicenseKey.Result r = check(HOME, "ab12cd34", 20261105);
        assertEquals(LicenseKey.Tier.HOME, r.tier);
        assertEquals(LicenseKey.Reason.OK, r.reason);
        assertEquals(20271231, r.expires);
        assertEquals(LicenseKey.Tier.HOME, check(HOME, "ef56ab78", 20261105).tier);
        assertEquals(LicenseKey.Tier.HOME, check("  " + HOME + "\n", "ab12cd34", 20261105).tier);
        LicenseKey.Result f = check(FAMILY, "ab12cd34", 20990101);
        assertEquals(LicenseKey.Tier.FAMILY, f.tier);
        assertEquals(0, f.expires);
    }

    @Test
    public void everythingElseStaysFree() {
        assertEquals(LicenseKey.Reason.OTHER_DEVICE, check(HOME, "00000000", 20261105).reason);
        assertEquals(LicenseKey.Reason.EXPIRED, check(HOME, "ab12cd34", 20280101).reason);
        assertEquals(LicenseKey.Reason.OK, check(HOME, "ab12cd34", 20271231).reason);
        assertEquals(LicenseKey.Reason.NO_KEY, check("", "ab12cd34", 1).reason);
        assertEquals(LicenseKey.Reason.NO_KEY, check(null, "ab12cd34", 1).reason);
        assertEquals(LicenseKey.Reason.NO_PUBLIC_KEY, LicenseKey.check(HOME, null, "ab12cd34", 1).reason);
        assertEquals(LicenseKey.Reason.BAD_FORMAT, check("TVH2.a.b", "ab12cd34", 1).reason);
        assertEquals(LicenseKey.Reason.BAD_FORMAT, check("TVH1.only", "ab12cd34", 1).reason);
        assertEquals(LicenseKey.Reason.BAD_FORMAT, check("TVH1.!!!.???", "ab12cd34", 1).reason);
        for (String k : new String[] {"", HOME, FAMILY}) {
            assertEquals(LicenseKey.Tier.FREE, check(k.isEmpty() ? k : k.replace('.', ','), "ab12cd34", 1).tier);
        }
    }

    @Test
    public void aTamperedPayloadOrSignatureIsRefused() {
        // tier=home swapped for tier=family keeps the format but not the signature
        String[] p = HOME.split("\\.");
        String swapped = FAMILY.split("\\.")[1];
        assertEquals(LicenseKey.Reason.BAD_SIGNATURE, check(p[0] + "." + swapped + "." + p[2], "ab12cd34", 1).reason);
        String broken = HOME.substring(0, HOME.length() - 3) + "AAA";
        assertEquals(LicenseKey.Reason.BAD_SIGNATURE, check(broken, "ab12cd34", 1).reason);
        byte[] other = LicenseKey.hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        assertEquals(LicenseKey.Reason.BAD_SIGNATURE, LicenseKey.check(HOME, other, "ab12cd34", 1).reason);
    }

    @Test
    public void hexParsing() {
        assertNull(LicenseKey.hex(""));
        assertNull(LicenseKey.hex("zz" + "00".repeat(31)));
        assertEquals(32, LicenseKey.hex("00".repeat(32)).length);
    }
}
