package com.gathin.tvhome.family;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SanitizerRulesTest {
    private static final long MIN = 60_000L;

    @Test
    public void swapNearlyFullOrLittleRamIsTight() {
        assertTrue(SanitizerRules.memoryTight(2_000_000, 900_000, 614_396, 14_000));
        assertTrue(SanitizerRules.memoryTight(2_000_000, 200_000, 614_396, 500_000));
        assertFalse(SanitizerRules.memoryTight(2_000_000, 900_000, 614_396, 400_000));
        assertFalse(SanitizerRules.memoryTight(2_000_000, 900_000, 0, 0));
    }

    @Test
    public void onlyTheSystemWideTrimLevelsCount() {
        assertFalse(SanitizerRules.pressure(5));     // RUNNING_MODERATE
        assertTrue(SanitizerRules.pressure(10));     // RUNNING_LOW
        assertTrue(SanitizerRules.pressure(15));     // RUNNING_CRITICAL
        assertFalse(SanitizerRules.pressure(20));    // UI_HIDDEN
        assertFalse(SanitizerRules.pressure(40));    // BACKGROUND
        assertTrue(SanitizerRules.pressure(60));     // MODERATE
        assertTrue(SanitizerRules.pressure(80));     // COMPLETE
    }

    @Test
    public void cleansOnlyWhenTight() {
        SanitizerRules rules = new SanitizerRules();
        assertFalse(rules.shouldClean(10 * MIN, false));
        assertTrue(rules.shouldClean(10 * MIN, true));
    }

    @Test
    public void aCleanThatFindsNothingTightDoesNotStartTheCooldown() {
        SanitizerRules rules = new SanitizerRules();
        assertFalse(rules.shouldClean(10 * MIN, false));
        assertTrue(rules.shouldClean(11 * MIN, true));
    }

    @Test
    public void cleaningWaitsForTheCooldown() {
        SanitizerRules rules = new SanitizerRules();
        assertTrue(rules.shouldClean(10 * MIN, true));
        assertFalse(rules.shouldClean(12 * MIN, true));
        assertFalse(rules.shouldClean(29 * MIN, true));
        assertTrue(rules.shouldClean(30 * MIN, true));
    }

    @Test
    public void theVpnIsStartedOnceAndThenLeftAloneForAWhile() {
        SanitizerRules rules = new SanitizerRules();
        assertTrue(rules.shouldStartVpn(10 * MIN));
        assertFalse(rules.shouldStartVpn(20 * MIN));
        assertFalse(rules.shouldStartVpn(39 * MIN));
        assertTrue(rules.shouldStartVpn(40 * MIN));
    }
}
