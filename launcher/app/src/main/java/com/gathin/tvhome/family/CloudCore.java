package com.gathin.tvhome.family;

import java.util.Map;

/**
 * The rules of the cloud link (docs/cloud-protocol.md) with nothing from Android in them: what a command from the server
 * may do and what the TV answers, the state it sends, how long it waits after a failure and which server addresses it
 * accepts. The TV decides: a command is a request, and it is only carried out while the Cloud control switch is on and
 * before it expires.
 */
public final class CloudCore {
    public static final int PROTOCOL = 1;
    public static final int TEXT_MAX = 140;
    public static final long RETRY_FIRST_MS = 5_000L;
    public static final long RETRY_MAX_MS = 5 * 60_000L;

    private CloudCore() {
    }

    /** One command from the poll answer. */
    public static final class Command {
        public final long seq;
        public final String id;
        public final String type;
        public final Map<String, Object> args;
        public final long expiresAt;

        Command(long seq, String id, String type, Map<String, Object> args, long expiresAt) {
            this.seq = seq;
            this.id = id;
            this.type = type;
            this.args = args;
            this.expiresAt = expiresAt;
        }
    }

    /** What the TV answers a command with: result is ok, failed or expired. */
    public static final class Result {
        public final String result;
        public final String detail;

        Result(String result, String detail) {
            this.result = result;
            this.detail = detail;
        }

        static Result ok(String detail) {
            return new Result("ok", detail);
        }

        static Result failed(String detail) {
            return new Result("failed", detail);
        }
    }

    /** What the TV can be told to do; each answers whether it did it (false: it could not, for a reason of its own). */
    public interface Executor {
        boolean lock(String note);

        /** Lifts a lock set from a phone; false when only the schedule is blocking. */
        boolean unlock();

        boolean message(String text);

        boolean pause();

        /** Extra time for today's schedule; false when no schedule is on. */
        boolean bonus(int minutes);

        boolean powerOff(int delaySeconds, boolean cancel);
    }

    /** The command in a poll answer's entry, or null when it lacks an id, a type or a sequence number. */
    public static Command command(Map<String, Object> entry) {
        if (entry == null) return null;
        String id = MiniJson.string(entry, "id");
        String type = MiniJson.string(entry, "type");
        long seq = MiniJson.integer(entry, "seq", -1);
        if (id == null || id.isEmpty() || id.length() > 64 || type == null || type.isEmpty() || seq < 0) return null;
        Map<String, Object> args = MiniJson.object(entry.get("args"));
        return new Command(seq, id, type, args == null ? java.util.Collections.<String, Object>emptyMap() : args,
                MiniJson.integer(entry, "expires_at", 0));
    }

    /** Applies one command, or says why not. enabled is the Cloud control switch; now is the TV's clock in milliseconds. */
    public static Result apply(Command c, long now, boolean enabled, Executor ex) {
        if (!enabled) return Result.failed("cloud control is off");
        if (c.expiresAt > 0 && now > c.expiresAt) return new Result("expired", "");
        try {
            switch (c.type) {
                case "lock": {
                    String note = MiniJson.string(c.args, "note");
                    if (note != null && note.length() > TEXT_MAX) return Result.failed("note is longer than " + TEXT_MAX);
                    return ex.lock(note == null ? "" : note.trim()) ? Result.ok("") : Result.failed("could not lock");
                }
                case "unlock":
                    return ex.unlock() ? Result.ok("") : Result.failed("the schedule blocks the TV, give time instead");
                case "message": {
                    String text = MiniJson.string(c.args, "text");
                    if (text == null || text.trim().isEmpty()) return Result.failed("no text");
                    if (text.length() > TEXT_MAX) return Result.failed("text is longer than " + TEXT_MAX);
                    return ex.message(text.trim()) ? Result.ok("") : Result.failed("the helper on the TV is off");
                }
                case "pause":
                    return ex.pause() ? Result.ok("") : Result.failed("the helper on the TV is off");
                case "bonus": {
                    long minutes = MiniJson.integer(c.args, "minutes", -1);
                    if (minutes < 5 || minutes > 120) return Result.failed("minutes must be 5 to 120");
                    return ex.bonus((int) minutes) ? Result.ok("") : Result.failed("no schedule is on");
                }
                case "power_off": {
                    boolean cancel = Boolean.TRUE.equals(c.args.get("cancel"));
                    long delay = MiniJson.integer(c.args, "delay_s", 0);
                    if (!cancel && (delay < 0 || delay > 86_400)) return Result.failed("delay_s must be 0 to 86400");
                    return ex.powerOff((int) delay, cancel) ? Result.ok("") : Result.failed("the helper on the TV is off");
                }
                default:
                    return Result.failed("unknown command");
            }
        } catch (RuntimeException e) {
            return Result.failed("error");
        }
    }

    /** How long to wait after this many failures in a row: 5 seconds, doubling up to 5 minutes; 0 for none. */
    public static long retryDelayMs(int failures) {
        if (failures <= 0) return 0;
        long delay = RETRY_FIRST_MS;
        for (int k = 1; k < failures && delay < RETRY_MAX_MS; k++) delay *= 2;
        return Math.min(delay, RETRY_MAX_MS);
    }

    /**
     * Whether the address may be the cloud server: https, a host, an optional port and path, nothing else. A plain http
     * address is only for a test machine (loopback), and only when the caller says it is a debug build or a test.
     */
    public static boolean validUrl(String url, boolean allowLoopbackHttp) {
        if (url == null || url.length() > 200) return false;
        for (int k = 0; k < url.length(); k++) {
            char ch = url.charAt(k);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch) || ch == '@' || ch == '?' || ch == '#' || ch == '\\') return false;
        }
        String rest;
        boolean plain = false;
        if (url.startsWith("https://")) rest = url.substring(8);
        else if (allowLoopbackHttp && url.startsWith("http://")) {
            rest = url.substring(7);
            plain = true;
        } else return false;
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        if (authority.isEmpty() || authority.startsWith(":")) return false;
        String host = authority;
        int colon = authority.lastIndexOf(':');
        if (!authority.startsWith("[") && colon >= 0) {
            host = authority.substring(0, colon);
            String port = authority.substring(colon + 1);
            if (port.isEmpty() || port.length() > 5 || !port.matches("[0-9]+")) return false;
        }
        if (host.isEmpty()) return false;
        if (plain && !(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]"))) return false;
        return true;
    }

    /**
     * The state the TV sends (POST /v1/device/state). journalJson is the last events as a JSON array and daysJson the day
     * table (null: not sent); both are already JSON.
     */
    public static String stateJson(boolean locked, boolean scheduled, boolean service, boolean screenOn, long since,
                                   String pkg, String label, String journalJson, String daysJson) {
        StringBuilder out = new StringBuilder("{\"locked\":").append(locked).append(",\"scheduled\":").append(scheduled)
                .append(",\"service\":").append(service)
                .append(",\"screen\":{\"on\":").append(screenOn).append(",\"since\":").append(since)
                .append(",\"pkg\":").append(MiniJson.quote(pkg == null ? "" : pkg))
                .append(",\"label\":").append(MiniJson.quote(label == null ? "" : label)).append('}')
                .append(",\"journal\":").append(journalJson == null || journalJson.isEmpty() ? "[]" : journalJson);
        if (daysJson != null) out.append(",\"days\":").append(daysJson);
        return out.append('}').toString();
    }
}
