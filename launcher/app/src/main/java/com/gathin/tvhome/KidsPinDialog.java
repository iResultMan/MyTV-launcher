package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Consumer;

/**
 * A code pad for the remote: the digits as buttons the arrow keys move over, the remote's own number keys too, and the
 * code is taken as soon as the fourth digit is in. Used to open the schedule's settings, to guard the launcher's
 * settings and to give extra time on the lock screen.
 */
final class KidsPinDialog {
    static final int LENGTH = 4;

    private KidsPinDialog() {
    }

    /** Asks for the parents' code; onPin gets it, onCancel (may be null) runs when the pad is closed without one. */
    static void ask(Activity activity, int titleRes, Consumer<String> onPin, Runnable onCancel) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        float d = activity.getResources().getDisplayMetrics().density;
        StringBuilder entered = new StringBuilder();
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        int pad = (int) (24 * d);
        panel.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(activity);
        title.setText(titleRes);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        TextView dots = new TextView(activity);
        dots.setTextColor(Color.WHITE);
        dots.setTextSize(30);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
        panel.addView(dots);
        GridLayout pad3 = new GridLayout(activity);
        pad3.setColumnCount(3);
        panel.addView(pad3);
        boolean[] done = {false};
        Runnable redraw = () -> {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < LENGTH; i++) s.append(i < entered.length() ? "● " : "○ ");
            dots.setText(s.toString().trim());
        };
        Runnable push = () -> {
            if (entered.length() < LENGTH) return;
            done[0] = true;
            String pin = entered.toString();
            dialog.dismiss();
            onPin.accept(pin);
        };
        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", ""};
        for (String label : labels) {
            TextView key = new TextView(activity, null, 0, R.style.SettingsRow);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = (int) (84 * d);
            lp.height = (int) (60 * d);
            lp.setMargins((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
            key.setLayoutParams(lp);
            key.setGravity(Gravity.CENTER);
            key.setPadding(0, 0, 0, 0);
            key.setText(label);
            if (label.isEmpty()) {
                key.setFocusable(false);
                key.setClickable(false);
                key.setBackground(null);
            } else {
                key.setOnClickListener(v -> {
                    if (label.equals("⌫")) {
                        if (entered.length() > 0) entered.deleteCharAt(entered.length() - 1);
                    } else if (entered.length() < LENGTH) {
                        entered.append(label);
                    }
                    redraw.run();
                    push.run();
                });
            }
            pad3.addView(key);
        }
        redraw.run();
        dialog.setOnKeyListener((dlg, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9 && entered.length() < LENGTH) {
                entered.append((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
                redraw.run();
                push.run();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL && entered.length() > 0) {
                entered.deleteCharAt(entered.length() - 1);
                redraw.run();
                return true;
            }
            return false;
        });
        dialog.setOnDismissListener(dlg -> {
            KidsGuard.setPinEntry(false);
            if (!done[0] && onCancel != null) onCancel.run();
        });
        dialog.setContentView(panel);
        KidsGuard.setPinEntry(true);
        dialog.show();
        for (int i = 0; i < pad3.getChildCount(); i++) {
            View k = pad3.getChildAt(i);
            if (k.isFocusable()) {
                k.requestFocus();
                break;
            }
        }
    }

    /**
     * Asks for the code when one is set and the schedule is on; wrong or cancelled closes the activity. For the screens that
     * change settings: a child with the remote cannot get into them.
     */
    static void guard(Activity activity) {
        KidsStore store = new KidsStore(activity);
        if (!store.enabled() || !store.hasPin()) return;
        View content = activity.findViewById(android.R.id.content);
        content.setVisibility(View.INVISIBLE);
        askUntilRight(activity, store, R.string.kids_pin_enter, () -> content.setVisibility(View.VISIBLE), activity::finish);
    }

    /** Asks until the code is right (the answer may be refused for a while after too many wrong ones) or the pad is cancelled. */
    static void askUntilRight(Activity activity, KidsStore store, int titleRes, Runnable onRight, Runnable onCancel) {
        ask(activity, titleRes, pin -> {
            long now = KidsGuard.now();
            if (store.checkPin(pin, now)) {
                onRight.run();
                return;
            }
            Toast.makeText(activity, store.pinLocked(now) ? R.string.kids_pin_locked : R.string.kids_pin_wrong, Toast.LENGTH_SHORT).show();
            askUntilRight(activity, store, titleRes, onRight, onCancel);
        }, onCancel);
    }
}
