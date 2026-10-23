package com.greatwall.callring.ui.feature.callers

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.callers.NumberCheck
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.ui.testing.TestChangeFeed
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NumberLookupViewModelTest {
    @Test fun validProfileNumberAndMatchingRuleAreDisplayedAsSeparateFacts() = runTest {
        val model = NumberLookupViewModel({ NumberCheck("normalized", true, false) }, SavedStateHandle(), TestChangeFeed(), backgroundScope)
        model.edit("input"); runCurrent()
        assertEquals("normalized", model.checkedNumber())
        assertFalse(model.result.value.matchesRule)
    }
    @Test fun ruleChangeUpdatesObservableResultWithoutEditingInput() = runTest {
        val feed = TestChangeFeed(); var matches = false; var checks = 0
        val model = NumberLookupViewModel({ checks++; NumberCheck(it, true, matches) }, SavedStateHandle(mapOf("number" to "812345")), feed, backgroundScope)
        runCurrent(); assertFalse(model.result.value.matchesRule)
        matches = true; feed.publish(setOf(DataDomain.NUMBER_RULES)); runCurrent()
        assertEquals("812345", model.text.value); assertTrue(model.result.value.matchesRule)
        val before = checks; feed.publish(setOf(DataDomain.RECORDING_ARCHIVES)); runCurrent(); assertEquals(before, checks)
    }
}
