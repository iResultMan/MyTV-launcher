package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CloudCoreTest {
    private static final long NOW = 1_800_000_000_000L;

    /** Records what was done and answers as told. */
    private static class Fake implements CloudCore.Executor {
        final List<String> done = new ArrayList<>();
        boolean can = true;

        @Override public boolean lock(String note) { done.add("lock:" + note); return can; }
        @Override public boolean unlock() { done.add("unlock"); return can; }
        @Override public boolean message(String text) { done.add("message:" + text); return can; }
        @Override public boolean pause() { done.add("pause"); return can; }
        @Override public boolean bonus(int minutes) { done.add("bonus:" + minutes); return can; }
        @Override public boolean powerOff(int delay, boolean cancel) { done.add("power_off:" + delay + ":" + cancel); return can; }
    }

    private static CloudCore.Command cmd(String json) {
        return CloudCore.command(MiniJson.object(MiniJson.parse(json)));
    }

    private static String entry(String type, String args, long expiresAt) {
        return "{\"seq\":3,\"id\":\"c1\",\"type\":\"" + type + "\",\"args\":" + args + ",\"expires_at\":" + expiresAt + "}";
    }

    @Test
    public void everyCommandOfTheProtocolIsCarriedOutWithItsArguments() {
        Fake ex = new Fake();
        long later = NOW + 60_000;
        assertEquals("ok", CloudCore.apply(cmd(entry("lock", "{\"note\":\" Dinner time \"}", later)), NOW, true, ex).result);
        assertEquals("ok", CloudCore.apply(cmd(entry("unlock", "{}", later)), NOW, true, ex).result);
        assertEquals("ok", CloudCore.apply(cmd(entry("message", "{\"text\":\"Come here\"}", later)), NOW, true, ex).result);
        assertEquals("ok", CloudCore.apply(cmd(entry("pause", "{}", later)), NOW, true, ex).result);
        assertEquals("ok", CloudCore.apply(cmd(entry("bonus", "{\"minutes\":30}", later)), NOW, true, ex).result);
        assertEquals("ok", CloudCore.apply(cmd(entry("power_off", "{\"delay_s\":600}", later)), NOW, true, ex).result);
        assertEquals("ok", CloudCore.apply(cmd(entry("power_off", "{\"cancel\":true}", later)), NOW, true, ex).result);
        assertEquals("ok", CloudCore.apply(cmd(entry("power_off", "{}", later)), NOW, true, ex).result);
        assertEquals(java.util.Arrays.asList("lock:Dinner time", "unlock", "message:Come here", "pause", "bonus:30",
                "power_off:600:false", "power_off:0:true", "power_off:0:false"), ex.done);
    }

    @Test
    public void aCommandThatExpiredIsNotCarriedOut() {
        Fake ex = new Fake();
        CloudCore.Result r = CloudCore.apply(cmd(entry("lock", "{}", NOW - 1)), NOW, true, ex);
        assertEquals("expired", r.result);
        assertTrue(ex.done.isEmpty());
        assertEquals("ok", CloudCore.apply(cmd(entry("lock", "{}", NOW)), NOW, true, ex).result);
    }

    @Test
    public void withTheSwitchOffNothingIsCarriedOut() {
        Fake ex = new Fake();
        CloudCore.Result r = CloudCore.apply(cmd(entry("lock", "{}", NOW + 1000)), NOW, false, ex);
        assertEquals("failed", r.result);
        assertEquals("cloud control is off", r.detail);
        assertTrue(ex.done.isEmpty());
    }

    @Test
    public void theTvRefusesWhatItCannotOrShouldNotDo() {
        Fake ex = new Fake();
        ex.can = false;
        for (String type : new String[] {"lock", "unlock", "pause"}) {
            assertEquals(type, "failed", CloudCore.apply(cmd(entry(type, "{}", NOW + 1000)), NOW, true, ex).result);
        }
        assertEquals("failed", CloudCore.apply(cmd(entry("message", "{\"text\":\"hi\"}", NOW + 1000)), NOW, true, ex).result);
        assertEquals("failed", CloudCore.apply(cmd(entry("bonus", "{\"minutes\":10}", NOW + 1000)), NOW, true, ex).result);
    }

    @Test
    public void argumentsOutsideTheProtocolLimitsAreFailuresAndNothingRuns() {
        Fake ex = new Fake();
        String tooLong = new String(new char[141]).replace('\0', 'x');
        String[][] bad = {
                {"lock", "{\"note\":\"" + tooLong + "\"}"}, {"message", "{}"}, {"message", "{\"text\":\"  \"}"},
                {"message", "{\"text\":\"" + tooLong + "\"}"}, {"bonus", "{\"minutes\":4}"}, {"bonus", "{\"minutes\":121}"},
                {"bonus", "{\"minutes\":\"30\"}"}, {"bonus", "{}"}, {"power_off", "{\"delay_s\":86401}"},
                {"power_off", "{\"delay_s\":-1}"}, {"reboot", "{}"}};
        for (String[] b : bad) {
            assertEquals(b[0] + " " + b[1].length(), "failed", CloudCore.apply(cmd(entry(b[0], b[1], NOW + 1000)), NOW, true, ex).result);
        }
        assertTrue(ex.done.isEmpty());
        assertEquals("unknown command", CloudCore.apply(cmd(entry("reboot", "{}", NOW + 1000)), NOW, true, ex).detail);
    }

    @Test
    public void anExecutorThatThrowsIsAFailureNotACrash() {
        CloudCore.Executor boom = new Fake() {
            @Override public boolean pause() { throw new IllegalStateException("x"); }
        };
        assertEquals("failed", CloudCore.apply(cmd(entry("pause", "{}", NOW + 1000)), NOW, true, boom).result);
    }

    @Test
    public void anEntryWithoutAnIdATypeOrASequenceIsNotACommand() {
        assertNull(cmd("{\"id\":\"a\",\"type\":\"lock\"}"));
        assertNull(cmd("{\"seq\":1,\"type\":\"lock\"}"));
        assertNull(cmd("{\"seq\":1,\"id\":\"a\"}"));
        assertNull(cmd("{\"seq\":-1,\"id\":\"a\",\"type\":\"lock\"}"));
        assertNull(CloudCore.command(null));
    }

    @Test
    public void retriesStartAtFiveSecondsAndDoubleUpToFiveMinutes() {
        assertEquals(0, CloudCore.retryDelayMs(0));
        assertEquals(5_000, CloudCore.retryDelayMs(1));
        assertEquals(10_000, CloudCore.retryDelayMs(2));
        assertEquals(20_000, CloudCore.retryDelayMs(3));
        assertEquals(300_000, CloudCore.retryDelayMs(7));
        assertEquals(300_000, CloudCore.retryDelayMs(500));
    }

    @Test
    public void onlyHttpsServersAreAcceptedAndPlainHttpOnlyOnLoopbackForTests() {
        assertTrue(CloudCore.validUrl("https://tv.secondoffice.ru", false));
        assertTrue(CloudCore.validUrl("https://tv.secondoffice.ru:8443/api", false));
        assertFalse(CloudCore.validUrl("http://tv.secondoffice.ru", false));
        assertFalse(CloudCore.validUrl("http://tv.secondoffice.ru", true));
        assertFalse(CloudCore.validUrl("http://192.168.1.5:8790", true));
        assertFalse(CloudCore.validUrl("http://192.168.1.5:8790", false));
        assertFalse(CloudCore.validUrl("http://127.0.0.1:8790", false));
        assertTrue(CloudCore.validUrl("http://127.0.0.1:8790", true));
        assertTrue(CloudCore.validUrl("http://localhost:8790", true));
        assertFalse(CloudCore.validUrl("https://", false));
        assertFalse(CloudCore.validUrl("https://user@host", false));
        assertFalse(CloudCore.validUrl("https://host/a b", false));
        assertFalse(CloudCore.validUrl("https://host?x=1", false));
        assertFalse(CloudCore.validUrl("ftp://host", false));
        assertFalse(CloudCore.validUrl("https://host:port", false));
        assertFalse(CloudCore.validUrl(null, false));
    }

    @Test
    public void netRulesStillRefuseAPlainHttpCloudAddress() {
        assertFalse(NetRules.allowed("http://tv.secondoffice.ru"));
        assertFalse(NetRules.allowed("http://tv.secondoffice.ru/v1/devices"));
    }

    @Test
    public void theStateIsTheJsonTheProtocolDescribes() {
        String json = CloudCore.stateJson(false, true, true, true, 1793904538672L, "ru.ivi.client", "Ivi\"",
                "[{\"t\":1,\"kind\":\"service_off\",\"text\":\"x\"}]", null);
        Map<String, Object> m = MiniJson.object(MiniJson.parse(json));
        assertEquals(Boolean.FALSE, m.get("locked"));
        assertEquals(Boolean.TRUE, m.get("scheduled"));
        assertEquals("Ivi\"", MiniJson.string(MiniJson.object(m.get("screen")), "label"));
        assertEquals(1793904538672L, MiniJson.integer(MiniJson.object(m.get("screen")), "since", -1));
        assertEquals(1, MiniJson.array(m.get("journal")).size());
        assertFalse(m.containsKey("days"));
        assertTrue(CloudCore.stateJson(true, false, true, false, 0, null, null, "", "[]").contains("\"days\":[]"));
    }
}
