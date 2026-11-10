package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.gathin.tvhome.family.CloudCore;
import com.gathin.tvhome.family.CloudHttp;
import com.gathin.tvhome.family.CloudSession;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The cloud link while Cloud control is on (docs/cloud-protocol.md): one thread registers the TV, long-polls for commands
 * and applies them through CloudExecutor; a second sends the state at start, on every lock change and every 60 seconds.
 * Nothing runs while the switch is off, and the only network use is HTTPS to the server of CloudStore.serverUrl.
 */
final class CloudClient {
    enum Status { OFF, CONNECTING, ONLINE, RETRYING, REFUSED }

    private static final String TAG = "CloudClient";
    private static final int POLL_WAIT_S = 25;

    private static Context app;
    private static Thread poller;
    private static ScheduledExecutorService pusher;
    private static volatile boolean running;
    private static volatile Status status = Status.OFF;
    private static volatile boolean pushDue;
    private static volatile String code = "";
    private static volatile long codeUntil;
    private static final KidsLock.Listener ON_LOCK = locked -> push();

    private CloudClient() {
    }

    static Status status() {
        return status;
    }

    /** The pairing code on screen and when it stops working (elapsed realtime), or "" when there is none. */
    static String code() {
        return SystemClock.elapsedRealtime() < codeUntil ? code : "";
    }

    /** Starts or stops the link to match the switch; called when the launcher's helper starts and whenever the switch changes. */
    static synchronized void sync(Context ctx) {
        app = ctx.getApplicationContext();
        boolean want = new CloudStore(app).enabled() && CloudStore.serverUrl(app) != null;
        if (want && !running) start();
        else if (!want && running) stop();
        else if (!want) status = Status.OFF;
    }

    /**
     * Turns Cloud control on or off. Off deletes this TV's data on the server and the secret here; done reports whether
     * the server confirmed (false: it could not be reached, and its copy goes after 30 days).
     */
    static void setEnabled(Context ctx, boolean on, Consumer<Boolean> done) {
        Context a = ctx.getApplicationContext();
        CloudStore store = new CloudStore(a);
        if (on) {
            store.setEnabled(true);
            sync(a);
            if (done != null) done.accept(true);
            return;
        }
        String url = CloudStore.serverUrl(a);
        store.setEnabled(false);
        synchronized (CloudClient.class) {
            if (running) stop();
        }
        status = Status.OFF;
        code = "";
        new Thread(() -> {
            // The secret goes with the delete call whatever the answer; with no usable address only the local data goes.
            CloudSession.Status s = url == null ? clearOnly(store) : new CloudSession(url, new CloudHttp(), store).deleteDevice();
            if (done != null) done.accept(s == CloudSession.Status.OK);
        }, "CloudOff").start();
    }

    private static CloudSession.Status clearOnly(CloudStore store) {
        store.clear();
        return CloudSession.Status.RETRY;
    }

    /** Asks the server for a new pairing code, on its own thread; the result arrives on done (null when the server did not give one). */
    static void requestCode(Consumer<String> done) {
        Context a = app;
        if (a == null) {
            done.accept(null);
            return;
        }
        new Thread(() -> {
            String url = CloudStore.serverUrl(a);
            CloudStore store = new CloudStore(a);
            if (url == null || !store.enabled()) {
                done.accept(null);
                return;
            }
            CloudSession session = new CloudSession(url, new CloudHttp(), store);
            if (session.register(deviceName(a)) != CloudSession.Status.OK) {
                done.accept(null);
                return;
            }
            CloudSession.PairCode pc = session.pairCode();
            if (pc.status == CloudSession.Status.UNAUTHORIZED) {
                store.clear();
                if (session.register(deviceName(a)) == CloudSession.Status.OK) pc = session.pairCode();
            }
            if (pc.status == CloudSession.Status.OK) {
                code = pc.code;
                codeUntil = SystemClock.elapsedRealtime() + pc.expiresIn * 1000L;
                done.accept(pc.code);
            } else {
                done.accept(null);
            }
        }, "CloudCode").start();
    }

    private static void start() {
        running = true;
        status = Status.CONNECTING;
        KidsLock.add(ON_LOCK);
        poller = new Thread(CloudClient::loop, "CloudPoll");
        poller.start();
        pusher = Executors.newSingleThreadScheduledExecutor();
        pusher.scheduleWithFixedDelay(CloudClient::pushIfDue, 2, 5, TimeUnit.SECONDS);
        pusher.scheduleWithFixedDelay(CloudClient::push, 60, 60, TimeUnit.SECONDS);
        push();
        Log.i(TAG, "cloud control on");
    }

    private static void stop() {
        running = false;
        KidsLock.remove(ON_LOCK);
        if (poller != null) poller.interrupt();
        if (pusher != null) pusher.shutdownNow();
        poller = null;
        pusher = null;
        Log.i(TAG, "cloud control off");
    }

    private static void push() {
        pushDue = true;
    }

    private static void pushIfDue() {
        Context a = app;
        if (!running || !pushDue || a == null) return;
        String url = CloudStore.serverUrl(a);
        CloudStore store = new CloudStore(a);
        if (url == null || !store.registered()) return;
        pushDue = false;
        CloudSession.Status s = new CloudSession(url, new CloudHttp(), store).pushState(state(a));
        if (s != CloudSession.Status.OK) pushDue = true;
    }

    private static String state(Context a) {
        KidsStore kids = new KidsStore(a);
        Object[] screen = UsageLog.screenNow();
        PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        boolean on = screen != null ? (Boolean) screen[0] : pm == null || pm.isInteractive();
        long since = screen != null ? (Long) screen[1] : 0L;
        return CloudCore.stateJson(KidsLock.lockedByHand(), KidsLock.scheduledOnly(), FamilyHooks.serviceEnabled(a), on, since,
                screen != null ? (String) screen[2] : "", screen != null ? (String) screen[3] : "", kids.journalJson(10), null);
    }

    private static void loop() {
        Context a = app;
        CloudStore store = new CloudStore(a);
        CloudExecutor executor = new CloudExecutor(a);
        long since = 0;
        int failures = 0;
        int unauthorized = 0;
        while (running) {
            String url = CloudStore.serverUrl(a);
            if (url == null || !store.enabled()) break;
            CloudSession session = new CloudSession(url, new CloudHttp(), store);
            CloudSession.Status s;
            if (!session.registered()) {
                s = session.register(deviceName(a));
                if (s == CloudSession.Status.OK) {
                    since = 0;
                    push();
                }
            } else {
                CloudSession.Step step = session.pollAndApply(since, POLL_WAIT_S, executor, () -> running && store.enabled(), System::currentTimeMillis);
                since = step.since;
                s = step.status;
            }
            switch (s) {
                case OK:
                    failures = 0;
                    unauthorized = 0;
                    status = Status.ONLINE;
                    break;
                case UNAUTHORIZED:
                    // The server no longer knows this TV: forget the identity and register again, a few times at most.
                    store.clear();
                    since = 0;
                    status = ++unauthorized > 3 ? Status.REFUSED : Status.CONNECTING;
                    failures = unauthorized > 3 ? 7 : 0;
                    break;
                case REJECTED:
                    status = Status.REFUSED;
                    failures = 7;
                    break;
                default:
                    status = Status.RETRYING;
                    failures++;
            }
            long wait = CloudCore.retryDelayMs(failures);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        if (!running) status = Status.OFF;
    }

    private static String deviceName(Context a) {
        String name = android.os.Build.MODEL;
        return name == null || name.trim().isEmpty() ? "TV" : name.trim();
    }
}
