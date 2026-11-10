package com.gathin.tvhome;

import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KidsLockTest {
    private static final String SALT = "00112233445566ff";

    private static final class Mem implements KidsLockCore.State {
        boolean locked;
        String by = "";

        public boolean isLocked() {
            return locked;
        }

        public String by() {
            return by;
        }

        public void set(boolean l, String b) {
            locked = l;
            by = b;
        }

        String note = "";

        @Override public String note() {
            return note;
        }

        @Override public void setNote(String n) {
            note = n;
        }
    }

    private final Mem state = new Mem();
    private final AtomicLong now = new AtomicLong(1000);

    private KidsLockCore core() {
        Map<String, String> phones = new LinkedHashMap<>();
        phones.put("Papa", "tok-papa");
        phones.put("iMac", "tok-relay");
        KidsLockCore core = new KidsLockCore(phones, SALT, KidsLockCore.hashPin("4321", SALT), state, now::get);
        core.setText(() -> java.util.Collections.singletonMap("web_kids_title", "Kids mode"));
        return core;
    }

    @Test public void theCodeHashMatchesWhatPythonStores() {
        // python3 -c "import hashlib;print(hashlib.pbkdf2_hmac('sha256',b'4321',bytes.fromhex('00112233445566ff'),200000).hex())"
        assertEquals("e75dc53e8ee518c5a55c41391a109371ec0734f160990b26d986ece05ea9532e", KidsLockCore.hashPin("4321", SALT));
        assertFalse(KidsLockCore.hashPin("4321", SALT).equals(KidsLockCore.hashPin("4322", SALT)));
    }

    @Test public void aLockCarriesItsNoteAndALaterOneWithoutClearsIt() {
        KidsLockCore c = core();
        assertEquals(200, c.handle("POST", "/k/tok-papa/lock", null, "{\"note\":\"\\u0423\\u0440\\u043e\\u043a\\u0438, \\\"\\u0434\\u043e\\u043c\\u0430\\\"\"}").status);
        assertEquals("\u0423\u0440\u043e\u043a\u0438, \"\u0434\u043e\u043c\u0430\"", state.note);
        assertEquals(200, c.handle("POST", "/k/tok-papa/lock", null, "").status);
        assertEquals("", state.note);
        assertEquals(KidsLockCore.NOTE_MAX, KidsLockCore.noteOf("{\"note\":\"" + "x".repeat(500) + "\"}").length());
    }

    @Test public void powerOffWorksOnTheLegacyRouteForAKnownPhoneOnly() {
        KidsLockCore c = core();
        java.util.List<Integer> asked = new java.util.ArrayList<>();
        java.util.List<String> journal = new java.util.ArrayList<>();
        c.setHealth(() -> "\"service\":true", (kind, text) -> journal.add(kind + ":" + text));
        assertEquals(404, c.handle("POST", "/k/tok-papa/poweroff", null, "{}").status);      // not wired
        c.setPowerOff(asked::add);
        assertEquals(404, c.handle("POST", "/k/nope/poweroff", null, "{}").status);
        assertEquals(200, c.handle("POST", "/k/tok-papa/poweroff", null, "{\"delay_s\":600}").status);
        assertEquals(200, c.handle("POST", "/k/tok-relay/poweroff", "Mama", "").status);
        assertEquals(java.util.Arrays.asList(600, 0), asked);
        assertEquals(java.util.Arrays.asList("poweroff:TV will turn off in 600 s, asked by Papa", "poweroff:TV turned off from Mama"), journal);
        assertEquals(404, c.handle("GET", "/k/tok-papa/poweroff", null, "").status);
    }

    @Test public void usageIsServedToAKnownPhoneOnlyWithTheSinceValue() {
        KidsLockCore c = core();
        assertEquals(404, c.handle("GET", "/k/tok-relay/usage?since=5", null, "").status);      // nothing wired yet
        c.setUsage(since -> "{\"since\":" + since + "}");
        KidsLockCore.Reply r = c.handle("GET", "/k/tok-relay/usage?since=12345", null, "");
        assertEquals(200, r.status);
        assertEquals("{\"since\":12345}", r.body);
        assertEquals("{\"since\":0}", c.handle("GET", "/k/tok-relay/usage", null, "").body);
        assertEquals(404, c.handle("GET", "/k/nope/usage?since=1", null, "").status);
        assertEquals(404, c.handle("POST", "/k/tok-relay/usage", null, "").status);
    }

    @Test public void anUnknownTokenGetsNothing() {
        assertEquals(404, core().handle("POST", "/k/nope/lock", null, "").status);
        assertFalse(state.locked);
        assertEquals(404, core().handle("GET", "/state", null, "").status);
    }

    @Test public void anAuthorisedPhoneLocksAndNeedsTheCodeToUnlock() {
        KidsLockCore c = core();
        assertEquals(200, c.handle("POST", "/k/tok-papa/lock", null, "").status);
        assertTrue(state.locked);
        assertEquals("Papa", state.by);
        assertEquals(403, c.handle("POST", "/k/tok-papa/unlock", null, "{\"pin\":\"0000\"}").status);
        assertEquals(403, c.handle("POST", "/k/tok-papa/unlock", null, "{}").status);
        assertTrue(state.locked);
        assertEquals(200, c.handle("POST", "/k/tok-papa/unlock", null, "{\"pin\":\"4321\"}").status);
        assertFalse(state.locked);
    }

    @Test public void fiveWrongCodesBlockEvenTheRightOneUntilTheWindowPasses() {
        KidsLockCore c = core();
        state.locked = true;
        for (int i = 0; i < KidsLockCore.MAX_FAILS; i++) assertEquals(403, c.handle("POST", "/k/tok-papa/unlock", null, "{\"pin\":\"1111\"}").status);
        assertEquals(403, c.handle("POST", "/k/tok-papa/unlock", null, "{\"pin\":\"4321\"}").status);
        assertTrue(state.locked);
        now.addAndGet(KidsLockCore.FAIL_WINDOW_MS + 1);
        assertEquals(200, c.handle("POST", "/k/tok-papa/unlock", null, "{\"pin\":\"4321\"}").status);
        assertFalse(state.locked);
    }

    @Test public void theRelayPassesOnTheRealPhoneName() {
        core().handle("POST", "/k/tok-relay/lock", "Mama", "");
        assertEquals("Mama", state.by);
        core().handle("POST", "/k/tok-papa/lock", "Mama", "");
        assertEquals("Papa", state.by);   // only the relay may speak for someone else
    }

    @Test public void theRealServerAnswersOverASocket() throws Exception {
        KidsLockServer server = new KidsLockServer(0, core());
        int port = server.start();
        try {
            assertEquals(200, call(port, "GET", "/k/tok-papa/state", null).code);
            assertEquals(404, call(port, "POST", "/k/bad/lock", null).code);
            Resp lock = call(port, "POST", "/k/tok-papa/lock", "");
            assertEquals(200, lock.code);
            assertTrue(lock.body.contains("\"locked\":true"));
            assertTrue(state.locked);
            assertEquals(403, call(port, "POST", "/k/tok-papa/unlock", "{\"pin\":\"9999\"}").code);
            assertTrue(call(port, "GET", "/k/tok-papa/", null).body.contains("\"web_kids_title\":\"Kids mode\""));
            assertEquals(200, call(port, "POST", "/k/tok-papa/unlock", "{\"pin\":\"4321\"}").code);
            assertFalse(state.locked);
        } finally {
            server.stop();
        }
    }

    @Test public void onlyHomeNetworkAddressesAreServed() throws Exception {
        assertTrue(KidsLockServer.fromHomeNetwork(java.net.InetAddress.getByName("192.168.1.20")));
        assertTrue(KidsLockServer.fromHomeNetwork(java.net.InetAddress.getByName("10.1.2.3")));
        assertTrue(KidsLockServer.fromHomeNetwork(java.net.InetAddress.getByName("172.20.0.5")));
        assertTrue(KidsLockServer.fromHomeNetwork(java.net.InetAddress.getByName("127.0.0.1")));
        assertFalse(KidsLockServer.fromHomeNetwork(java.net.InetAddress.getByName("8.8.8.8")));
        assertTrue(KidsLockServer.fromHomeNetwork(java.net.InetAddress.getByName("100.64.10.20")));      // Tailscale
        assertTrue(KidsLockServer.fromHomeNetwork(java.net.InetAddress.getByName("100.127.255.1")));
        assertFalse(KidsLockServer.fromHomeNetwork(java.net.InetAddress.getByName("100.63.0.1")));      // not in 100.64.0.0/10
        assertFalse(KidsLockServer.fromHomeNetwork(java.net.InetAddress.getByName("100.128.0.1")));
    }

    private static final class Resp {
        int code;
        String body;
    }

    private static Resp call(int port, String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        try (InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream()) {
            r.body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return r;
    }

    @Test
    public void autoUnlockRule() {
        long h = 60L * 60 * 1000;
        assertFalse("a fresh lock stays", KidsLockCore.expired(1_000, 1_000 + 11 * h, 12 * h));
        assertTrue("12 hours lifts it", KidsLockCore.expired(1_000, 1_000 + 12 * h, 12 * h));
        assertTrue("longer than 12 hours lifts it", KidsLockCore.expired(1_000, 1_000 + 30 * h, 12 * h));
        assertFalse("0 means never", KidsLockCore.expired(1_000, 1_000 + 1000 * h, 0));
        assertFalse("no start time means unknown, not expired", KidsLockCore.expired(0, 1_000 + 30 * h, 12 * h));
    }
}
