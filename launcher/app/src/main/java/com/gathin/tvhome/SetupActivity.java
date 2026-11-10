package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gathin.tvhome.family.SetupSteps;

/**
 * Setup and health in one screen. Opened from the settings menu it is a checklist of what the launcher needs (Home,
 * the helper, usage access, auto-repair, children's mode), each row fixing itself on OK. Opened as the first-run
 * wizard (EXTRA_WIZARD) it walks the same checks one step at a time; "Later" on any step passes it over, and nothing
 * here ever blocks Home.
 */
public final class SetupActivity extends Activity {
    static final String EXTRA_WIZARD = "com.gathin.tvhome.extra.SETUP_WIZARD";
    private static final int OK_COLOR = 0xFF8FD6A0, BAD_COLOR = 0xFFFFB4A8;

    private LinearLayout list;
    private float d;
    private boolean wizard;
    private final boolean[] skipped = new boolean[SetupSteps.COUNT];

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        d = getResources().getDisplayMetrics().density;
        wizard = getIntent().getBooleanExtra(EXTRA_WIZARD, false);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101419);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = (int) (72 * d);
        list.setPadding(side, (int) (38 * d), side, (int) (38 * d));
        scroll.addView(list);
        setContentView(scroll);
        KidsPinDialog.guard(this);
        if (wizard) SetupChecks.setWizardSeen(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wizard) buildWizard();
        else buildHealth();
    }

    private boolean[] done() {
        KidsStore store = new KidsStore(this);
        return new boolean[] {SetupChecks.homeIsOurs(this), SetupChecks.serviceOn(this), store.lanEnabled() && store.hasPin()};
    }

    // ---- the checklist ---------------------------------------------------------------------------------

    private void buildHealth() {
        list.removeAllViews();
        heading(getString(R.string.setup_title));
        boolean home = SetupChecks.homeIsOurs(this), service = SetupChecks.serviceOn(this);
        row(getString(home ? R.string.setup_home_ok : R.string.setup_home_bad), home, () -> SetupChecks.requestHome(this));
        row(getString(service ? R.string.setup_service_ok : R.string.setup_service_bad), service, () -> {
            if (!service) disclose(null);
        });
        boolean usage = SetupChecks.usageAccess(this);
        row(getString(usage ? R.string.setup_usage_ok : R.string.setup_usage_bad), usage, () -> {
            if (!usage) SetupChecks.openUsageAccess(this);
        });
        boolean secure = SetupChecks.canWriteSecure(this);
        row(getString(secure ? R.string.setup_repair_ok : R.string.setup_repair_bad), secure, () -> {
            if (!secure) new AlertDialog.Builder(this).setMessage(R.string.setup_repair_how).setPositiveButton(android.R.string.ok, null).show();
        });
        KidsStore store = new KidsStore(this);
        boolean kids = store.enabled() && store.hasPin();
        row(getString(kids ? R.string.setup_kids_ok : R.string.setup_kids_bad), kids, () -> startActivity(new Intent(this, KidsSettingsActivity.class)));
        row(getString(R.string.setup_save), true, () -> Toast.makeText(this, SettingsFiles.saveFile(this) ? R.string.setup_saved : R.string.setup_save_failed, Toast.LENGTH_LONG).show());
        row(getString(R.string.setup_load), true, () -> Toast.makeText(this, SettingsFiles.loadFile(this) ? R.string.setup_loaded : R.string.setup_load_failed, Toast.LENGTH_LONG).show());
        row(Entitlements.summary(this), true, () -> { });
        row(getString(R.string.license_from_file), true, () -> Toast.makeText(this,
                Entitlements.submitFile(this).reason == com.gathin.tvhome.family.LicenseKey.Reason.OK ? R.string.license_ok : R.string.license_bad,
                Toast.LENGTH_LONG).show());
        row(getString(R.string.setup_erase), false, () -> new AlertDialog.Builder(this)
                .setMessage(R.string.setup_erase_ask)
                .setPositiveButton(R.string.setup_erase_do, (dialog, which) -> {
                    new KidsStore(this).eraseAll();
                    UsageLog.erase(this);
                    KidsGuard.recheck();
                    KidsLock.configure(this, Config.overlay(this));
                    Toast.makeText(this, R.string.setup_erased, Toast.LENGTH_LONG).show();
                    buildHealth();
                })
                .setNegativeButton(R.string.setup_not_now, null)
                .show());
        row(getString(R.string.about_title), true, () -> startActivity(new Intent(this, AboutActivity.class)));
        row(getString(R.string.setup_wizard), true, () -> startActivity(new Intent(this, SetupActivity.class).putExtra(EXTRA_WIZARD, true)));
    }

    // ---- the wizard ---------------------------------------------------------------------------------------

    private void buildWizard() {
        list.removeAllViews();
        int step = SetupSteps.next(done(), skipped);
        if (step < 0) {
            heading(getString(R.string.setup_done_title));
            body(getString(R.string.setup_done_body));
            button(getString(R.string.setup_close), true, this::finish);
            return;
        }
        TextView count = body(getString(R.string.setup_step, step + 1, SetupSteps.COUNT));
        count.setTextColor(0xFF8C96A5);
        switch (step) {
            case SetupSteps.HOME:
                heading(getString(R.string.setup_home_title));
                body(getString(R.string.setup_home_body));
                button(getString(R.string.setup_home_do), true, () -> SetupChecks.requestHome(this));
                break;
            case SetupSteps.SERVICE:
                heading(getString(R.string.setup_service_title));
                body(getString(R.string.setup_disclosure));
                button(getString(R.string.setup_allow), true, () -> {
                    if (SetupChecks.enableService(this)) buildWizard();
                });
                break;
            default:
                heading(getString(R.string.setup_phone_title));
                body(getString(R.string.setup_phone_body));
                button(getString(R.string.setup_phone_do), true, () -> startActivity(new Intent(this, KidsSettingsActivity.class)));
                break;
        }
        button(getString(R.string.setup_later), false, () -> {
            skipped[step] = true;
            buildWizard();
        });
    }

    /** The prominent disclosure before the helper is turned on: what it does and what it does not read. */
    private void disclose(Runnable after) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_service_title)
                .setMessage(R.string.setup_disclosure)
                .setPositiveButton(R.string.setup_allow, (dialog, which) -> {
                    if (SetupChecks.enableService(this)) {
                        Toast.makeText(this, R.string.setup_service_ok, Toast.LENGTH_SHORT).show();
                        buildHealth();
                    }
                    if (after != null) after.run();
                })
                .setNegativeButton(R.string.setup_not_now, null)
                .show();
    }

    // ---- views ----------------------------------------------------------------------------------------------

    private void heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(34);
        t.setPadding(0, 0, 0, (int) (12 * d));
        list.addView(t);
    }

    private TextView body(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFD5DAE2);
        t.setTextSize(22);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
        list.addView(t);
        return t;
    }

    private void row(String text, boolean ok, Runnable onOk) {
        TextView r = button(text, false, onOk);
        r.setTextColor(ok ? OK_COLOR : BAD_COLOR);
    }

    private TextView button(String text, boolean focusFirst, Runnable onOk) {
        TextView b = new TextView(this, null, 0, R.style.SettingsRow);
        ViewGroup.MarginLayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * d));
        lp.topMargin = (int) (14 * d);
        b.setLayoutParams(lp);
        b.setText(text);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setSingleLine(true);
        b.setOnClickListener((View v) -> onOk.run());
        list.addView(b);
        if (focusFirst || list.getChildCount() == 2 && !wizard) b.requestFocus();
        return b;
    }
}
