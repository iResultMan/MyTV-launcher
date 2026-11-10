package com.gathin.tvhome.family;

/**
 * One button, two meanings: a single tap and two quick ones. The caller feeds every press and release of the button
 * and a timer; a double press is told the moment the second press starts, a single tap once the window after its
 * release has passed without another press. Key repeats of a held button are ignored.
 */
public final class GearPress {
    /** How long after a release a second press still counts as the other half of a double press. */
    public static final long WINDOW_MS = 350;

    private enum State { IDLE, FIRST_DOWN, WAITING, SECOND_DOWN }

    private State state = State.IDLE;
    private long releasedAt;

    /** A press or release; true when this press completes a double press. */
    public boolean onKey(boolean down, boolean repeat, long now) {
        if (repeat) return false;
        switch (state) {
            case WAITING:
                if (!down) return false;
                if (now - releasedAt <= WINDOW_MS) {
                    state = State.SECOND_DOWN;
                    return true;
                }
                state = State.FIRST_DOWN;
                return false;
            case FIRST_DOWN:
                if (!down) {
                    state = State.WAITING;
                    releasedAt = now;
                }
                return false;
            case SECOND_DOWN:
                state = down ? State.FIRST_DOWN : State.IDLE;
                return false;
            default:
                if (down) state = State.FIRST_DOWN;
                return false;
        }
    }

    /** Milliseconds until a single tap is due, or -1 when none is waiting. */
    public long nextDelay(long now) {
        return state == State.WAITING ? Math.max(0, WINDOW_MS - (now - releasedAt)) : -1;
    }

    /** True once, when the waiting tap has outlasted the window: it was a single tap. */
    public boolean singleDue(long now) {
        if (state != State.WAITING || now - releasedAt < WINDOW_MS) return false;
        state = State.IDLE;
        return true;
    }
}
