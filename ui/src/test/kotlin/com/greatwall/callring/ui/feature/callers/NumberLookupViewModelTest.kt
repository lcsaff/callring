package com.greatwall.callring.ui.feature.callers

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.callers.NumberCheck
import org.junit.Assert.*
import org.junit.Test

class NumberLookupViewModelTest {
    @Test fun validProfileNumberAndMatchingRuleAreDisplayedAsSeparateFacts() {
        val model = NumberLookupViewModel({ NumberCheck("normalized", true, false) }, SavedStateHandle())
        model.edit("input")
        assertEquals("normalized", model.checkedNumber())
        assertFalse(model.result().matchesRule)
    }
}
