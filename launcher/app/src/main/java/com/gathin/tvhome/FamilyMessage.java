package com.gathin.tvhome;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * A message for the family in the free middle of Home. The theme's message_url (the family-msg service on the
 * home server, GET /current) answers {} or {"id", "text", "author", "time", "left", "sticky", "hold"}; this asks
 * once a minute while Home is on screen. A message stays up for "left" seconds, or until a remote key dismisses it
 * once "hold" seconds have passed (a key pressed earlier goes on to Home, so a viewer clicking away does not lose the
 * message unread); a "sticky" one no key can dismiss. A dismissed one does not come back. With no message the card
 * is gone and Home looks as it always does. While MessageOverlay runs (the accessibility helper is on) it shows the
 * message over every app, Home included, and this card stays quiet.
 */
final class FamilyMessage {
    private static final long POLL_MS = 60 * 1000L;
    static final long DEFAULT_HOLD_MS = 4000L;
    /** The id the owner last dismissed, kept for the life of the process, shared with MessageOverlay. */
    private static volatile String dismissedId = "";

    private final String url;
    private final View card;
    private final TextView text, author;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable expire = this::hide;
    private HandlerThread thread;
    private volatile Handler bg;
    private String shownId = "";
    private long shownAt;
    private long holdMs = DEFAULT_HOLD_MS;
    private boolean sticky;
    /** Children's mode owns the card: no message is shown or fetched into it meanwhile. */
    private volatile boolean suspended;

    /** @return null when the theme has no message_url, or this layout has no card (the Hebrew one). */
    static FamilyMessage create(JSONObject theme, View card, TextView text, TextView author) {
        if (theme == null || card == null) return null;
        String url = theme.optString("message_url", "").trim();
        return url.isEmpty() ? null : new FamilyMessage(url, card, text, author);
    }

    private FamilyMessage(String url, View card, TextView text, TextView author) {
        this.url = url;
        this.card = card;
        this.text = text;
        this.author = author;
    }

    static boolean isDismissed(String id) {
        return id.equals(dismissedId);
    }

    static void markDismissed(String id) {
        if (!id.isEmpty()) dismissedId = id;
    }

    /** The message that is active now, as the server sends it ({} for none); null when the server does not answer. */
    static JSONObject fetch(String url) {
        if (!com.gathin.tvhome.family.NetRules.allowed(url)) {
            Log.w("FamilyMessage", "message_url refused: plain http is allowed only on the home network");
            return null;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[2048];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                return new JSONObject(buf.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (Exception e) {
            // The server is off or the network is down: nothing changes on screen.
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Children's mode takes the card (true) or gives it back (false, empty until the next message). */
    void suspend(boolean on) {
        suspended = on;
        if (on || visible()) hide();
    }

    boolean visible() {
        return card.getVisibility() == View.VISIBLE;
    }

    /** Whether a remote key dismisses the message now: it is up, not sticky and has been on screen for its hold time. */
    boolean takesKeys() {
        return visible() && !sticky && SystemClock.elapsedRealtime() - shownAt >= holdMs;
    }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("family-message");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                poll(h);
                if (bg == h) h.postDelayed(this, POLL_MS);
            }
        });
    }

    /** Stops asking; a shown message stays until it expires or is dismissed. */
    void stop() {
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    /** A remote key: the card goes and this message does not return. */
    void dismiss() {
        markDismissed(shownId);
        hide();
    }

    private void hide() {
        main.removeCallbacks(expire);
        card.setVisibility(View.GONE);
        shownId = "";
    }

    private void poll(Handler h) {
        JSONObject msg = fetch(url);
        if (msg == null) return;
        String id = msg.optString("id", "");
        main.post(() -> {
            if (bg != h || suspended) return;
            if (MessageOverlay.running) {
                if (visible()) hide();
                return;
            }
            if (id.isEmpty()) {
                if (visible()) hide();
            } else if (!isDismissed(id) && !id.equals(shownId)) {
                String name = msg.optString("author", "");
                String time = msg.optString("time", "");
                text.setText(msg.optString("text", ""));
                author.setText(name.isEmpty() ? time : name + " · " + time);
                author.setVisibility(name.isEmpty() && time.isEmpty() ? View.GONE : View.VISIBLE);
                shownId = id;
                sticky = msg.optBoolean("sticky", false);
                holdMs = Math.max(0, msg.optLong("hold", DEFAULT_HOLD_MS / 1000)) * 1000L;
                shownAt = SystemClock.elapsedRealtime();
                card.setVisibility(View.VISIBLE);
                main.removeCallbacks(expire);
                main.postDelayed(expire, Math.max(5, msg.optLong("left", 600)) * 1000L);
            }
        });
    }
}
