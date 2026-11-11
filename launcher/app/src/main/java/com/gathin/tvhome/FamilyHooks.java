package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.Toast;
import android.view.accessibility.AccessibilityEvent;

/**
 * The family features of HomeKeyService (children's lock, the message over apps, screen time) kept out of that
 * upstream class: it calls in at five places and nothing else here touches it.
 */
final class FamilyHooks {
    private final HomeKeyService service;
    /** Made on first use: the service has no context yet while it is being constructed. */
    private KidsStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The family message over every app (message_url); null without one. */
    private MessageOverlay messages;
    private int messageTries;
    /** The listener for KidsLock is on (it is, from the first config on). */
    private boolean kidsLockOn;
    /** key_filter: whether the service may filter keys at all. */
    private boolean keyFilterAllowed;

    private final KidsLock.Listener kidsListener;

    /** The helper is on the system's enabled list, which is what binds it. */
    static boolean serviceEnabled(android.content.Context ctx) {
        android.content.ContentResolver cr = ctx.getContentResolver();
        if (android.provider.Settings.Secure.getInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false;
        String list = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        String mine = new android.content.ComponentName(ctx, HomeKeyService.class).flattenToShortString();
        for (String entry : list.split(":")) {
            android.content.ComponentName name = android.content.ComponentName.unflattenFromString(entry);
            if (name != null && name.flattenToShortString().equals(mine)) return true;
        }
        return false;
    }

    /**
     * With the schedule on, a helper that went off (a child in the system settings, a force stop) is written to the
     * journal and put back by ServiceRepair, which needs the install's WRITE_SECURE_SETTINGS.
     */
    static void heal(android.content.Context ctx, KidsStore store) {
        boolean on = serviceEnabled(ctx);
        store.serviceSeen(on);
        if (on) ServiceRepair.connected(ctx, HomeKeyService.class);
        else ServiceRepair.restore(ctx);
    }

    /** The schedule is on: the helper is noted for ServiceRepair and stays noted when Android unbinds it. */
    boolean protecting() {
        if (store == null) store = new KidsStore(service);
        return store.enabled();
    }

    /** The schedule's warning before the end: over the app, with the video paused. */
    private final KidsGuard.Listener warnings;

    private final QuickMenu quickMenu;

    FamilyHooks(HomeKeyService service) {
        this.service = service;
        quickMenu = new QuickMenu(service, handler, true);
        warnings = minutes -> {
            if (messages != null) messages.showLocal(service.getString(R.string.kids_warn, minutes), 20);
        };
        kidsListener = isLocked -> {
            if (isLocked) service.performGlobalAction(HomeKeyService.GLOBAL_ACTION_HOME);
            if (messages != null) messages.onKidsLock(isLocked);
            updateKeyFilter();
        };
    }

    /** Called from loadConfig once the config is read and the app keys are parsed. */
    void configure(Config config) {
        keyFilterAllowed = config.keyFilter;
        KidsLock.configure(service, Config.overlay(service));
        UsageLog.start(service);
        // Window changes feed the screen-time log (and children's mode, which adds the same type).
        addWindowEvents();
        startMessages();
        if (!kidsLockOn) {
            kidsLockOn = true;
            KidsLock.add(kidsListener);
            KidsGuard.addListener(warnings);
        }
        KidsGuard.start(service);
        quickMenu.configure(Config.overlay(service));
        CloudClient.sync(service);
        // The sleep timer needs the lock-screen action, which Android has from 9.
        if (android.os.Build.VERSION.SDK_INT >= 28) SleepTimer.setActor(sleepActor);
        updateKeyFilter();
        if (store == null) store = new KidsStore(service);
        store.serviceSeen(true);
    }

    /** What the sleep timer calls: the TV to standby, and the warning a minute before. */
    private final SleepTimer.Actor sleepActor = new SleepTimer.Actor() {
        @Override
        public void sleepNow() {
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        }

        @Override
        public void warn(int extraMinutes, Runnable extend) {
            String text = service.getString(R.string.sleep_warn, extraMinutes);
            android.util.Log.i("SleepTimer", "warning shown " + (messages != null ? "over the apps" : "as a toast"));
            if (messages != null) messages.showSleepWarning(text, (int) (com.gathin.tvhome.family.SleepTimerCore.WARN_MS / 1000), () -> {
                extend.run();
                SleepTimer.tell(service, extraMinutes);
            });
            else Toast.makeText(service, text, Toast.LENGTH_LONG).show();
        }

        @Override
        public void adjust(int minutes) {
            if (messages != null) messages.showSleepAdjuster(SleepTimer.text(service, minutes), SleepTimer::step, m -> SleepTimer.text(service, m));
            else Toast.makeText(service, SleepTimer.text(service, minutes), Toast.LENGTH_LONG).show();
        }
    };

    private void addWindowEvents() {
        AccessibilityServiceInfo info = service.getServiceInfo();
        if (info != null && (info.eventTypes & AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) == 0) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            service.setServiceInfo(info);
        }
    }

