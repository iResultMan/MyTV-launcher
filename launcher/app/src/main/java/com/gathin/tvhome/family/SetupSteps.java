package com.gathin.tvhome.family;

/** The order of the first-run steps and which one to show next; pure. */
public final class SetupSteps {
    public static final int HOME = 0, SERVICE = 1, PHONE = 2, COUNT = 3;

    private SetupSteps() {
    }

    /**
     * @param done    whether each step is already in place
     * @param skipped whether the user chose "later" for it
     * @return the first step that is neither done nor skipped, or -1 when there is nothing left to ask
     */
    public static int next(boolean[] done, boolean[] skipped) {
        for (int i = 0; i < COUNT; i++) if (!done[i] && !skipped[i]) return i;
        return -1;
    }

    /** Home and the service are what the launcher needs; the phone is optional, so it alone never opens the wizard by itself. */
    public static boolean needsWizard(boolean homeOk, boolean serviceOk) {
        return !homeOk || !serviceOk;
    }
}
