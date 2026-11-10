package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * The family message over every app: the same feed as the card on Home (FamilyMessage), drawn by the accessibility
 * helper as an overlay window, so it shows over a film too and needs no extra permission. It asks the server every
 * few seconds. A message options from the sender:
 *  - hold: seconds during which a remote key does not dismiss it (the key goes on to the app, so someone clicking
 *    the remote does not lose the message unread);
 *  - sticky: no remote key dismisses it, it hangs on screen until its time is up or it is deleted on the server;
 *  - pause: playback is paused when it appears and resumed once the message goes. Two ways at once: a transient audio
 *    focus request (well-behaved players of any app pause on losing focus and resume when it comes back) and a media
 *    key (apps with a media session, YouTube among them); when something was playing, a play key follows once the message goes.
 * After the hold, the first remote key dismisses it and is eaten, volume, power and Home aside.
 */
final class MessageOverlay {
    /** True while the overlay owns the messages: Home's own card stays quiet. */
    static volatile boolean running;
    private static final long POLL_MS = 8 * 1000L;

    private final AccessibilityService service;
    private final String url;
    /** Told when takesKeys() may have changed: the helper filters keys only while it is true. */
    private final Runnable keysChanged;
    private final WindowManager windows;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable expire = () -> hide(false);
    private HandlerThread thread;
    private volatile Handler bg;

    private LinearLayout view;
    private TextView textView, authorView, hintView;
    private String shownId = "";
    private long shownAt, holdMs;
    private boolean sticky;
    private boolean pausedByUs;
    private AudioFocusRequest focus;
    /** The key whose press dismissed the message: its release is eaten too. */
    private int eatUpOf = -1;
    /** Runs when a remote key dismisses the message now on screen (the sleep warning's "stay on"). */
    private Runnable dismissAction;
    /** The sleep timer's value on screen: Up and Down change it (step) and the text follows (textOf). */
    private java.util.function.IntUnaryOperator adjusterStep;
    private java.util.function.IntFunction<String> adjusterText;

    /** @return null when the theme cannot be read yet; without a message_url the overlay only shows local messages (showLocal). */
    static MessageOverlay create(AccessibilityService service, JSONObject theme, Runnable keysChanged) {
        if (theme == null) return null;
        return new MessageOverlay(service, theme.optString("message_url", "").trim(), keysChanged);
    }

    private MessageOverlay(AccessibilityService service, String url, Runnable keysChanged) {
        this.service = service;
        this.url = url;
        this.keysChanged = keysChanged;
        this.windows = service.getSystemService(WindowManager.class);
        this.audio = service.getSystemService(AudioManager.class);
    }

    /** A message made on the TV (a schedule warning): shown like one from the feed, not for the lock, closed by a key after two seconds. */
    void showLocal(String text, int seconds) {
        showLocal(text, seconds, 2, true, null);
    }

    /** The sleep timer's warning: no pause, a key answers it after a second, and onKey runs when one does. */
    void showSleepWarning(String text, int seconds, Runnable onKey) {
        showLocal(text, seconds, 1, false, onKey);
    }

    /** The sleep timer's value just set: Up and Down change it while it shows, any other key closes it. */
    void showSleepAdjuster(String text, java.util.function.IntUnaryOperator step, java.util.function.IntFunction<String> textOf) {
        main.post(() -> {
            if (KidsLock.isLocked()) return;
            try {
                dismissAction = null;
                show("local:" + SystemClock.elapsedRealtime(), new JSONObject().put("text", text).put("left", 6).put("hold", 0).put("pause", false));
                adjusterStep = step;
                adjusterText = textOf;
                hintView.setText(R.string.sleep_adjust_hint);
                hintView.setVisibility(View.VISIBLE);
                keysChanged.run();
            } catch (org.json.JSONException ignored) {
            }
        });
    }

