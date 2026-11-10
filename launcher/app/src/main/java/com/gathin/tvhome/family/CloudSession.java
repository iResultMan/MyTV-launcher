package com.gathin.tvhome.family;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The TV's side of the cloud link, call by call (docs/cloud-protocol.md): register, ask for a pairing code, send the
 * state, poll for commands, acknowledge them, and delete the TV's data on the server. It knows nothing of Android or of
 * sockets: the requests go through Http (CloudHttp on the TV, a fake in the tests) and the secret is kept by Credentials.
 */
public final class CloudSession {
    /** How a call ended: OK; UNAUTHORIZED (401: the server no longer knows this TV); REJECTED (400 and other refusals that retrying cannot fix: a malformed request or a protocol the server does not speak); RETRY (no answer, 429, 5xx). */
    public enum Status { OK, UNAUTHORIZED, REJECTED, RETRY }

    public interface Http {
        Response request(String method, String url, String bearer, String body, int timeoutMs) throws IOException;
    }

    public static final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    /** Where the TV's identity is kept (the launcher's private storage). */
    public interface Credentials {
        String deviceId();

        String secret();

        void save(String deviceId, String secret);

        void clear();
    }

    public static final class PairCode {
        public final Status status;
        public final String code;
        public final int expiresIn;

        PairCode(Status status, String code, int expiresIn) {
            this.status = status;
            this.code = code;
            this.expiresIn = expiresIn;
        }
    }

    /** One pass of poll, apply and acknowledge: how it ended and the highest command sequence the TV has now seen. */
    public static final class Step {
        public final Status status;
        public final long since;
        public final int applied;

        Step(Status status, long since, int applied) {
            this.status = status;
            this.since = since;
            this.applied = applied;
        }
    }

    private static final int CALL_MS = 10_000;
    private static final int POLL_EXTRA_MS = 8_000;

    private final String base;
    private final Http http;
    private final Credentials credentials;

    public CloudSession(String baseUrl, Http http, Credentials credentials) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
        this.credentials = credentials;
    }

    public boolean registered() {
        return notEmpty(credentials.deviceId()) && notEmpty(credentials.secret());
    }

    /** POST /v1/devices unless the TV already has an identity; OK once it has one. */
    public Status register(String name) {
        if (registered()) return Status.OK;
        Response r = send("POST", "/v1/devices", null, "{\"name\":" + MiniJson.quote(clip(name, 60)) + "}", CALL_MS);
        if (r == null) return Status.RETRY;
        Status s = status(r);
        if (s != Status.OK) return s;
        Map<String, Object> m = parse(r.body);
        String id = MiniJson.string(m, "device_id");
        String secret = MiniJson.string(m, "device_secret");
        if (!notEmpty(id) || !notEmpty(secret) || id.length() > 64 || secret.length() > 128) return Status.REJECTED;
        credentials.save(id, secret);
        return Status.OK;
    }

    /** POST /v1/pair/code: the code to show on the screen. */
    public PairCode pairCode() {
        Response r = send("POST", "/v1/pair/code", credentials.secret(), "{}", CALL_MS);
        if (r == null) return new PairCode(Status.RETRY, "", 0);
        Status s = status(r);
        if (s != Status.OK) return new PairCode(s, "", 0);
        Map<String, Object> m = parse(r.body);
        String code = MiniJson.string(m, "code");
        if (code == null || !code.matches("[0-9]{4,10}")) return new PairCode(Status.REJECTED, "", 0);
        return new PairCode(Status.OK, code, (int) MiniJson.integer(m, "expires_in", 300));
    }

    /** POST /v1/device/state with a state built by CloudCore.stateJson. */
    public Status pushState(String stateJson) {
        Response r = send("POST", "/v1/device/state", credentials.secret(), stateJson, CALL_MS);
        return r == null ? Status.RETRY : status(r);
    }

    /**
     * One long poll, then every command in the answer in order: each is applied (or refused) by CloudCore.apply and
     * acknowledged. A command is applied at most once: the sequence number moves on before the acknowledgement is sent.
     */
    public Step pollAndApply(long since, int waitSeconds, CloudCore.Executor executor, BooleanSupplier enabled, LongSupplier clock) {
        Response r = send("GET", "/v1/device/poll?since=" + since + "&wait=" + Math.max(0, Math.min(30, waitSeconds)),
                credentials.secret(), null, waitSeconds * 1000 + POLL_EXTRA_MS);
        if (r == null) return new Step(Status.RETRY, since, 0);
        Status s = status(r);
        if (s != Status.OK) return new Step(s, since, 0);
        Map<String, Object> m = parse(r.body);
        List<Object> entries = MiniJson.array(m == null ? null : m.get("commands"));
        long seen = Math.max(since, MiniJson.integer(m, "seq", since));
        int applied = 0;
        Status last = Status.OK;
        if (entries != null) {
            for (Object entry : entries) {
                CloudCore.Command c = CloudCore.command(MiniJson.object(entry));
                if (c == null) continue;
                if (c.seq <= since) continue;
                seen = Math.max(seen, c.seq);
                CloudCore.Result result = CloudCore.apply(c, clock.getAsLong(), enabled.getAsBoolean(), executor);
                applied++;
                Status a = ack(c.id, result);
                if (a == Status.UNAUTHORIZED) return new Step(a, seen, applied);
                if (a != Status.OK) last = a;
            }
        }
        return new Step(last == Status.RETRY ? Status.OK : last, seen, applied);
    }

    /** POST /v1/device/ack. */
    public Status ack(String id, CloudCore.Result result) {
        String body = "{\"id\":" + MiniJson.quote(id) + ",\"result\":" + MiniJson.quote(result.result)
                + ",\"detail\":" + MiniJson.quote(clip(result.detail, 80)) + "}";
        Response r = send("POST", "/v1/device/ack", credentials.secret(), body, CALL_MS);
        return r == null ? Status.RETRY : status(r);
    }

    /**
     * Turning the cloud control off: the TV's data on the server goes (DELETE /v1/devices/{id}) and the identity on the TV
     * goes with it, whatever the server answered, so that nothing of it stays here. The result says whether the server
     * confirmed (OK, also when it no longer knew the TV) or could not be reached (RETRY).
     */
    public Status deleteDevice() {
        String id = credentials.deviceId();
        String secret = credentials.secret();
        if (!notEmpty(id) || !notEmpty(secret)) {
            credentials.clear();
            return Status.OK;
        }
        Response r = send("DELETE", "/v1/devices/" + id, secret, null, CALL_MS);
        credentials.clear();
        if (r == null) return Status.RETRY;
        if (r.status == 404) return Status.OK;
        return status(r);
    }

    private Response send(String method, String path, String bearer, String body, int timeoutMs) {
        try {
            return http.request(method, base + path, bearer, body, timeoutMs);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Status status(Response r) {
        if (r.status >= 200 && r.status < 300) return Status.OK;
        if (r.status == 401) return Status.UNAUTHORIZED;
        if (r.status == 429 || r.status >= 500) return Status.RETRY;
        return Status.REJECTED;
    }

    private static Map<String, Object> parse(String body) {
        try {
            return MiniJson.object(MiniJson.parse(body));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
