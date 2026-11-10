package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Phone and cloud": the switch for the cloud link (off until a parent turns it on, with the code when one is set), the
 * list of what the server learns shown before it can be turned on, the state of the link and the pairing code with its QR
 * code. Turning the switch off deletes this TV's data on the server.
 */
public final class CloudSettingsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView switchRow, statusRow;
    private LinearLayout codeBox;
    private float d;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, 1500);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
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
        text(getString(R.string.cloud_title), 34, 0xFFFFFFFF);
        text(getString(R.string.cloud_intro), 22, 0xFFD5DAE2);
        text(getString(R.string.cloud_learns_title), 24, 0xFFFFFFFF);
        text(getString(R.string.cloud_learns), 20, 0xFFD5DAE2);
        switchRow = row();
        switchRow.setOnClickListener(v -> toggle());
        statusRow = text("", 20, 0xFF8C96A5);
        codeBox = new LinearLayout(this);
        codeBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(codeBox);
        switchRow.requestFocus();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    private TextView text(String text, int size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
        list.addView(t);
        return t;
    }

    private TextView row() {
        TextView t = text("", 24, 0xFFFFFFFF);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
        t.setOnFocusChangeListener((v, focused) -> v.setBackgroundColor(focused ? 0x33FFFFFF : 0));
        return t;
    }

    private void refresh() {
        boolean on = new CloudStore(this).enabled();
        boolean usable = CloudStore.serverUrl(this) != null;
        switchRow.setText(getString(R.string.cloud_switch, getString(on ? R.string.cloud_on : R.string.cloud_off)));
        if (!usable) {
            statusRow.setText(R.string.cloud_bad_url);
        } else if (!on) {
            statusRow.setText(getString(R.string.cloud_server, CloudStore.serverUrl(this)));
        } else {
            statusRow.setText(getString(R.string.cloud_server, CloudStore.serverUrl(this)) + "\n" + getString(statusText(CloudClient.status())));
        }
        showCode(on && usable);
    }

    private static int statusText(CloudClient.Status s) {
        switch (s) {
            case ONLINE: return R.string.cloud_status_online;
            case RETRYING: return R.string.cloud_status_retrying;
            case REFUSED: return R.string.cloud_status_refused;
            default: return R.string.cloud_status_connecting;
        }
    }

    /** The code and its QR code while the link is on; a row to ask for a new one. */
    private void showCode(boolean on) {
        String code = CloudClient.code();
        if (!on) {
            codeBox.removeAllViews();
            codeBox.setTag(null);
            return;
        }
        String shown = "code:" + code;
        if (shown.equals(codeBox.getTag())) return;
        codeBox.setTag(shown);
        codeBox.removeAllViews();
        TextView title = new TextView(this);
        title.setText(R.string.cloud_link_title);
        title.setTextSize(24);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, (int) (18 * d), 0, (int) (6 * d));
        codeBox.addView(title);
        TextView ask = new TextView(this);
        ask.setText(code.isEmpty() ? R.string.cloud_get_code : R.string.cloud_new_code);
        ask.setTextSize(24);
        ask.setTextColor(0xFFFFFFFF);
        ask.setFocusable(true);
        ask.setClickable(true);
        ask.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
        ask.setOnFocusChangeListener((v, focused) -> v.setBackgroundColor(focused ? 0x33FFFFFF : 0));
        ask.setOnClickListener(v -> {
            ask.setEnabled(false);
            CloudClient.requestCode(result -> main.post(() -> {
                if (result == null) Toast.makeText(this, R.string.cloud_code_failed, Toast.LENGTH_LONG).show();
                codeBox.setTag(null);
                refresh();
            }));
        });
        codeBox.addView(ask);
        if (!code.isEmpty()) {
            LinearLayout both = new LinearLayout(this);
            both.setOrientation(LinearLayout.HORIZONTAL);
            both.setGravity(Gravity.CENTER_VERTICAL);
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(QrBitmap.of(pairingLink(code), (int) (230 * d)));
            both.addView(qr);
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding((int) (28 * d), 0, 0, 0);
            TextView big = new TextView(this);
            big.setText(code);
            big.setTextSize(48);
            big.setTextColor(0xFFFFFFFF);
            big.setLetterSpacing(0.15f);
            words.addView(big);
            TextView hint = new TextView(this);
            hint.setText(CloudStore.bot(this).isEmpty() ? R.string.cloud_code_hint_page : R.string.cloud_code_hint_bot);
            hint.setTextSize(20);
            hint.setTextColor(0xFFD5DAE2);
            hint.setMaxWidth((int) (560 * d));
            words.addView(hint);
            both.addView(words);
            codeBox.addView(both);
        }
    }

    /** What the QR code opens: the project's bot with the code, or the project's page with the code when no bot is set. */
    private String pairingLink(String code) {
        String bot = CloudStore.bot(this);
        if (!bot.isEmpty()) return "https://t.me/" + bot + "?start=" + code;
        String url = CloudStore.serverUrl(this);
        return (url == null ? CloudStore.DEFAULT_URL : url.replaceAll("/+$", "")) + "/?c=" + code;
    }

    private void toggle() {
        KidsStore kids = new KidsStore(this);
        Runnable go = () -> {
            if (new CloudStore(this).enabled()) turnOff();
            else confirmOn();
        };
        // With a parents' code set, only a parent changes this, in either direction.
        if (kids.hasPin()) KidsPinDialog.askUntilRight(this, kids, R.string.kids_pin_enter, go, null);
        else go.run();
    }

    /** The list of what the server learns comes first; only a yes turns the link on. */
    private void confirmOn() {
        if (CloudStore.serverUrl(this) == null) {
            Toast.makeText(this, R.string.cloud_bad_url, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.cloud_learns_title)
                .setMessage(getString(R.string.cloud_learns) + "\n\n" + getString(R.string.cloud_off_deletes))
                .setPositiveButton(R.string.cloud_turn_on, (dialog, which) -> {
                    CloudClient.setEnabled(this, true, null);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void turnOff() {
        CloudClient.setEnabled(this, false, ok -> main.post(() -> Toast.makeText(this,
                ok ? R.string.cloud_deleted : R.string.cloud_not_deleted, Toast.LENGTH_LONG).show()));
        refresh();
    }
}