    /** The family message overlay, once the theme with message_url can be read (right after an install it may not be yet). */
    private void startMessages() {
        if (messages != null) return;
        messages = MessageOverlay.create(service, Config.overlay(service), this::updateKeyFilter);
        if (messages != null) {
            messages.start();
            KidsGuard.setMessenger(new KidsGuard.Messenger() {
                @Override
                public void message(String text) {
                    messages.showLocal(text, 60);
                }

                @Override
                public void pause() {
                    messages.pauseNow();
                }
            });
            return;
        }
        if (++messageTries < 20) handler.postDelayed(this::startMessages, 15000);
    }

    /**
     * Keys go through onKeyEvent only while something here has to take them: the children's lock, a message a remote
     * key may dismiss, or app_keys. Each filtered key goes back to the system through its main thread, and on a busy
     * TV (TCL BeyondTV2, 2 GB) a release held up there past the 400 ms repeat timeout made Android auto-repeat the
     * press: one click moved the focus several rows or tiles. The rest of the time the remote's keys go straight to the
     * app, so the Settings button, ALL_APPS and the short Home of stock_home_redirect only act while filtering is on.
     * With gamepad_passthrough, GamepadPassthrough owns the flag, as before.
     */
    void updateKeyFilter() {
        if (service.gamepadsOwnKeyFilter()) return;
        AccessibilityServiceInfo info = service.getServiceInfo();
        if (info == null) return;
        boolean filter = keyFilterAllowed && (service.hasAppKeys() || (kidsLockOn && KidsLock.isLocked())
                || (messages != null && messages.takesKeys()) || quickMenu.wantsKeys());
        boolean filtering = (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
        if (filter == filtering) return;
        info.flags ^= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        service.setServiceInfo(info);
    }

    /** null: not ours, go on; otherwise what onKeyEvent returns. */
    Boolean onKey(KeyEvent event) {
        // The code pad over the lock screen takes its own keys.
        if (KidsGuard.pinEntry()) return false;
        // Children's mode: only volume, power and Home get through; any other key sends the TV back to Home, where the
        // lock screen is, and is not passed on to whatever app or input is in front.
        if (kidsLockOn && KidsLock.isLocked()) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN || key == KeyEvent.KEYCODE_VOLUME_MUTE
                    || key == KeyEvent.KEYCODE_POWER || key == KeyEvent.KEYCODE_TV_POWER || key == KeyEvent.KEYCODE_HOME
                    || key == KeyEvent.KEYCODE_SLEEP || key == KeyEvent.KEYCODE_WAKEUP) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) service.performGlobalAction(HomeKeyService.GLOBAL_ACTION_HOME);
            return true;
        }
        // A family message over the app: a key dismisses it once its hold time has passed (and is not passed on).
        if (messages != null && messages.onKey(event)) return true;
        if (quickMenu.onKey(event)) return true;
        return null;
    }

    /** A window-state event; true when it was dealt with and nothing else should act on it. */
    boolean onWindow(AccessibilityEvent event) {
        UsageLog.onWindow(event.getPackageName(), event.getClassName(), service.getPackageName());
        if (quickMenu.onWindow(event)) updateKeyFilter();
        // An app a parent closed for the children (with the schedule on) goes straight back to Home.
        CharSequence shown = event.getPackageName();
        if (store == null) store = new KidsStore(service);
        if (shown != null && store.appClosed(shown.toString())) {
            service.performGlobalAction(HomeKeyService.GLOBAL_ACTION_HOME);
            Toast.makeText(service, R.string.kids_app_closed, Toast.LENGTH_LONG).show();
            return true;
        }
        // Children's mode: a window that is not ours, the system UI or the system itself opened (an app, Settings).
        if (kidsLockOn && KidsLock.isLocked()) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null && !pkg.toString().equals(service.getPackageName()) && !pkg.toString().equals("com.android.systemui")
                    && !pkg.toString().equals("android")) {
                service.performGlobalAction(HomeKeyService.GLOBAL_ACTION_HOME);
                return true;
            }
        }
        return false;
    }

    void destroy() {
        // Off by the user or the system: the list says; an update of the app leaves it on the list.
        if (store == null) store = new KidsStore(service);
        store.serviceSeen(serviceEnabled(service));
        if (kidsLockOn) {
            kidsLockOn = false;
            KidsLock.remove(kidsListener);
            KidsGuard.removeListener(warnings);
        }
        handler.removeCallbacksAndMessages(null);
        SleepTimer.setActor(null);
        UsageLog.stop();
        KidsGuard.setMessenger(null);
        if (messages != null) {
            messages.stop();
            messages = null;
        }
    }
}
