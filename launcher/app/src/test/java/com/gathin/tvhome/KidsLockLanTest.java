package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.gathin.tvhome.family.LanPairing;

import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** The phone-on-the-home-network routes: the page, the pairing, and calls that carry the token in a header only. */
public class KidsLockLanTest {
    private static final String SALT = "00112233445566ff";

    private static final class Mem implements KidsLockCore.State {
        boolean locked;
        String by = "";
        String note = "";

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

        @Override public String note() {
            return note;
        }

        @Override public void setNote(String n) {
            note = n;
        }
    }

    private static final class Phones implements LanPairing.Store {
        List<LanPairing.Phone> list = new ArrayList<>();

        @Override public List<LanPairing.Phone> load() {
            return new ArrayList<>(list);
        }

        @Override public void save(List<LanPairing.Phone> p) {
            list = new ArrayList<>(p);
        }
    }

    private final Mem state = new Mem();
    private final AtomicLong now = new AtomicLong(1000);
    private final LanPairing pairing = new LanPairing(new Phones(), now::get);
    private final List<String> shown = new ArrayList<>();
    private boolean helperOn = true;
    private int pauses;
    private final KidsLockCore core = makeCore();

    private KidsLockCore makeCore() {
        KidsLockCore c = new KidsLockCore(new LinkedHashMap<>(), SALT, KidsLockCore.hashPin("4321", SALT), state, now::get);
        c.setLan(pairing, new KidsLockCore.LanActions() {
            @Override public boolean message(String text) {
                if (helperOn) shown.add(text);
                return helperOn;
            }

            @Override public boolean pause() {
                if (helperOn) pauses++;
                return helperOn;
            }
        });
        return c;
    }

    private String pairPhone(String name) {
        String code = pairing.newCode();
        KidsLockCore.Reply r = core.handle("POST", "/api/pair", null, "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}", null);
        assertEquals(200, r.status);
        String token = r.body.replaceAll(".*\"token\":\"([0-9a-f]+)\".*", "$1");
        assertEquals(64, token.length());
        return token;
    }

    private KidsLockCore.Reply call(String method, String path, String token, String body) {
        return core.handle(method, path, null, body, token == null ? null : "Bearer " + token);
    }

    @Test public void thePageIsOpenToAnyoneOnTheNetworkAndHoldsNoSecret() {
        KidsLockCore.Reply r = core.handle("GET", "/", null, null, null);
        assertEquals(200, r.status);
        assertTrue(r.type.startsWith("text/html"));
        assertFalse(r.body.contains(pairing.newCode()));
        assertEquals(200, core.handle("GET", "/?c=123456", null, null, null).status);
    }

    @Test public void apiCallsWithoutAValidTokenGet401() {
        assertEquals(401, call("GET", "/api/state", null, null).status);
        assertEquals(401, call("GET", "/api/state", "deadbeef", null).status);
        assertEquals(401, core.handle("GET", "/api/state", null, null, "Basic abc").status);
        assertEquals(401, call("POST", "/api/lock", null, "").status);
        assertFalse(state.locked);
    }

    @Test public void aTokenInTheAddressIsNotAccepted() {
        String token = pairPhone("Mama");
        assertEquals(401, core.handle("GET", "/api/state?token=" + token, null, null, null).status);
        assertEquals(401, core.handle("GET", "/api/" + token + "/state", null, null, null).status);
        assertEquals(404, core.handle("GET", "/k/" + token + "/state", null, null, null).status);
    }

    @Test public void pairingGivesATokenThatWorksAndTheCodeWorksOnce() {
        String code = pairing.newCode();
        String body = "{\"code\":\"" + code + "\",\"name\":\"Papa\"}";
        KidsLockCore.Reply ok = core.handle("POST", "/api/pair", null, body, null);
        assertEquals(200, ok.status);
        assertEquals(403, core.handle("POST", "/api/pair", null, body, null).status);
        String token = ok.body.replaceAll(".*\"token\":\"([0-9a-f]+)\".*", "$1");
        KidsLockCore.Reply s = call("GET", "/api/state", token, null);
        assertEquals(200, s.status);
        assertTrue(s.body.contains("\"locked\":false"));
    }

