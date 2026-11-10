package com.greatwall.callring.ui.feature.callers

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class CallHistoryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rowActionsDoNotNavigateAndReturningFromDetailPreservesScroll() =
        CallHistoryScreenCases.rowActionsDoNotNavigateAndReturningFromDetailPreservesScroll(compose)

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun datesAndActionsRemainUsableInNarrowLargeType() =
        CallHistoryScreenCases.datesAndActionsRemainUsableInNarrowLargeType(compose)
}
