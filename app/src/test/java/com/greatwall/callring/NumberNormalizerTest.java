package com.greatwall.callring;

import org.junit.Test;
import static org.junit.Assert.*;

public class NumberNormalizerTest {
    @Test public void stripsOnlyFormattingCharacters() {
        assertEquals("31234", NumberNormalizer.normalize(" \t(31)-234\n"));
        assertEquals("+8631234", NumberNormalizer.normalize("+86 (31) 234"));
        assertEquals("3.12#34", NumberNormalizer.normalize("3.12#34"));
    }
    @Test public void retainsDialingPrefixesForStrictRule() {
        assertTrue(RulePolicy.matches(NumberNormalizer.normalize("3-1234")));
        assertFalse(RulePolicy.matches(NumberNormalizer.normalize("+86 31234")));
        assertFalse(RulePolicy.matches(NumberNormalizer.normalize("0086 31234")));
        assertFalse(RulePolicy.matches(NumberNormalizer.normalize("３１２３４")));
    }
    @Test public void removesUnicodeWhitespaceWithoutChangingDigits() {
        assertEquals("31234", NumberNormalizer.normalize("3\u00a012\u300034"));
    }
    @Test public void nullIsEmpty() { assertEquals("", NumberNormalizer.normalize(null)); }
}