    @Test public void stateShowsTheHelperAndWrongCodesAreJournaled() {
        List<String> journal = new ArrayList<>();
        core.setHealth(() -> "\"service\":" + helperOn + ",\"journal\":[]", (kind, text) -> journal.add(kind));
        String token = pairPhone("Mama");
        assertTrue(call("GET", "/api/state", token, null).body.contains("\"service\":true,\"journal\":[]}"));
        helperOn = false;
        KidsLockCore.Reply s = call("GET", "/api/state", token, null);
        assertEquals(200, s.status);
        assertTrue(s.body.contains("\"service\":false"));
        assertEquals(403, call("POST", "/api/unlock", token, "{\"pin\":\"0000\"}").status);
        assertEquals(403, core.handle("POST", "/api/pair", null, "{\"code\":\"000000\",\"name\":\"X\"}", null).status);
        assertEquals(java.util.Arrays.asList("paired", "unlock_fail", "pair_fail"), journal);
    }

    @Test public void theDayTableNeedsATokenAndComesFromTheSupplier() {
        assertEquals(401, call("GET", "/api/screentime", null, null).status);
        String token = pairPhone("Mama");
        assertEquals(404, call("GET", "/api/screentime", token, null).status);
        core.setScreenTime(() -> "[{\"day\":\"2026-11-01\",\"s\":60,\"apps\":[]}]");
        KidsLockCore.Reply r = call("GET", "/api/screentime", token, null);
        assertEquals(200, r.status);
        assertEquals("{\"days\":[{\"day\":\"2026-11-01\",\"s\":60,\"apps\":[]}]}", r.body);
    }

