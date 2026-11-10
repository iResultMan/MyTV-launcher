package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Home's first page when Watch next is on and apps have published cards: the focused card's title large, a row of
 * posters under it and three dots at the bottom that say the apps are one press down. Down hides the whole page and
 * shows the usual Home (MainActivity), Up from the app tiles brings it back; Up here reaches the top buttons. No
 * outlines: a focused poster grows and the others dim.
 */
final class NextShelf {
    /** What the shelf asks of Home. */
    interface Host {
        void leaveDown();

        void leaveUp();

        /** A card was opened: coming back lands on the shelf. */
        void opened();
    }

    private static final int SHOWN = 12;
    private final Activity activity;
    private final Host host;
    private final float d;
    private final PosterLoader posters;
    private final LinearLayout box;
    private final TextView title;
    private final TextView meta;
    private final LinearLayout row;
    private final HorizontalScrollView scroller;
    private List<WatchNext.Item> items = Collections.emptyList();
    private String signature = "";
    private int remembered;

    NextShelf(Activity activity, ViewGroup root, Host host) {
        this.activity = activity;
        this.host = host;
        d = activity.getResources().getDisplayMetrics().density;
        posters = new PosterLoader(activity);

        box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setPadding((int) (68 * d), 0, (int) (40 * d), 0);
        box.setVisibility(View.GONE);

        title = new TextView(activity);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(40);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth((int) (700 * d));
        title.setIncludeFontPadding(false);
        title.setTypeface(android.graphics.Typeface.create(activity.getString(R.string.font_light), android.graphics.Typeface.NORMAL));
        title.setLetterSpacing(-0.02f);
        box.addView(title);

        meta = new TextView(activity);
        meta.setTextColor(0xB3FFFFFF);
        meta.setTextSize(15);
        meta.setSingleLine(true);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = (int) (4 * d);
        box.addView(meta, mp);

        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding((int) (14 * d), (int) (20 * d), (int) (14 * d), (int) (20 * d));
        scroller = new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.addView(row);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = (int) (8 * d);
        sp.setMarginStart((int) (-14 * d));
        box.addView(scroller, sp);

        LinearLayout dots = new LinearLayout(activity);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            View dot = new View(activity);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(i == 1 ? 0xCCFFFFFF : 0x66FFFFFF);
            dot.setBackground(shape);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams((int) (7 * d), (int) (7 * d));
            dp.setMargins((int) (6 * d), 0, (int) (6 * d), 0);
            dots.addView(dot, dp);
        }
        LinearLayout.LayoutParams dotsP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsP.setMarginStart((int) (-68 * d));
        dotsP.setMarginEnd((int) (-40 * d));
        box.addView(dots, dotsP);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        lp.bottomMargin = (int) (26 * d);
        root.addView(box, lp);
    }

    /** True when the page has cards to show; rereads them, so call it when Home comes back. */
    boolean refresh() {
        List<WatchNext.Item> read = WatchNext.enabled(activity) && WatchNext.permitted(activity)
                ? WatchNext.read(activity) : Collections.<WatchNext.Item>emptyList();
        if (read.size() > SHOWN) read = new ArrayList<>(read.subList(0, SHOWN));
        StringBuilder sig = new StringBuilder();
        for (WatchNext.Item item : read) sig.append(item.title).append('|').append(item.intentUri).append('|').append(item.poster).append('\n');
        if (!sig.toString().equals(signature)) {
            signature = sig.toString();
            items = read;
            remembered = 0;
            build();
        }
        return !items.isEmpty();
    }

    boolean available() {
        return !items.isEmpty();
    }

    boolean shown() {
        return box.getVisibility() == View.VISIBLE;
    }

    boolean hasFocus() {
        return box.hasFocus();
    }

    /** Shows the page and puts focus on the card that had it last. */
    void show() {
        if (items.isEmpty()) return;
        box.setVisibility(View.VISIBLE);
        View card = row.getChildAt(Math.max(0, Math.min(remembered, row.getChildCount() - 1)));
        if (card != null) {
            card.requestFocus();
            show(items.get(indexOf(card)));
        }
    }

    void hide() {
        box.setVisibility(View.GONE);
    }

    /** Down and Up on a card; true when the key was taken. Called by Home before its own key handling. */
    boolean onKey(KeyEvent event) {
        if (!shown() || !box.hasFocus()) return false;
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_DOWN && code != KeyEvent.KEYCODE_DPAD_UP) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) host.leaveDown();
            else host.leaveUp();
        }
        return true;
    }

    void release() {
        posters.shutdown();
    }

    private int indexOf(View card) {
        return row.indexOfChild(card);
    }

    private void show(WatchNext.Item item) {
        title.setText(item.title);
        meta.setText(labelOf(item.pkg));
    }

    private void build() {
        row.removeAllViews();
        int w = (int) (132 * d);
        int h = (int) (198 * d);
        for (int i = 0; i < items.size(); i++) {
            WatchNext.Item item = items.get(i);
            ImageView poster = new ImageView(activity);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(0xFF1E2430);
            shape.setCornerRadius(14 * d);
            poster.setBackground(shape);
            poster.setClipToOutline(true);
            poster.setFocusable(true);
            poster.setClickable(true);
            poster.setAlpha(0.72f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMarginEnd((int) (18 * d));
            final int index = i;
            poster.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.1f : 1f).scaleY(focused ? 1.1f : 1f).alpha(focused ? 1f : 0.72f).setDuration(140).start();
                if (focused) {
                    remembered = index;
                    show(item);
                    row.requestChildFocus(v, v);
                    int left = v.getLeft() - (int) (14 * d);
                    scroller.smoothScrollTo(Math.max(0, left), 0);
                }
            });
            poster.setOnClickListener(v -> open(item));
            poster.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                // The ends of the row keep focus: the focus finder would jump to a tile or a button far away.
                View next = v.focusSearch(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                return next == null || row.indexOfChild(next) < 0;
            });
            if (item.poster != null) {
                posters.load(item.poster, w, (Bitmap b) -> {
                    if (b != null && !activity.isDestroyed()) poster.setImageBitmap(b);
                });
            }
            row.addView(poster, lp);
        }
        if (!items.isEmpty()) show(items.get(0));
    }

    private String labelOf(String pkg) {
        try {
            PackageManager pm = activity.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pkg == null ? "" : pkg;
        }
    }

    private void open(WatchNext.Item item) {
        Intent intent = WatchNext.launchIntent(activity, item);
        try {
            if (intent == null) throw new ActivityNotFoundException();
            host.opened();
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.watch_next_cannot_open, Toast.LENGTH_SHORT).show();
        }
    }
}
