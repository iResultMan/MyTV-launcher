package com.gathin.tvhome.family;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NetRulesTest {
    @Test
    public void httpsAnywherePlainHttpOnlyAtHome() {
        assertTrue(NetRules.allowed("https://api.open-meteo.com/v1/forecast?x=1"));
        assertTrue(NetRules.allowed("http://192.168.1.175:8765/current"));
        assertTrue(NetRules.allowed("http://10.1.2.3/x"));
        assertFalse(NetRules.allowed("http://api.open-meteo.com/v1/forecast"));
        assertFalse(NetRules.allowed("http://8.8.8.8/x"));
        assertFalse(NetRules.allowed("http://192.168.1.1.evil.example/x"));
    }

    @Test
    public void otherSchemesAreNotNetworkAddresses() {
        assertFalse(NetRules.allowed("content://com.app/poster"));
        assertFalse(NetRules.allowed("file:///etc/passwd"));
        assertFalse(NetRules.allowed("ftp://192.168.1.1/x"));
        assertFalse(NetRules.allowed(null));
        assertFalse(NetRules.allowed(""));
    }
}
