package com.gathin.tvhome;

import android.content.Context;

import com.gathin.tvhome.family.CloudCore;

/** What the cloud link may make the TV do, through the same hooks the phone pages on the home network use. */
final class CloudExecutor implements CloudCore.Executor {
    /** The name the lock and the journal show for a command that came through the cloud. */
    static final String BY = "cloud";

    private final Context ctx;

    CloudExecutor(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public boolean lock(String note) {
        boolean done = KidsLock.lockFromCloud(note, BY);
        if (done) new KidsStore(ctx).journal("cloud", "locked from the cloud");
        return done;
    }

    @Override
    public boolean unlock() {
        boolean done = KidsLock.unlockFromCloud(BY);
        if (done) new KidsStore(ctx).journal("cloud", "unlocked from the cloud");
        return done;
    }

    @Override
    public boolean message(String text) {
        KidsGuard.Messenger m = KidsGuard.messenger();
        if (m == null) return false;
        m.message(text);
        return true;
    }

    @Override
    public boolean pause() {
        KidsGuard.Messenger m = KidsGuard.messenger();
        if (m == null) return false;
        m.pause();
        return true;
    }

    @Override
    public boolean bonus(int minutes) {
        KidsStore store = new KidsStore(ctx);
        if (!store.enabled()) return false;
        KidsSettingsActivity.giveBonus(store, minutes);
        store.journal("cloud", "extra time from the cloud: " + minutes + " min");
        return true;
    }

    @Override
    public boolean powerOff(int delaySeconds, boolean cancel) {
        return SleepTimer.powerOff(ctx, cancel ? -1 : delaySeconds);
    }
}
