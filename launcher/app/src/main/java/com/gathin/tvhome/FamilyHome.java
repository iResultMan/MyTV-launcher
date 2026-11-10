package com.gathin.tvhome;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

/**
 * The family parts of the Home screen (weather block, message card, children's lock card, night dimming), kept out
 * of MainActivity: it creates one of these and calls it from onResume, onPause, dispatchKeyEvent, the minute tick and
 * the top-bar key handling.
 */
final class FamilyHome {
    /** The app the weather block opens unless the theme names another (weather_app): the weather add-on. */
    private static final String DEFAULT_WEATHER_APP = "io.github.sumyabazar1.weather";

    private final Activity activity;
    /** Weather next to the date; null unless the theme sets weather_lat and weather_lon (WeatherHeader). */
    private final WeatherHeader weather;
    /** A message for the family; null unless the theme sets message_url (FamilyMessage). */
    private final FamilyMessage family;
    /** night_dim: a black layer over Home between night_from and night_to (NightDim); null when off. */
    private final NightDim nightDim;
    private final KidsLock.Listener kidsListener = isLocked -> updateKidsLock();
    private View weatherTap;
    private boolean weatherOpens;

    FamilyHome(Activity activity) {
        this.activity = activity;
        weather = WeatherHeader.create(Config.overlay(activity), activity.findViewById(R.id.weatherBlock), activity.findViewById(R.id.weatherIcon),
                activity.findViewById(R.id.weatherTemp), activity.findViewById(R.id.weatherCaption), activity.findViewById(R.id.weatherFeels),
                activity.findViewById(R.id.weatherHint));
        nightDim = NightDim.create(Config.overlay(activity), activity.findViewById(R.id.nightScrim));
        family = FamilyMessage.create(Config.overlay(activity), activity.findViewById(R.id.familyCard), activity.findViewById(R.id.familyText),
                activity.findViewById(R.id.familyAuthor));
    }

    /**
     * The weather block opens the weather app (theme weather_app, the weather add-on by default) when one is
     * installed: Left from the first pill reaches it, Right goes back, Down returns to the dock like the pills.
     */
    void bindWeatherTap(boolean rtlMenuBar, Runnable focusDock, Runnable focusTopBar) {
        weatherTap = activity.findViewById(R.id.weatherTap);
        String weatherApp = Config.overlay(activity) == null ? DEFAULT_WEATHER_APP
                : Config.overlay(activity).optString("weather_app", DEFAULT_WEATHER_APP).trim();
        weatherOpens = weather != null && !rtlMenuBar && !weatherApp.isEmpty()
                && (activity.getPackageManager().getLeanbackLaunchIntentForPackage(weatherApp) != null
                || activity.getPackageManager().getLaunchIntentForPackage(weatherApp) != null);
        weatherTap.setFocusable(weatherOpens);
        if (!weatherOpens) return;
        weatherTap.setOnClickListener(v -> RemoteKeys.launch(activity, weatherApp));
        weatherTap.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusDock.run();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                focusTopBar.run();
                return true;
            }
            return false;
        });
    }

    /** Left from the first pill: the weather block takes the focus when it is shown and can open its app. */
    void focusWeather() {
        if (weatherOpens && activity.findViewById(R.id.weatherBlock).getVisibility() == View.VISIBLE
                && activity.findViewById(R.id.leftBlock).getAlpha() > 0f) weatherTap.requestFocus();
    }

    /** The rows a parent gets on a tile: close the app for the children or open it again; each asks for the code when one is set. */
    void tileMenu(String pkg, java.util.function.BiConsumer<Integer, Runnable> addRow) {
        KidsStore store = new KidsStore(activity);
        if (!store.enabled()) return;
        boolean closed = store.isClosed(pkg);
        addRow.accept(closed ? R.string.kids_open_app : R.string.kids_close_app, () -> {
            Runnable toggle = () -> store.setClosed(pkg, !closed);
            if (store.hasPin()) KidsPinDialog.askUntilRight(activity, store, R.string.kids_pin_enter, toggle, null);
            else toggle.run();
        });
    }

    private View watchNext;

    /** The Watch next pill on the top bar: it opens the shelf, and shows only while the feature is on and allowed. */
    void bindWatchNext(View button, View.OnKeyListener keys) {
        watchNext = button;
        button.setOnClickListener(v -> activity.startActivity(new android.content.Intent(activity, WatchNextActivity.class)));
        button.setOnKeyListener(keys);
    }

    /** First run with Home or the helper missing: the setup wizard, once. Otherwise a quiet hint (at most hourly) when another launcher is the Home app. */
    private void setupHints() {
        if (SetupChecks.shouldOpenWizard(activity)) {
            activity.startActivity(new android.content.Intent(activity, SetupActivity.class).putExtra(SetupActivity.EXTRA_WIZARD, true));
            return;
        }
        android.content.SharedPreferences p = activity.getSharedPreferences("setup", android.content.Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!SetupChecks.homeIsOurs(activity) && now - p.getLong("hint_at", 0) > 60 * 60 * 1000L) {
            p.edit().putLong("hint_at", now).apply();
            android.widget.Toast.makeText(activity, R.string.setup_home_hint, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    void onResume() {
        setupHints();
        if (watchNext != null) watchNext.setVisibility(WatchNext.enabled(activity) && WatchNext.permitted(activity) ? View.VISIBLE : View.GONE);
        KidsGuard.start(activity);
        KidsLock.configure(activity, Config.overlay(activity));
        KidsLock.add(kidsListener);
        updateKidsLock();
        updateNight();
        if (weather != null) weather.start();
        if (family != null) family.start();
    }

    void onPause() {
        KidsLock.remove(kidsListener);
        if (weather != null) weather.stop();
        if (family != null) family.stop();
    }

    void updateNight() {
        if (nightDim != null) nightDim.update();
    }

    /** Children's mode: the lock takes the message card, and no key closes it. Unlocked, the card goes. */
    private void updateKidsLock() {
        View card = activity.findViewById(R.id.familyCard);
        if (card == null) return;
        boolean on = KidsLock.isLocked();
        if (family != null) family.suspend(on);
        if (on) {
            ((TextView) activity.findViewById(R.id.familyKicker)).setText(R.string.kids_lock_kicker);
            ((TextView) activity.findViewById(R.id.familyText)).setText(R.string.kids_lock_title);
            TextView sub = activity.findViewById(R.id.familyAuthor);
            String note = KidsLock.note();
            if (note.isEmpty()) sub.setText(R.string.kids_lock_text);
            else sub.setText(note);
            sub.setVisibility(View.VISIBLE);
        }
        card.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private static boolean isVolumeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    /** When the schedule has locked the TV, OK asks for the parents' code and then for how much time to give. */
    private void askForTime() {
        KidsStore store = new KidsStore(activity);
        if (!store.hasPin() || KidsGuard.pinEntry()) return;
        KidsPinDialog.askUntilRight(activity, store, R.string.kids_pin_enter, () -> KidsSettingsActivity.chooseBonus(activity, store), null);
    }

    /** True when the key was taken: the lock screen takes every key but volume, and a message closes on any key. */
    boolean onKey(KeyEvent event) {
        if (KidsLock.isLocked() && !isVolumeKey(event.getKeyCode())) {
            int key = event.getKeyCode();
            if (KidsLock.scheduledOnly() && event.getAction() == KeyEvent.ACTION_UP
                    && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) askForTime();
            return true;
        }
        // A message for the family is on: any remote key closes it (on release), and is not passed on to a tile.
        if (family != null && family.takesKeys() && !isVolumeKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_UP) family.dismiss();
            return true;
        }
        return false;
    }
}
