package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class PosterRulesTest {
    @Test
    public void httpsAndContentAreAllowedAnywhere() {
        assertTrue(PosterRules.allowed("https://cdn.example.com/a.jpg"));
        assertTrue(PosterRules.allowed("HTTPS://cdn.example.com/a.jpg"));
        assertTrue(PosterRules.allowed("content://com.app.provider/poster/1"));
    }

    @Test
    public void plainHttpOnlyOnTheHomeNetwork() {
        assertTrue(PosterRules.allowed("http://192.168.1.175:8765/p.jpg"));
        assertTrue(PosterRules.allowed("http://10.0.0.5/p.jpg"));
        assertTrue(PosterRules.allowed("http://172.16.3.4/p.jpg"));
        assertFalse(PosterRules.allowed("http://172.32.3.4/p.jpg"));
        assertFalse(PosterRules.allowed("http://8.8.8.8/p.jpg"));
        assertFalse(PosterRules.allowed("http://example.com/p.jpg"));
        assertFalse(PosterRules.allowed("http://192.168.1.1.evil.com/p.jpg"));
        assertFalse(PosterRules.allowed("http://192.168.1.1@evil.com/p.jpg"));
    }

    @Test
    public void otherSchemesAndJunkAreRefused() {
        assertFalse(PosterRules.allowed(null));
        assertFalse(PosterRules.allowed(""));
        assertFalse(PosterRules.allowed("file:///data/data/x"));
        assertFalse(PosterRules.allowed("javascript:alert(1)"));
        assertFalse(PosterRules.allowed("ftp://192.168.1.1/a"));
        assertFalse(PosterRules.allowed("android.resource://x/1"));
    }

    @Test
    public void evictionDropsTheOldestUntilItFits() {
        long[] sizes = {10, 10, 10, 10};
        long[] used = {400, 100, 300, 200};
        assertEquals(Arrays.asList(1, 3), PosterRules.evict(sizes, used, 20));
        assertEquals(Arrays.<Integer>asList(), PosterRules.evict(sizes, used, 40));
        assertEquals(Arrays.asList(1, 3, 2, 0), PosterRules.evict(sizes, used, 0));
    }
}
