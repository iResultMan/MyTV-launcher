package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.Test;

public class CloudSessionTest {
    private static final long NOW = 1_800_000_000_000L;

    /** Answers from a script and remembers every request as "METHOD path | bearer | body". */
    private static final class ScriptedHttp implements CloudSession.Http {
        final Deque<Object> answers = new ArrayDeque<>();   // CloudSession.Response or IOException
        final List<String> requests = new ArrayList<>();

        ScriptedHttp say(int status, String body) {
            answers.add(new CloudSession.Response(status, body));
            return this;
        }

        ScriptedHttp fail() {
            answers.add(new IOException("no network"));
            return this;
        }

        @Override
        public CloudSession.Response request(String method, String url, String bearer, String body, int timeoutMs) throws IOException {
            requests.add(method + " " + url.substring(url.indexOf("/v1")) + " | " + bearer + " | " + body);
            Object a = answers.poll();
            if (a instanceof IOException) throw (IOException) a;
            return a == null ? new CloudSession.Response(500, "") : (CloudSession.Response) a;
        }
    }

    private static final class Memory implements CloudSession.Credentials {
        String id, secret;
        Memory(String id, String secret) { this.id = id; this.secret = secret; }
        @Override public String deviceId() { return id; }
        @Override public String secret() { return secret; }
        @Override public void save(String i, String s) { id = i; secret = s; }
        @Override public void clear() { id = null; secret = null; }
    }

    private static final class Done implements CloudCore.Executor {
        final List<String> done = new ArrayList<>();
        @Override public boolean lock(String note) { done.add("lock:" + note); return true; }
        @Override public boolean unlock() { done.add("unlock"); return true; }
        @Override public boolean message(String text) { done.add("message:" + text); return true; }
        @Override public boolean pause() { done.add("pause"); return true; }
        @Override public boolean bonus(int minutes) { done.add("bonus:" + minutes); return true; }
        @Override public boolean powerOff(int d, boolean c) { done.add("power_off:" + d + ":" + c); return true; }
    }

    private static CloudSession session(ScriptedHttp http, Memory m) {
        return new CloudSession("https://tv.example.org/", http, m);
    }

    private static String poll(String... commands) {
        return "{\"commands\":[" + String.join(",", commands) + "],\"seq\":9}";
    }

    private static String command(long seq, String id, String type, String args, long expires) {
        return "{\"seq\":" + seq + ",\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"args\":" + args + ",\"expires_at\":" + expires + "}";
    }

    @Test
    public void registeringKeepsTheSecretAndHappensOnce() {
        ScriptedHttp http = new ScriptedHttp().say(200, "{\"device_id\":\"d1\",\"device_secret\":\"s1\"}");
        Memory m = new Memory(null, null);
        CloudSession s = session(http, m);
        assertEquals(CloudSession.Status.OK, s.register("Living room TV"));
        assertEquals("d1", m.id);
        assertEquals("s1", m.secret);
        assertEquals("POST /v1/devices | null | {\"name\":\"Living room TV\"}", http.requests.get(0));
        assertEquals(CloudSession.Status.OK, s.register("again"));
        assertEquals(1, http.requests.size());
    }

    @Test
    public void aServerThatDoesNotSpeakTheProtocolIsRefusedNotRetried() {
        Memory m = new Memory(null, null);
        assertEquals(CloudSession.Status.REJECTED, session(new ScriptedHttp().say(400, "{\"error\":\"unknown protocol\"}"), m).register("TV"));
        assertNull(m.secret);
        assertEquals(CloudSession.Status.RETRY, session(new ScriptedHttp().fail(), m).register("TV"));
        assertEquals(CloudSession.Status.RETRY, session(new ScriptedHttp().say(503, ""), m).register("TV"));
        assertEquals(CloudSession.Status.REJECTED, session(new ScriptedHttp().say(200, "{\"device_id\":\"d\"}"), m).register("TV"));
        assertNull(m.secret);
    }

    @Test
    public void pollsApplyEachCommandInOrderAndAcknowledgeIt() {
        ScriptedHttp http = new ScriptedHttp()
                .say(200, poll(command(7, "a", "lock", "{\"note\":\"Dinner\"}", NOW + 1000), command(8, "b", "pause", "{}", NOW + 1000)))
                .say(200, "{}").say(200, "{}");
        Done ex = new Done();
        CloudSession.Step step = session(http, new Memory("d", "s")).pollAndApply(5, 25, ex, () -> true, () -> NOW);
        assertEquals(CloudSession.Status.OK, step.status);
        assertEquals(9, step.since);
        assertEquals(2, step.applied);
        assertEquals(java.util.Arrays.asList("lock:Dinner", "pause"), ex.done);
        assertEquals("GET /v1/device/poll?since=5&wait=25 | s | null", http.requests.get(0));
        assertEquals("POST /v1/device/ack | s | {\"id\":\"a\",\"result\":\"ok\",\"detail\":\"\"}", http.requests.get(1));
        assertEquals("POST /v1/device/ack | s | {\"id\":\"b\",\"result\":\"ok\",\"detail\":\"\"}", http.requests.get(2));
    }

    @Test
    public void anExpiredCommandIsAcknowledgedAsExpiredAndNotCarriedOut() {
        ScriptedHttp http = new ScriptedHttp().say(200, poll(command(7, "a", "lock", "{}", NOW - 5))).say(200, "{}");
        Done ex = new Done();
        session(http, new Memory("d", "s")).pollAndApply(0, 25, ex, () -> true, () -> NOW);
        assertTrue(ex.done.isEmpty());
        assertTrue(http.requests.get(1).contains("\"result\":\"expired\""));
    }

