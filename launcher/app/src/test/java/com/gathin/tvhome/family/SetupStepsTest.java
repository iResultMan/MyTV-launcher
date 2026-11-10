package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SetupStepsTest {
    @Test
    public void theFirstOpenStepComesNext() {
        assertEquals(SetupSteps.HOME, SetupSteps.next(new boolean[] {false, false, false}, new boolean[3]));
        assertEquals(SetupSteps.SERVICE, SetupSteps.next(new boolean[] {true, false, false}, new boolean[3]));
        assertEquals(SetupSteps.PHONE, SetupSteps.next(new boolean[] {true, true, false}, new boolean[3]));
    }

    @Test
    public void aSkippedStepIsPassedOverAndNothingLeftGivesMinusOne() {
        assertEquals(SetupSteps.PHONE, SetupSteps.next(new boolean[] {false, false, false}, new boolean[] {true, true, false}));
        assertEquals(-1, SetupSteps.next(new boolean[] {false, true, false}, new boolean[] {true, false, true}));
        assertEquals(-1, SetupSteps.next(new boolean[] {true, true, true}, new boolean[3]));
    }

    @Test
    public void onlyHomeAndServiceOpenTheWizard() {
        assertFalse(SetupSteps.needsWizard(true, true));
        assertTrue(SetupSteps.needsWizard(false, true));
        assertTrue(SetupSteps.needsWizard(true, false));
    }
}