    private void showLocal(String text, int seconds, int hold, boolean pause, Runnable onKey) {
        main.post(() -> {
            if (KidsLock.isLocked()) return;
            try {
                if (onKey != null) android.util.Log.i("SleepTimer", "overlay: showing the sleep warning");
                dismissAction = onKey;
                adjusterStep = null;
                show("local:" + SystemClock.elapsedRealtime(), new JSONObject().put("text", text).put("left", seconds).put("hold", hold).put("pause", pause));
            } catch (org.json.JSONException ignored) {
            }
        });
    }

    /** A parent's phone asked for a pause: the media key goes out, whatever plays. */
    void pauseNow() {
        main.post(() -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE));
    }

    void start() {
        if (thread != null || url.isEmpty()) return;
        running = true;
        thread = new HandlerThread("message-overlay");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                JSONObject msg = FamilyMessage.fetch(url);
                if (msg != null) main.post(() -> {
                    if (bg == h) apply(msg);
                });
                if (bg == h) h.postDelayed(this, POLL_MS);
            }
        });
    }

    void stop() {
        running = false;
        if (thread != null) {
            bg.removeCallbacksAndMessages(null);
            thread.quit();
            thread = null;
            bg = null;
        }
        main.post(() -> hide(false));
    }

    /** Children's mode: the lock screen owns the TV, a message waits for the unlock (or is gone by then). */
    void onKidsLock(boolean locked) {
        if (locked) main.post(() -> hide(false));
    }

    private void apply(JSONObject msg) {
        String id = msg.optString("id", "");
        if (id.isEmpty()) {
            // An empty feed removes the feed's own message, never a local one such as the sleep warning.
            if (view != null && !shownId.startsWith("local:")) hide(false);
            return;
        }
        if (id.equals(shownId) || FamilyMessage.isDismissed(id) || KidsLock.isLocked()) return;
        dismissAction = null;
        adjusterStep = null;
        show(id, msg);
    }

    private void show(String id, JSONObject msg) {
        if (view == null && !build()) {
            android.util.Log.w("MessageOverlay", "cannot add the overlay window");
            return;
        }
        String name = msg.optString("author", "");
        String time = msg.optString("time", "");
        textView.setText(msg.optString("text", ""));
        authorView.setText(name.isEmpty() ? time : name + " · " + time);
        authorView.setVisibility(name.isEmpty() && time.isEmpty() ? View.GONE : View.VISIBLE);
        shownId = id;
        sticky = msg.optBoolean("sticky", false);
        holdMs = Math.max(0, msg.optLong("hold", FamilyMessage.DEFAULT_HOLD_MS / 1000)) * 1000L;
        shownAt = SystemClock.elapsedRealtime();
        // The hint tells when a key will dismiss it: not at all for a sticky one, after the hold for the others.
        hintView.setText(R.string.family_message_hint);
        hintView.setVisibility(View.GONE);
        main.removeCallbacks(showHint);
        // A message that answers to a key (the sleep warning) says so in its own text, the dismiss hint would lie.
        if (!sticky) main.postDelayed(showHint, holdMs);
        view.setVisibility(View.VISIBLE);
        main.removeCallbacks(expire);
        main.postDelayed(expire, Math.max(5, msg.optLong("left", 600)) * 1000L);
        if (msg.optBoolean("pause", false)) pausePlayback();
        keysChanged.run();
    }

    private final Runnable showHint = this::holdOver;

    /** The hold is over: from now on a remote key dismisses the message. */
    private void holdOver() {
        if (view != null && view.getVisibility() == View.VISIBLE && dismissAction == null) hintView.setVisibility(View.VISIBLE);
        keysChanged.run();
    }

    private boolean build() {
        float d = service.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(service);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding((int) (36 * d), (int) (22 * d), (int) (36 * d), (int) (20 * d));
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(Color.argb(0xEB, 0x14, 0x18, 0x22));
        panel.setCornerRadius(24 * d);
        panel.setStroke((int) Math.max(1, d), Color.argb(0x33, 0xFF, 0xFF, 0xFF));
        box.setBackground(panel);

        TextView kicker = new TextView(service);
        kicker.setText(R.string.family_message_kicker);
        kicker.setTextColor(0xB3FFFFFF);
        kicker.setTextSize(11);
        kicker.setLetterSpacing(0.22f);
        kicker.setAllCaps(true);
        box.addView(kicker);

        textView = new TextView(service);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(34);
        textView.setGravity(Gravity.CENTER);
        textView.setMaxWidth((int) (640 * d));
        textView.setMaxLines(4);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = (int) (10 * d);
        box.addView(textView, tp);

        authorView = new TextView(service);
        authorView.setTextColor(0xB3FFFFFF);
        authorView.setTextSize(16);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ap.topMargin = (int) (8 * d);
        box.addView(authorView, ap);

        hintView = new TextView(service);
        hintView.setText(R.string.family_message_hint);
        hintView.setTextColor(0x80FFFFFF);
        hintView.setTextSize(12);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = (int) (10 * d);
        box.addView(hintView, hp);
        hintView.setVisibility(View.GONE);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (56 * d);
        try {
            windows.addView(box, lp);
        } catch (RuntimeException e) {
            return false;
        }
        view = box;
        return true;
    }

    /** Removes the message; dismissed (a remote key) it does not come back, expired or deleted it may with a new id. */
    private void hide(boolean dismissed) {
        main.removeCallbacks(expire);
        main.removeCallbacks(showHint);
        if (dismissed) FamilyMessage.markDismissed(shownId);
        Runnable onKey = dismissAction;
        dismissAction = null;
        adjusterStep = null;
        if (dismissed && onKey != null) onKey.run();
        shownId = "";
        if (view != null) {
            try {
                windows.removeView(view);
            } catch (RuntimeException ignored) {
            }
            view = null;
        }
        resumePlayback();
        keysChanged.run();
    }

    private void pausePlayback() {
        if (audio == null) return;
        boolean playing = audio.isMusicActive();
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(change -> { })
                    .build();
            audio.requestAudioFocus(focus);
        }
        // Something was playing when the message came: whichever way it stops (focus or key), it is started again later.
        if (pausedByUs || !playing) return;
        pausedByUs = true;
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    private void resumePlayback() {
        if (focus != null && audio != null) {
            audio.abandonAudioFocusRequest(focus);
            focus = null;
        }
        if (!pausedByUs) return;
        pausedByUs = false;
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    private void sendMediaKey(int code) {
        long t = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    /** Whether the helper has to filter keys for the message: one a key may dismiss is up (sticky never is, nor during
     *  its hold, when keys go on to the app), or the release of the key that dismissed it is still to be eaten. */
    boolean takesKeys() {
        return eatUpOf != -1 || (view != null && !sticky && SystemClock.elapsedRealtime() - shownAt >= holdMs);
    }

    /** From the helper's key filter: true when the message took this key (dismissed on it, or eats its release). */
    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_UP && k == eatUpOf) {
            eatUpOf = -1;
            keysChanged.run();
            return true;
        }
        if (view == null || sticky) return false;
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE
                || k == KeyEvent.KEYCODE_POWER || k == KeyEvent.KEYCODE_TV_POWER || k == KeyEvent.KEYCODE_HOME
                || k == KeyEvent.KEYCODE_SLEEP || k == KeyEvent.KEYCODE_WAKEUP) return false;
        if (SystemClock.elapsedRealtime() - shownAt < holdMs) return false;
        if (adjusterStep != null && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                eatUpOf = k;
                if (e.getRepeatCount() == 0) {
                    int minutes = adjusterStep.applyAsInt(k == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1);
                    textView.setText(adjusterText.apply(minutes));
                    main.removeCallbacks(expire);
                    main.postDelayed(expire, minutes > 0 ? 6000L : 3000L);
                }
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            eatUpOf = k;
            hide(true);
        }
        return true;
    }
}
