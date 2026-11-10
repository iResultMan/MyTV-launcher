package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Watch next: the switch (off by default), the permission, and the cards as a shelf of posters. OK on a card continues
 * it in the app that published it. Home's "Watch next" pill opens this screen once the feature is on.
 */
public final class WatchNextActivity extends Activity {
    private static final int REQUEST = 7;
    private LinearLayout list;
    private PosterLoader posters;
    private float d;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        d = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101419);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = (int) (72 * d);
        list.setPadding(side, (int) (38 * d), side, (int) (38 * d));
        scroll.addView(list);
        setContentView(scroll);
        posters = new PosterLoader(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    @Override
    protected void onDestroy() {
        posters.shutdown();
        super.onDestroy();
    }

    private void build() {
        list.removeAllViews();
        TextView title = new TextView(this);
        title.setText(R.string.watch_next);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(34);
        list.addView(title);
        boolean on = WatchNext.enabled(this);
        View toggle = row(getString(on ? R.string.watch_next_on : R.string.watch_next_off), () -> {
            WatchNext.setEnabled(this, !WatchNext.enabled(this));
            build();
        });
        if (!on) return;
        if (!WatchNext.permitted(this)) {
            row(getString(R.string.watch_next_grant), () -> requestPermissions(new String[] {WatchNext.PERMISSION}, REQUEST));
            return;
        }
        List<WatchNext.Item> items = WatchNext.read(this);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.watch_next_empty);
            empty.setTextColor(0xFFB0B8C4);
            empty.setTextSize(22);
            empty.setPadding(0, (int) (24 * d), 0, 0);
            list.addView(empty);
            return;
        }
        LinearLayout shelf = new LinearLayout(this);
        shelf.setOrientation(LinearLayout.HORIZONTAL);
        shelf.setPadding((int) (24 * d), (int) (28 * d), (int) (24 * d), (int) (24 * d));
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.addView(shelf);
        list.addView(scroller);
        View first = null;
        for (WatchNext.Item item : items) {
            View card = card(item);
            card.setNextFocusUpId(toggle.getId());
            shelf.addView(card);
            if (first == null) first = card;
        }
        toggle.setNextFocusDownId(first.getId());
        first.requestFocus();
    }

    /** A poster 170 x 255 dp with the title under it; focus lifts and lights the poster. */
    private View card(WatchNext.Item item) {
        LinearLayout card = new LinearLayout(this);
        card.setId(View.generateViewId());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        int w = (int) (170 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int) (22 * d));
        card.setLayoutParams(lp);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0xFF1E2430);
        card.addView(poster, new LinearLayout.LayoutParams(w, (int) (255 * d)));
        TextView name = new TextView(this);
        name.setText(item.title);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(15);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, (int) (10 * d), 0, 0);
        card.addView(name);
        TextView app = new TextView(this);
        app.setText(labelOf(item.pkg));
        app.setTextColor(0xFF8C96A5);
        app.setTextSize(13);
        app.setSingleLine(true);
        card.addView(app);
        card.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.07f : 1f).scaleY(focused ? 1.07f : 1f).setDuration(140).start();
            poster.setBackgroundColor(focused ? 0xFF3A4A63 : 0xFF1E2430);
            if (focused) v.getParent().requestChildFocus(v, v);
        });
        card.setOnClickListener(v -> open(item));
        if (item.poster == null) android.util.Log.w("WatchNext", "no poster for a card of " + item.pkg);
        if (item.poster != null) {
            posters.load(item.poster, w, (Bitmap b) -> {
                if (b != null && !isDestroyed()) poster.setImageBitmap(b);
            });
        }
        return card;
    }

    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pkg == null ? "" : pkg;
        }
    }

    private void open(WatchNext.Item item) {
        Intent intent = WatchNext.launchIntent(this, item);
        try {
            if (intent == null) throw new ActivityNotFoundException();
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.watch_next_cannot_open, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        build();
    }

    private TextView row(String text, Runnable onOk) {
        TextView row = new TextView(this, null, 0, R.style.SettingsRow);
        row.setId(View.generateViewId());
        ViewGroup.MarginLayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * d));
        lp.topMargin = (int) (14 * d);
        row.setLayoutParams(lp);
        row.setText(text);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setSingleLine(true);
        row.setOnClickListener(v -> onOk.run());
        list.addView(row);
        if (list.getChildCount() == 2) row.requestFocus();
        return row;
    }
}
