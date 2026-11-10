package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gathin.tvhome.family.KidsSchedule;

import java.util.Locale;

/**
 * The kids' schedule, set on the TV with the remote: on or off, the allowed hours on weekdays and weekends, the limit
 * per day, extra time, and the parents' code. With a code set the screen opens only with it. Left and Right change a value
 * by half an hour; OK switches or confirms.
 */
public final class KidsSettingsActivity extends Activity {
    private static final int STEP_MIN = 30;
    private static final int[] BONUS_MIN = {15, 30, 60};

    private KidsStore store;
    private LinearLayout list;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new KidsStore(this);
        float d = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101419);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = (int) (72 * d);
        list.setPadding(side, (int) (38 * d), side, (int) (38 * d));
        scroll.addView(list);
        setContentView(scroll);
        KidsPinDialog.guard(this);
        build();
    }

    private void build() {
        list.removeAllViews();
        TextView title = new TextView(this);
        title.setText(R.string.kids_schedule);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(34);
        list.addView(title);
        KidsSchedule schedule = store.schedule();
        row(getString(schedule.enabled ? R.string.kids_set_on : R.string.kids_set_off), this::toggle, null);
        if (schedule.enabled) {
            window(false, true);
            window(false, false);
            window(true, true);
            window(true, false);
            row(limitText(store.limitMin()), null, step -> {
                store.setLimitMin(Math.max(0, Math.min(12 * 60, store.limitMin() + step * STEP_MIN)));
                KidsGuard.recheck();
                return limitText(store.limitMin());
            });
            row(getString(R.string.kids_set_bonus), () -> chooseBonus(this, store), null);
        }
        row(getString(R.string.kids_set_pin), this::changePin, null);
        row(getString(store.lanEnabled() ? R.string.kids_lan_on : R.string.kids_lan_off), this::toggleLan, null);
        if (store.lanEnabled() && KidsLock.pairing() != null) {
            row(getString(R.string.kids_lan_pair), this::showQr, null);
            row(getString(R.string.kids_lan_phones, KidsLock.pairing().phones().size()), this::choosePhone, null);
        }
    }

    /** Phone control needs the code (it unlocks with it), so turning it on asks for one first when none is set. */
    private void toggleLan() {
        Runnable apply = () -> {
            store.setLanEnabled(!store.lanEnabled());
            KidsLock.configure(this, Config.overlay(this));
            build();
        };
        if (!store.lanEnabled() && !store.hasPin()) newPin(apply);
        else apply.run();
    }

    private void showQr() {
        com.gathin.tvhome.family.LanPairing pairing = KidsLock.pairing();
        String address = LanDiscovery.address();
        if (pairing == null || address == null) {
            Toast.makeText(this, R.string.kids_lan_no_network, Toast.LENGTH_LONG).show();
            return;
        }
        String code = pairing.newCode();
        String url = "http://" + address + ":" + KidsLock.lanPort() + "/?c=" + code;
        boolean[][] modules = com.gathin.tvhome.family.QrCode.encode(url);
        int quiet = 4, n = modules.length + 2 * quiet;
        int px = Math.max(1, (int) (300 * getResources().getDisplayMetrics().density) / n);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(n * px, n * px, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFFFFFFFF);
        for (int y = 0; y < modules.length; y++) {
            for (int x = 0; x < modules.length; x++) {
                if (!modules[y][x]) continue;
                for (int dy = 0; dy < px; dy++) for (int dx = 0; dx < px; dx++) bitmap.setPixel((x + quiet) * px + dx, (y + quiet) * px + dy, 0xFF000000);
            }
        }
        float d = getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setPadding((int) (24 * d), (int) (20 * d), (int) (24 * d), (int) (20 * d));
        TextView hint = new TextView(this);
        hint.setText(R.string.kids_lan_scan);
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(18);
        hint.setGravity(android.view.Gravity.CENTER);
        panel.addView(hint);
        android.widget.ImageView image = new android.widget.ImageView(this);
        image.setImageBitmap(bitmap);
        image.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
        panel.addView(image);
        TextView text = new TextView(this);
        text.setText(address + ":" + KidsLock.lanPort() + "\n" + getString(R.string.kids_lan_code, code));
        text.setTextColor(0xFFC8C8C8);
        text.setTextSize(16);
        text.setGravity(android.view.Gravity.CENTER);
        panel.addView(text);
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.setContentView(panel);
        dialog.setOnDismissListener(dlg -> {
            pairing.closeCode();
            build();
        });
        dialog.show();
    }

    /** The paired phones; choosing one asks whether to disconnect it. */
    private void choosePhone() {
        com.gathin.tvhome.family.LanPairing pairing = KidsLock.pairing();
        if (pairing == null) return;
        java.util.List<com.gathin.tvhome.family.LanPairing.Phone> phones = pairing.phones();
        if (phones.isEmpty()) {
            Toast.makeText(this, R.string.kids_lan_none, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[phones.size()];
        for (int i = 0; i < names.length; i++) names[i] = phones.get(i).name;
        new android.app.AlertDialog.Builder(this).setTitle(R.string.kids_lan_disconnect).setItems(names, (dlg, which) -> {
            pairing.revoke(phones.get(which).id);
            build();
        }).show();
    }

    private interface Stepper {
        /** Moves the value by step (-1 or 1) and returns the row's new text. */
        String step(int step);
    }

    private void row(String text, Runnable onOk, Stepper stepper) {
        float d = getResources().getDisplayMetrics().density;
        TextView row = new TextView(this, null, 0, R.style.SettingsRow);
        ViewGroup.MarginLayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * d));
        lp.topMargin = (int) (14 * d);
        row.setLayoutParams(lp);
        row.setText(text);
        if (onOk != null) row.setOnClickListener(v -> onOk.run());
        if (stepper != null) {
            row.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                row.setText(stepper.step(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1));
                return true;
            });
        }
        list.addView(row);
        if (list.getChildCount() == 2) row.requestFocus();
    }

    /** One end of the allowed hours of weekdays or weekends: a row Left and Right move by half an hour. */
    private void window(boolean weekend, boolean from) {
        row(windowText(weekend, from), null, step -> {
            int[] w = minutes(weekend);
            int v = (from ? w[0] : w[1]) + step * STEP_MIN;
            if (from) {
                v = Math.max(0, Math.min(1440 - STEP_MIN, v));
                if (v == w[1] % 1440) v += step * STEP_MIN;
                w[0] = Math.max(0, Math.min(1440 - STEP_MIN, v));
            } else {
                v = Math.max(STEP_MIN, Math.min(1440, v));
                if (v % 1440 == w[0]) v += step * STEP_MIN;
                w[1] = Math.max(STEP_MIN, Math.min(1440, v));
            }
            store.setWindows(weekend, new KidsSchedule.Window(w[0], w[1]).toString());
            KidsGuard.recheck();
            return windowText(weekend, from);
        });
    }

    /** The window of weekdays or weekends as {startMin, endMin}; the default when none (or more than one, then the first) is set. */
    private int[] minutes(boolean weekend) {
        String text = store.windowsText(weekend);
        KidsSchedule.Window w = text == null ? null : KidsSchedule.parseWindow(text.split(";")[0]);
        if (w == null) w = KidsSchedule.parseWindow(weekend ? KidsStore.DEFAULT_WEEKENDS : KidsStore.DEFAULT_WEEKDAYS);
        return new int[] {w.startMin, w.endMin};
    }

    private String windowText(boolean weekend, boolean from) {
        int[] w = minutes(weekend);
        String time = clock(from ? w[0] : w[1]);
        return getString(weekend ? (from ? R.string.kids_set_weekends_from : R.string.kids_set_weekends_to)
                : (from ? R.string.kids_set_weekdays_from : R.string.kids_set_weekdays_to), time);
    }

    private static String clock(int min) {
        return String.format(Locale.ROOT, "%02d:%02d", min / 60, min % 60);
    }

    private String limitText(int min) {
        return getString(R.string.kids_set_limit, min == 0 ? getString(R.string.kids_limit_none)
                : String.format(Locale.ROOT, "%d:%02d", min / 60, min % 60));
    }

    private void toggle() {
        if (store.enabled()) {
            store.setEnabled(false);
            KidsGuard.recheck();
            build();
        } else if (!store.hasPin()) {
            newPin(() -> {
                store.setEnabled(true);
                KidsGuard.recheck();
                build();
            });
        } else {
            store.setEnabled(true);
            KidsGuard.recheck();
            build();
        }
    }

    private void changePin() {
        newPin(() -> Toast.makeText(this, R.string.kids_pin_saved, Toast.LENGTH_SHORT).show());
    }

    /** Asks for a new code twice and saves it when both match. */
    private void newPin(Runnable saved) {
        KidsPinDialog.ask(this, R.string.kids_pin_new, first -> KidsPinDialog.ask(this, R.string.kids_pin_repeat, second -> {
            if (!first.equals(second)) {
                Toast.makeText(this, R.string.kids_pin_mismatch, Toast.LENGTH_SHORT).show();
                return;
            }
            store.setPin(first);
            KidsLock.configure(this, Config.overlay(this));
            saved.run();
        }, null), null);
    }

    /** A list of how much extra time to give; the keys are the dialog's, not the lock's, while it is up. */
    static void chooseBonus(Activity activity, KidsStore store) {
        String[] items = new String[BONUS_MIN.length];
        for (int i = 0; i < items.length; i++) items[i] = activity.getString(R.string.kids_bonus_option, BONUS_MIN[i]);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity).setTitle(R.string.kids_bonus_title).setItems(items, (dlg, which) -> {
            giveBonus(store, BONUS_MIN[which]);
            Toast.makeText(activity, activity.getString(R.string.kids_bonus_given, BONUS_MIN[which]), Toast.LENGTH_SHORT).show();
        }).create();
        dialog.setOnDismissListener(dlg -> KidsGuard.setPinEntry(false));
        KidsGuard.setPinEntry(true);
        dialog.show();
    }

    /** Extra time from now (or from the end of a bonus that is still running), whatever the windows and the limit say. */
    static void giveBonus(KidsStore store, int minutes) {
        long now = KidsGuard.now();
        store.setBonusUntil(Math.max(now, store.bonusUntil()) + minutes * 60_000L);
        KidsGuard.recheck();
    }
}
