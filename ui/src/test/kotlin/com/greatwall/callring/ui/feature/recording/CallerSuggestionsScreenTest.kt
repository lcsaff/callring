package com.greatwall.callring.ui.feature.recording

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class CallerSuggestionsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun textOnlyArchiveCanAnalyzeAndOnlySelectedFieldsAreSubmitted() =
        CallerSuggestionsScreenCases.textOnlyArchiveCanAnalyzeAndOnlySelectedFieldsAreSubmitted(compose)
}