    @Test public void settingsExportAndRestoreNeedATokenAndRefuseJunk() {
        java.util.List<String> restored = new ArrayList<>();
        assertEquals(401, call("GET", "/api/settings", null, null).status);
        String token = pairPhone("Mama");
        assertEquals(404, call("GET", "/api/settings", token, null).status);
        core.setSettings(() -> "{\"v\":1,\"settings\":{\"columns\":5}}", json -> json.contains("\"v\":1") && restored.add(json));
        KidsLockCore.Reply r = call("GET", "/api/settings", token, null);
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"columns\":5"));
        assertEquals(400, call("POST", "/api/settings", token, "not json").status);
        assertEquals(200, call("POST", "/api/settings", token, "{\"v\":1,\"settings\":{}}").status);
        assertEquals(1, restored.size());
        assertEquals(401, call("POST", "/api/settings", null, "{\"v\":1,\"settings\":{}}").status);
    }

    @Test public void aLicenceKeyIsSubmittedWithAToken() {
        assertEquals(401, call("POST", "/api/license", null, "{\"key\":\"TVH1.a.b\"}").status);
        String token = pairPhone("Mama");
        assertEquals(404, call("GET", "/api/license", token, null).status);
        core.setLicense(() -> "Plan: Free", key -> key.equals("TVH1.good.key") ? "Plan: Home" : null);
        assertEquals("{\"plan\":\"Plan: Free\"}", call("GET", "/api/license", token, null).body);
        assertEquals(400, call("POST", "/api/license", token, "{\"key\":\"TVH1.bad.key\"}").status);
        assertEquals(400, call("POST", "/api/license", token, "{}").status);
        KidsLockCore.Reply ok = call("POST", "/api/license", token, "{\"key\":\"TVH1.good.key\"}");
        assertEquals(200, ok.status);
        assertEquals("{\"plan\":\"Plan: Home\"}", ok.body);
    }

    @Test public void powerOffNeedsATokenAndReportsTheHelperAndTheJournal() {
        List<Integer> asked = new ArrayList<>();
        List<String> journal = new ArrayList<>();
        core.setHealth(() -> "\"service\":true,\"journal\":[]", (kind, text) -> journal.add(kind + ":" + text));
        assertEquals(401, call("POST", "/api/poweroff", null, "{}").status);
        String token = pairPhone("Mama");
        journal.clear();
        assertEquals(404, call("POST", "/api/poweroff", token, "{}").status);          // nothing wired yet
        core.setPowerOff(sec -> helperOn && asked.add(sec));
        assertEquals(200, call("POST", "/api/poweroff", token, "{}").status);          // no delay: now
        assertEquals(200, call("POST", "/api/poweroff", token, "{\"delay_s\":1800}").status);
        assertEquals(200, call("POST", "/api/poweroff", token, "{\"cancel\":true}").status);
        assertEquals(java.util.Arrays.asList(0, 1800, -1), asked);
        assertEquals(java.util.Arrays.asList("poweroff:TV turned off from Mama", "poweroff:TV will turn off in 1800 s, asked by Mama",
                "poweroff_cancel:sleep timer cancelled from Mama"), journal);
        helperOn = false;
        assertEquals(503, call("POST", "/api/poweroff", token, "{\"delay_s\":5}").status);
        assertEquals(3, asked.size());
        assertEquals(404, call("GET", "/api/poweroff", token, null).status);
    }

    @Test public void powerOffRefusesDelaysOutOfRange() {
        List<Integer> asked = new ArrayList<>();
        core.setPowerOff(asked::add);
        String token = pairPhone("Mama");
        assertEquals(400, call("POST", "/api/poweroff", token, "{\"delay_s\":86401}").status);
        assertEquals(400, call("POST", "/api/poweroff", token, "{\"delay_s\":-5}").status);
        assertEquals(400, call("POST", "/api/poweroff", token, "{\"delay_s\":99999999999}").status);
        assertEquals(400, call("POST", "/api/poweroff", token, "{\"delay_s\":\"soon\"}").status);
        assertEquals(200, call("POST", "/api/poweroff", token, "{\"delay_s\":86400}").status);
        assertEquals(java.util.Arrays.asList(86400), asked);
    }

    @Test public void aWrongCodeIsRefused() {
        pairing.newCode();
        assertEquals(403, core.handle("POST", "/api/pair", null, "{\"code\":\"000000\",\"name\":\"X\"}", null).status);
        assertEquals(403, core.handle("POST", "/api/pair", null, "{}", null).status);
    }

    @Test public void lockNeedsOnlyTheTokenUnlockAlsoNeedsTheCode() {
        String token = pairPhone("Mama");
        KidsLockCore.Reply lock = call("POST", "/api/lock", token, "{\"note\":\"Dinner time\"}");
        assertEquals(200, lock.status);
        assertTrue(state.locked);
        assertEquals("Mama", state.by);
        assertEquals("Dinner time", state.note);
        assertEquals(403, call("POST", "/api/unlock", token, "{\"pin\":\"1111\"}").status);
        assertTrue(state.locked);
        assertEquals(200, call("POST", "/api/unlock", token, "{\"pin\":\"4321\"}").status);
        assertFalse(state.locked);
    }

    @Test public void aRevokedPhoneStopsAtOnce() {
        String token = pairPhone("Mama");
        assertEquals(200, call("GET", "/api/state", token, null).status);
        pairing.revoke(pairing.phones().get(0).id);
        assertEquals(401, call("GET", "/api/state", token, null).status);
    }

    @Test public void aMessageAndAPauseReachTheHelperOr503() {
        String token = pairPhone("Mama");
        assertEquals(200, call("POST", "/api/message", token, "{\"text\":\"Time for bed\"}").status);
        assertEquals("Time for bed", shown.get(0));
        assertEquals(400, call("POST", "/api/message", token, "{\"text\":\"\"}").status);
        assertEquals(200, call("POST", "/api/pause", token, "{}").status);
        assertEquals(1, pauses);
        helperOn = false;
        assertEquals(503, call("POST", "/api/message", token, "{\"text\":\"x\"}").status);
        assertEquals(503, call("POST", "/api/pause", token, "{}").status);
    }

    @Test public void aLongMessageIsCut() {
        String token = pairPhone("Mama");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 300; i++) text.append('a');
        call("POST", "/api/message", token, "{\"text\":\"" + text + "\"}");
        assertEquals(KidsLockCore.NOTE_MAX, shown.get(0).length());
    }

    @Test public void theOldAddressStyleStillWorksForPhonesNamedInTheTheme() {
        java.util.Map<String, String> theme = new LinkedHashMap<>();
        theme.put("papa", "tok-papa");
        KidsLockCore both = new KidsLockCore(theme, SALT, KidsLockCore.hashPin("4321", SALT), state, now::get);
        both.setLan(pairing, null);
        assertEquals(200, both.handle("GET", "/k/tok-papa/state", null, null).status);
        assertEquals(404, both.handle("GET", "/k/other/state", null, null).status);
    }

    @Test public void overARealSocketTheHeaderIsReadAndNoHeaderIs401() throws Exception {
        String token = pairPhone("Mama");
        KidsLockServer server = new KidsLockServer(0, core);
        int port = server.start();
        try {
            assertEquals(401, http(port, "GET", "/api/state", null, null).code);
            assertEquals(401, http(port, "GET", "/api/state?token=" + token, null, null).code);
            assertEquals(200, http(port, "GET", "/api/state", "Bearer " + token, null).code);
            assertEquals(200, http(port, "GET", "/", null, null).code);
            assertEquals(200, http(port, "POST", "/api/lock", "Bearer " + token, "{}").code);
            assertTrue(state.locked);
        } finally {
            server.stop();
        }
    }

    private static final class Resp {
        int code;
        String body;
    }

    private static Resp http(int port, String method, String path, String auth, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        if (auth != null) c.setRequestProperty("Authorization", auth);
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
}
