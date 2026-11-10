package com.greatwall.callring.ui.feature.callers

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class CallHistoryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rowActionsDoNotNavigateAndReturningFromDetailPreservesScroll() =
        CallHistoryScreenCases.rowActionsDoNotNavigateAndReturningFromDetailPreservesScroll(compose)
}
