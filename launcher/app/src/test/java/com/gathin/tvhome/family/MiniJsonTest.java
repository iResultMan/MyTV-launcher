package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class MiniJsonTest {
    @Test
    public void readsObjectsArraysAndScalars() {
        Map<String, Object> m = MiniJson.object(MiniJson.parse(
                "{\"a\": [1, 2.5, \"x\\n\\u00e9\\\"\"], \"b\": {\"c\": true, \"d\": null}, \"seq\": 7}"));
        List<Object> a = MiniJson.array(m.get("a"));
        assertEquals(3, a.size());
        assertEquals(1.0, (Double) a.get(0), 0);
        assertEquals("x\n\u00e9\"", a.get(2));
        assertEquals(Boolean.TRUE, MiniJson.object(m.get("b")).get("c"));
        assertNull(MiniJson.object(m.get("b")).get("d"));
        assertEquals(7, MiniJson.integer(m, "seq", -1));
        assertEquals(-1, MiniJson.integer(m, "missing", -1));
    }

    @Test
    public void aNumberThatIsNotWholeIsNotAnInteger() {
        Map<String, Object> m = MiniJson.object(MiniJson.parse("{\"n\": 2.5, \"s\": \"3\"}"));
        assertEquals(-1, MiniJson.integer(m, "n", -1));
        assertEquals(-1, MiniJson.integer(m, "s", -1));
        assertNull(MiniJson.string(m, "n"));
    }

    @Test
    public void refusesDamagedOrOversizedText() {
        for (String bad : new String[] {"", "{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"\\q\"", "{'a':1}", "nul", "{\"a\":\"\u0001\"}"}) {
            try {
                MiniJson.parse(bad);
                fail("accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) deep.append('[');
        try {
            MiniJson.parse(deep.toString());
            fail("accepted a deep nest");
        } catch (IllegalArgumentException expected) {
            // fine
        }
        try {
            MiniJson.parse(new String(new char[70 * 1024]).replace('\0', ' '));
            fail("accepted 70 KB");
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Test
    public void quoteWritesWhatParseReads() {
        String text = "Dinner \"time\"\n\\ \u0007 \u00e9\u2028";
        assertEquals(text, MiniJson.parse(MiniJson.quote(text)));
        assertTrue(MiniJson.quote("a\nb").contains("\\n"));
    }
}
