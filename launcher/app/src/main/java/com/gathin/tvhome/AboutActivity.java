package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Who made this, what it is built on and under which licences (NOTICE.md and docs/PRIVACY.md hold the full text). */
public final class AboutActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        float d = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101419);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = (int) (72 * d);
        list.setPadding(side, (int) (38 * d), side, (int) (38 * d));
        scroll.addView(list);
        setContentView(scroll);
        add(list, getString(R.string.about_title), 34, 0xFFFFFFFF, d);
        add(list, getString(R.string.about_version, version()), 22, 0xFF8C96A5, d);
        add(list, getString(R.string.about_based), 22, 0xFFD5DAE2, d);
        add(list, getString(R.string.about_licenses), 22, 0xFFD5DAE2, d);
        add(list, getString(R.string.about_privacy), 22, 0xFFD5DAE2, d);
        TextView back = new TextView(this);
        back.setFocusable(true);
        list.addView(back, 0, 0);
        // Only when a donation link is set (res/values/support.xml or the theme); otherwise About is as it was.
        if (!Support.links(this).isEmpty()) {
            TextView support = add(list, getString(R.string.support_title), 24, 0xFFFFFFFF, d);
            support.setFocusable(true);
            support.setClickable(true);
            support.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
            support.setOnFocusChangeListener((v, focused) -> v.setBackgroundColor(focused ? 0x33FFFFFF : 0));
            support.setOnClickListener(v -> startActivity(new Intent(this, SupportActivity.class)));
            support.requestFocus();
        } else {
            back.requestFocus();
        }
    }

    private TextView add(LinearLayout list, String text, int size, int color, float d) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
        list.addView(t);
        return t;
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