    @Test
    public void withTheSwitchOffCommandsAreRefusedAndNotCarriedOut() {
        ScriptedHttp http = new ScriptedHttp().say(200, poll(command(7, "a", "lock", "{}", NOW + 1000))).say(200, "{}");
        Done ex = new Done();
        session(http, new Memory("d", "s")).pollAndApply(0, 25, ex, () -> false, () -> NOW);
        assertTrue(ex.done.isEmpty());
        assertTrue(http.requests.get(1).contains("\"result\":\"failed\""));
        assertTrue(http.requests.get(1).contains("cloud control is off"));
    }

    @Test
    public void aCommandAtOrBelowTheSequenceTheTvHasSeenIsNotAppliedTwice() {
        ScriptedHttp http = new ScriptedHttp().say(200, poll(command(5, "old", "lock", "{}", NOW + 1000), command(6, "new", "pause", "{}", NOW + 1000))).say(200, "{}");
        Done ex = new Done();
        CloudSession.Step step = session(http, new Memory("d", "s")).pollAndApply(5, 25, ex, () -> true, () -> NOW);
        assertEquals(java.util.Arrays.asList("pause"), ex.done);
        assertEquals(9, step.since);
        assertEquals(2, http.requests.size());
    }

    @Test
    public void aFailedAcknowledgementDoesNotMakeTheCommandRunAgain() {
        ScriptedHttp http = new ScriptedHttp().say(200, poll(command(7, "a", "pause", "{}", NOW + 1000))).fail();
        Done ex = new Done();
        CloudSession.Step step = session(http, new Memory("d", "s")).pollAndApply(0, 25, ex, () -> true, () -> NOW);
        assertEquals(1, ex.done.size());
        assertEquals(9, step.since);
    }

    @Test
    public void a401AfterTheTvWasRevokedStopsTheCallsAndAppliesNothing() {
        ScriptedHttp http = new ScriptedHttp().say(401, "{\"error\":\"revoked\"}");
        Done ex = new Done();
        CloudSession.Step step = session(http, new Memory("d", "s")).pollAndApply(3, 25, ex, () -> true, () -> NOW);
        assertEquals(CloudSession.Status.UNAUTHORIZED, step.status);
        assertEquals(3, step.since);
        assertTrue(ex.done.isEmpty());
        assertEquals(CloudSession.Status.UNAUTHORIZED, session(new ScriptedHttp().say(401, ""), new Memory("d", "s")).pushState("{}"));
        assertEquals(CloudSession.Status.UNAUTHORIZED, session(new ScriptedHttp().say(401, ""), new Memory("d", "s")).pairCode().status);
    }

    @Test
    public void anAcknowledgementThatGets401EndsThePass() {
        ScriptedHttp http = new ScriptedHttp().say(200, poll(command(7, "a", "pause", "{}", NOW + 1000), command(8, "b", "pause", "{}", NOW + 1000))).say(401, "");
        Done ex = new Done();
        CloudSession.Step step = session(http, new Memory("d", "s")).pollAndApply(0, 25, ex, () -> true, () -> NOW);
        assertEquals(CloudSession.Status.UNAUTHORIZED, step.status);
        assertEquals(1, ex.done.size());
    }

    @Test
    public void aDamagedPollAnswerAppliesNothing() {
        Done ex = new Done();
        CloudSession.Step step = session(new ScriptedHttp().say(200, "not json"), new Memory("d", "s")).pollAndApply(2, 25, ex, () -> true, () -> NOW);
        assertEquals(2, step.since);
        assertTrue(ex.done.isEmpty());
    }

    @Test
    public void theWaitIsCappedAtThirtySeconds() {
        ScriptedHttp http = new ScriptedHttp().say(200, poll());
        session(http, new Memory("d", "s")).pollAndApply(0, 300, new Done(), () -> true, () -> NOW);
        assertTrue(http.requests.get(0).contains("wait=30"));
    }

    @Test
    public void thePairingCodeIsReadAndAWrongOneIsRefused() {
        CloudSession.PairCode ok = session(new ScriptedHttp().say(200, "{\"code\":\"123456\",\"expires_in\":300}"), new Memory("d", "s")).pairCode();
        assertEquals("123456", ok.code);
        assertEquals(300, ok.expiresIn);
        assertEquals(CloudSession.Status.REJECTED, session(new ScriptedHttp().say(200, "{\"code\":\"12 3\"}"), new Memory("d", "s")).pairCode().status);
    }

    @Test
    public void turningTheSwitchOffDeletesTheTvOnTheServerAndTheSecretHere() {
        ScriptedHttp http = new ScriptedHttp().say(200, "{}");
        Memory m = new Memory("d1", "s1");
        assertEquals(CloudSession.Status.OK, session(http, m).deleteDevice());
        assertEquals("DELETE /v1/devices/d1 | s1 | null", http.requests.get(0));
        assertNull(m.secret);
        assertNull(m.id);
    }

    @Test
    public void theSecretGoesEvenWhenTheServerCannotBeReached() {
        Memory m = new Memory("d1", "s1");
        assertEquals(CloudSession.Status.RETRY, session(new ScriptedHttp().fail(), m).deleteDevice());
        assertNull(m.secret);
        Memory unknown = new Memory("d1", "s1");
        assertEquals(CloudSession.Status.OK, session(new ScriptedHttp().say(404, ""), unknown).deleteDevice());
        assertNull(unknown.secret);
        ScriptedHttp none = new ScriptedHttp();
        assertEquals(CloudSession.Status.OK, session(none, new Memory(null, null)).deleteDevice());
        assertTrue(none.requests.isEmpty());
        assertFalse(session(none, new Memory("d", null)).registered());
    }
}
