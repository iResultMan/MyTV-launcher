package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class SettingsBackupTest {
    private static void bad(String text) {
        try {
            SettingsBackup.fromJson(text);
            fail("accepted: " + text);
        } catch (IllegalArgumentException expected) {
            // refused, as it should be
        }
    }

    @Test
    public void aRoundTripKeepsTypesAndSkipsWhatIsLeftOut() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("columns", 6);
        prefs.put("animations", true);
        prefs.put("wallpaper_choice", "a \"b\" \\ cé");
        prefs.put("settings_revision", 7L);
        prefs.put("big", 5_000_000_000L);
        prefs.put("ratio", 1.5f);
        prefs.put("Bad Key", 1);
        String json = SettingsBackup.toJson(prefs, "settings_revision");
        Map<String, Object> back = SettingsBackup.fromJson(json);
        assertEquals(6, back.get("columns"));
        assertEquals(true, back.get("animations"));
        assertEquals("a \"b\" \\ cé", back.get("wallpaper_choice"));
        assertEquals(5_000_000_000L, back.get("big"));
        assertFalse(back.containsKey("settings_revision"));
        assertFalse(back.containsKey("ratio"));
        assertFalse(back.containsKey("Bad Key"));
        assertEquals(4, back.size());
    }

    @Test
    public void anEmptyBackupIsFine() {
        assertEquals(0, SettingsBackup.fromJson(SettingsBackup.toJson(new HashMap<String, Object>())).size());
    }

    @Test
    public void wrongShapesAreRefused() {
        bad(null);
        bad("");
        bad("[]");
        bad("{}");
        bad("{\"v\":2,\"settings\":{}}");
        bad("{\"v\":1}");
        bad("{\"v\":1,\"settings\":[]}");
        bad("{\"v\":1,\"settings\":{\"Columns\":1}}");
        bad("{\"v\":1,\"settings\":{\"a\":{\"b\":1}}}");
        bad("{\"v\":1,\"settings\":{\"a\":1,\"a\":2}}");
        bad("{\"v\":1,\"settings\":{\"a\":1.5}}");
        bad("{\"v\":1,\"settings\":{\"a\":null}}");
        bad("{\"v\":1,\"settings\":{\"a\":\"x}}");
        bad("{\"v\":1,\"settings\":{}} extra");
        bad("{\"v\":1,\"settings\":{\"a\":1}");
    }

    @Test
    public void sizeLimitsHold() {
        StringBuilder big = new StringBuilder("{\"v\":1,\"settings\":{");
        for (int i = 0; i < 300; i++) big.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":1");
        bad(big.append("}}").toString());
        bad("{\"v\":1,\"settings\":{\"a\":\"" + new String(new char[600]).replace('\0', 'x') + "\"}}");
        bad(new String(new char[SettingsBackup.MAX_TEXT + 1]).replace('\0', ' '));
    }
}
