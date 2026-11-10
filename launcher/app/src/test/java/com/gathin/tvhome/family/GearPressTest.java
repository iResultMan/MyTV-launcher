package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GearPressTest {
    @Test
    public void aSingleTapIsDueOnlyAfterTheWindow() {
        GearPress press = new GearPress();
        assertFalse(press.onKey(true, false, 0));
        assertFalse(press.onKey(false, false, 100));
        assertEquals(GearPress.WINDOW_MS, press.nextDelay(100));
        assertFalse(press.singleDue(100 + GearPress.WINDOW_MS - 1));
        assertTrue(press.singleDue(100 + GearPress.WINDOW_MS));
        assertFalse(press.singleDue(1000));
        assertEquals(-1, press.nextDelay(1000));
    }

    @Test
    public void aSecondPressInsideTheWindowIsADoublePress() {
        GearPress press = new GearPress();
        press.onKey(true, false, 0);
        press.onKey(false, false, 100);
        assertTrue(press.onKey(true, false, 300));
        assertFalse(press.singleDue(10_000));
        assertFalse(press.onKey(false, false, 400));
        assertEquals(-1, press.nextDelay(400));
    }

    @Test
    public void aSecondPressAfterTheWindowStartsOver() {
        GearPress press = new GearPress();
        press.onKey(true, false, 0);
        press.onKey(false, false, 100);
        assertFalse(press.onKey(true, false, 100 + GearPress.WINDOW_MS + 1));
        press.onKey(false, false, 600);
        assertTrue(press.singleDue(600 + GearPress.WINDOW_MS));
    }

    @Test
    public void repeatsOfAHeldButtonChangeNothing() {
        GearPress press = new GearPress();
        press.onKey(true, false, 0);
        assertFalse(press.onKey(true, true, 400));
        assertFalse(press.onKey(true, true, 450));
        press.onKey(false, false, 1500);
        assertTrue(press.singleDue(1500 + GearPress.WINDOW_MS));
    }

    @Test
    public void aThirdPressAfterADoubleIsATapOfItsOwn() {
        GearPress press = new GearPress();
        press.onKey(true, false, 0);
        press.onKey(false, false, 50);
        assertTrue(press.onKey(true, false, 100));
        press.onKey(false, false, 150);
        assertFalse(press.onKey(true, false, 200));
        press.onKey(false, false, 250);
        assertTrue(press.singleDue(250 + GearPress.WINDOW_MS));
    }
}
