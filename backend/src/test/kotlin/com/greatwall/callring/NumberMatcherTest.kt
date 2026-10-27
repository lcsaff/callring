package com.greatwall.callring

import com.greatwall.callring.domain.calling.NumberMatcher
import com.greatwall.callring.domain.calling.NumberRule
import com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberMatcherTest {
    private val matcher: NumberMatcher = PrefixLengthNumberMatcher

    @Test
    fun defaultRuleMatchesFiveAsciiDigitsStartingWithThree() {
        assertEquals(NumberRule("3", 5), NumberRule.DEFAULT)
        assertTrue(matcher.matches("31234", NumberRule.DEFAULT))
        listOf("81234", "3123", "312345", "+31234", "３１２３４", "3１２３４").forEach {
            assertFalse(it, matcher.matches(it, NumberRule.DEFAULT))
        }
    }

    @Test
    fun prefixAndLengthAreConfigurable() {
        val rule = NumberRule("8", 6)
        assertTrue(matcher.matches("812345", rule))
        assertFalse(matcher.matches("312345", rule))
        assertFalse(matcher.matches("81234", rule))
        assertFalse(matcher.matches("8123456", rule))
        assertTrue(matcher.matches("812345", NumberRule("81", 6)))
        assertFalse(matcher.matches("821345", NumberRule("81", 6)))
    }

    @Test
    fun emptyPrefixAllowsAnyAsciiDigitsOfConfiguredLength() {
        val rule = NumberRule("", 5)
        assertTrue(matcher.matches("01234", rule))
        assertTrue(matcher.matches("81234", rule))
        listOf("", "1234", "123456", "+1234", "１２３４５", "12a45").forEach {
            assertFalse(it, matcher.matches(it, rule))
        }
    }

    @Test
    fun validLengthBoundariesAndFullLengthPrefixAreSupported() {
        assertTrue(matcher.matches("0", NumberRule("", 1)))
        assertTrue(matcher.matches("0".repeat(30), NumberRule("", 30)))
        assertTrue(matcher.matches("812345", NumberRule("812345", 6)))
    }

    @Test
    fun nonAsciiDigitPrefixesAreRejected() {
        listOf("+3", "3a", "３", "٣", "3 ", "-3").forEach { prefix ->
            assertThrows(IllegalArgumentException::class.java) { NumberRule(prefix, 5) }
        }
    }

    @Test
    fun lengthsOutsideOneToThirtyAreRejected() {
        listOf(-1, 0, 31).forEach { length ->
            assertThrows(IllegalArgumentException::class.java) { NumberRule("", length) }
        }
    }

    @Test
    fun prefixLongerThanLengthIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { NumberRule("123", 2) }
    }
    @Test fun snapshotsMatchAnyRuleAndEmptyMatchesNothing() {
        val snapshot = com.greatwall.callring.domain.calling.NumberRuleSnapshot(
            listOf(NumberRule("3", 5), NumberRule("08", 6)), 7)
        assertTrue(snapshot.matches("31234", matcher))
        assertTrue(snapshot.matches("081234", matcher))
        assertFalse(snapshot.matches("812345", matcher))
        assertFalse(com.greatwall.callring.domain.calling.NumberRuleSnapshot(emptyList(), 8).matches("31234", matcher))
    }

    @Test fun snapshotDetachesRuleListAndStopsAtFirstMatchingRule() {
        val rules = mutableListOf(NumberRule("3", 5), NumberRule("31", 5))
        val snapshot = com.greatwall.callring.domain.calling.NumberRuleSnapshot(rules, 1)
        rules.clear()
        var calls = 0
        assertTrue(snapshot.matches("31234", NumberMatcher { n, rule -> calls++; matcher.matches(n, rule) }))
        assertEquals(1, calls)
        assertEquals(2, snapshot.rules.size)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.rules as MutableList<*>).clear() }
    }

}
