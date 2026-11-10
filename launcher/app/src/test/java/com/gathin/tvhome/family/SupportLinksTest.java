package com.gathin.tvhome.family;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class SupportLinksTest {
    private static final String KOFI = "https://ko-fi.com/resultman";
    private static final String CLOUDTIPS = "https://pay.cloudtips.ru/p/0b7cf363";

    @Test
    public void noLinksMeansNoRow() {
        assertTrue(SupportLinks.of("", "").isEmpty());
        assertTrue(SupportLinks.of(null, null).isEmpty());
        assertTrue(SupportLinks.of("   ", "\t").isEmpty());
    }

    @Test
    public void oneLinkGivesOneCodeAndTwoGiveTwoInOrder() {
        List<SupportLinks.Link> one = SupportLinks.of("", CLOUDTIPS);
        assertEquals(1, one.size());
        assertEquals(SupportLinks.Kind.RUSSIA, one.get(0).kind);
        List<SupportLinks.Link> two = SupportLinks.of(KOFI, CLOUDTIPS);
        assertEquals(2, two.size());
        assertEquals(SupportLinks.Kind.WORLDWIDE, two.get(0).kind);
        assertEquals(SupportLinks.Kind.RUSSIA, two.get(1).kind);
    }

    @Test
    public void theQrCodeCarriesExactlyTheGivenLink() {
        for (String given : new String[] {KOFI, CLOUDTIPS}) {
            SupportLinks.Link link = SupportLinks.of(given, "").get(0);
            assertEquals(given, link.url);
            assertArrayEquals(QrCode.encode(given), QrCode.encode(link.url));
        }
        assertEquals(KOFI, SupportLinks.of("  " + KOFI + "\n", "").get(0).url);
    }

    @Test
    public void onlyPlainHttpsLinksThatFitAQrCodeCount() {
        assertTrue(SupportLinks.of("http://ko-fi.com/resultman", "").isEmpty());
        assertTrue(SupportLinks.of("javascript:alert(1)", "ftp://example.org/x").isEmpty());
        assertTrue(SupportLinks.of("https://", "").isEmpty());
        assertTrue(SupportLinks.of("https://ko-fi.com/a b", "").isEmpty());
        StringBuilder longLink = new StringBuilder("https://example.org/");
        while (longLink.length() <= SupportLinks.MAX_BYTES) longLink.append('x');
        assertTrue(SupportLinks.of(longLink.toString(), "").isEmpty());
        assertEquals(1, SupportLinks.of(longLink.substring(0, SupportLinks.MAX_BYTES), "").size());
    }
}
