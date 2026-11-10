package com.greatwall.callring.ui.feature.recording

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class CallerSuggestionsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun textOnlyArchiveCanAnalyzeAndOnlySelectedFieldsAreSubmitted() =
        CallerSuggestionsScreenCases.textOnlyArchiveCanAnalyzeAndOnlySelectedFieldsAreSubmitted(compose)
}
