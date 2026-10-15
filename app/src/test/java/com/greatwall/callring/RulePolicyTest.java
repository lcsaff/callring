package com.greatwall.callring;

import org.junit.Test;
import static org.junit.Assert.*;

public class RulePolicyTest {
    @Test public void matchesExactlyFiveAsciiDigitsStartingWithThree() {
        assertTrue(RulePolicy.matches("30000"));
        assertTrue(RulePolicy.matches("39999"));
        for (String number : new String[]{null, "", "3", "3123", "312345", "41234", "+8631234", "３１２３４", "3123a", "31234\n", " 31234"}) {
            assertFalse("Unexpected match", RulePolicy.matches(number));
        }
    }
    @Test public void everyPrerequisiteMustBePresent() {
        assertTrue(RulePolicy.shouldReplace("31234", true, true, true, true, false));
        assertFalse(RulePolicy.shouldReplace("31234", false, true, true, true, false));
        assertFalse(RulePolicy.shouldReplace("31234", true, false, true, true, false));
        assertFalse(RulePolicy.shouldReplace("31234", true, true, false, true, false));
        assertFalse(RulePolicy.shouldReplace("31234", true, true, true, false, false));
        assertFalse(RulePolicy.shouldReplace("31234", true, true, true, true, true));
        assertFalse(RulePolicy.shouldReplace("41234", true, true, true, true, false));
    }
}
