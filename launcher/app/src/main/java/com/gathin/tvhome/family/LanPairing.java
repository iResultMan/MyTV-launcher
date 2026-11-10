package com.gathin.tvhome.family;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Pairing of a phone with the TV on the home network: the TV shows a one-time code (in a QR), the phone sends it back
 * with a name and gets a long token. The token travels in an Authorization header, never in an address; the TV keeps
 * only its SHA-256, so a copy of the TV's storage does not hold usable tokens. A phone can be revoked on the TV at any
 * time. Five wrong codes in five minutes block pairing until the window has passed.
 */
public final class LanPairing {
    /** One paired phone as kept: a hash of its token, never the token. */
    public static final class Phone {
        public final String id;
        public final String name;
        public final String tokenHash;
        public final long pairedAt;

        public Phone(String id, String name, String tokenHash, long pairedAt) {
            this.id = id;
            this.name = name;
            this.tokenHash = tokenHash;
            this.pairedAt = pairedAt;
        }
    }

    /** Where the paired phones are kept between runs. */
    public interface Store {
        List<Phone> load();

        void save(List<Phone> phones);
    }

    public static final long CODE_TTL_MS = 5 * 60_000L;
    static final int CODE_DIGITS = 6;
    static final int MAX_FAILS = 5;
    static final long FAIL_WINDOW_MS = 5 * 60_000L;
    static final int MAX_PHONES = 8;
    static final int NAME_MAX = 30;

    private final Store store;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Deque<Long> fails = new ArrayDeque<>();
    private String code = "";
    private long codeUntil;

    public LanPairing(Store store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    /** A fresh one-time code for the QR; the one before it stops working. */
    public synchronized String newCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_DIGITS; i++) sb.append(random.nextInt(10));
        code = sb.toString();
        codeUntil = clock.getAsLong() + CODE_TTL_MS;
        return code;
    }

    /** Stops the code from working, once the pairing screen is closed. */
    public synchronized void closeCode() {
        code = "";
    }

    /** @return the new token for a phone that sent the right code, or null (wrong or old code, too many tries, no room). */
    public synchronized String pair(String given, String name) {
        long now = clock.getAsLong();
        while (!fails.isEmpty() && now - fails.peekFirst() > FAIL_WINDOW_MS) fails.pollFirst();
        if (fails.size() >= MAX_FAILS) return null;
        boolean ok = !code.isEmpty() && now <= codeUntil && given != null
                && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            fails.addLast(now);
            return null;
        }
        List<Phone> phones = new ArrayList<>(store.load());
        if (phones.size() >= MAX_PHONES) return null;
        code = "";
        String token = randomHex(32);
        String label = cleanName(name);
        phones.add(new Phone(randomHex(4), label, hash(token), now));
        store.save(phones);
        return token;
    }

    /** The paired phone this token belongs to, or null. */
    public synchronized Phone phoneOf(String token) {
        if (token == null || token.isEmpty()) return null;
        byte[] want = hash(token).getBytes(StandardCharsets.UTF_8);
        Phone found = null;
        for (Phone p : store.load()) {
            if (MessageDigest.isEqual(want, p.tokenHash.getBytes(StandardCharsets.UTF_8))) found = p;
        }
        return found;
    }

    public synchronized List<Phone> phones() {
        return Collections.unmodifiableList(new ArrayList<>(store.load()));
    }

    public synchronized void revoke(String id) {
        List<Phone> phones = new ArrayList<>(store.load());
        phones.removeIf(p -> p.id.equals(id));
        store.save(phones);
    }

    public synchronized void revokeAll() {
        store.save(new ArrayList<>());
    }

    private static String cleanName(String name) {
        String s = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").trim();
        if (s.isEmpty()) s = "phone";
        return s.length() > NAME_MAX ? s.substring(0, NAME_MAX) : s;
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return hex(b);
    }

    static String hash(String token) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }
}
