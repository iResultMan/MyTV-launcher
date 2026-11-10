package com.gathin.tvhome;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

/** What the launcher needs from the system, asked of Android, and the ways to fix each (see SetupActivity). */
final class SetupChecks {
    private static final String PREFS = "setup";
    static final int REQUEST_ROLE = 21;

    private SetupChecks() {
    }

    /** This launcher is what the Home button opens now (no chooser, no other launcher). */
    static boolean homeIsOurs(Context ctx) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && ctx.getPackageName().equals(r.activityInfo.packageName);
    }

    static boolean serviceOn(Context ctx) {
        return FamilyHooks.serviceEnabled(ctx);
    }

    static boolean canWriteSecure(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Asks Android to make this launcher the Home app: the role dialog where the system has one, else the "choose the
     * Home app" chooser that the Home key raises with several launchers installed (pick "Always").
     */
    static void requestHome(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager roles = activity.getSystemService(RoleManager.class);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (roles.isRoleHeld(RoleManager.ROLE_HOME)) return;
                    activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_ROLE);
                    return;
                }
            }
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // No role dialog on this firmware: the chooser below does the same.
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
            // Nothing else can be done from here.
        }
    }

    /**
     * Puts the helper on the system's enabled list when the install granted WRITE_SECURE_SETTINGS; otherwise opens the
     * accessibility settings, where the user turns it on. Call only after the disclosure was accepted.
     * @return true when the helper was switched on right here
     */
    static boolean enableService(Activity activity) {
        if (canWriteSecure(activity)) {
            try {
                android.content.ContentResolver cr = activity.getContentResolver();
                String mine = new ComponentName(activity, HomeKeyService.class).flattenToShortString();
                String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (list == null || list.isEmpty()) list = mine;
                else if (!java.util.Arrays.asList(list.split(":")).contains(mine)) list = list + ":" + mine;
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                return true;
            } catch (RuntimeException ignored) {
                // Refused after all: fall through to the settings screen.
            }
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            // A TV without that screen: nothing to open.
        }
        return false;
    }

    static boolean usageAccess(Context ctx) {
        return SystemUsage.granted(ctx);
    }

    static void openUsageAccess(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            // No such screen on this TV.
        }
    }

    // ---- the wizard's own memory ---------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean wizardSeen(Context ctx) {
        return prefs(ctx).getBoolean("seen", false);
    }

    static void setWizardSeen(Context ctx) {
        prefs(ctx).edit().putBoolean("seen", true).apply();
    }

    /** The wizard opens by itself once, and only when Home or the helper is missing, so a TV that is set up never sees it. */
    static boolean shouldOpenWizard(Context ctx) {
        return !wizardSeen(ctx) && com.gathin.tvhome.family.SetupSteps.needsWizard(homeIsOurs(ctx), serviceOn(ctx));
    }
}
