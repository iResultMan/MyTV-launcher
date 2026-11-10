package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * "Watch next" cards that apps publish to the TV provider (TvContract.WatchNextPrograms): the title, the poster, and the
 * intent that continues the film. Reading them needs READ_TV_LISTINGS, a runtime permission the user grants. Off by
 * default: the row costs memory on a 2 GB TV (watch_next in the "watch_next" preferences).
 */
final class WatchNext {
    private static final String TAG = "WatchNext";
    private static final String PREFS = "watch_next";
    static final int LIMIT = 20;
    /** Not in the public SDK; the TV provider declares it as a runtime permission. */
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";

    static final class Item {
        final String title, poster, intentUri, pkg;
        final long lastEngagement;
        final int type;

        Item(String title, String poster, String intentUri, String pkg, long lastEngagement, int type) {
            this.title = title;
            this.poster = poster;
            this.intentUri = intentUri;
            this.pkg = pkg;
            this.lastEngagement = lastEngagement;
            this.type = type;
        }
    }

    private WatchNext() {
    }

    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false);
    }

    static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply();
    }

    static boolean permitted(Context ctx) {
        return ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** The newest cards first, at most {@link #LIMIT}; empty without the permission or when no app published any. */
    static List<Item> read(Context ctx) {
        List<Item> out = new ArrayList<>();
        if (!permitted(ctx)) return out;
        String[] columns = {
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE};
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, columns, null, null,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            while (c != null && c.moveToNext() && out.size() < LIMIT) {
                String title = c.getString(0), intent = c.getString(2);
                if (title == null || title.trim().isEmpty() || intent == null || intent.isEmpty()) continue;
                out.add(new Item(title.trim(), c.getString(1), intent, c.getString(3), c.getLong(4), c.getInt(5)));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot read the watch-next cards: " + e);
        }
        return out;
    }

    /**
     * The intent of a card, or null when it is not safe to start: another app wrote it, so it must not point into this
     * launcher (its settings and lock screens are not for other apps to open).
     */
    static Intent launchIntent(Context ctx, Item item) {
        try {
            Intent intent = Intent.parseUri(item.intentUri, 0);
            intent.setSelector(null);
            String target = intent.getComponent() != null ? intent.getComponent().getPackageName() : intent.getPackage();
            if (ctx.getPackageName().equals(target)) return null;
            return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
