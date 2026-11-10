package com.greatwall.callring.ui.feature.recording

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
import org.junit.Assert.assertEquals

internal object CallerSuggestionsScreenCases {
    fun textOnlyArchiveCanAnalyzeAndOnlySelectedFieldsAreSubmitted(compose: ComposeContentTestRule) {
        var requests = 0
        var fields: Set<CallerSuggestionField>? = null
        val archive = CallArchive(-7, "31234", 0, transcript = "我是张三，在技术部工作。",
            status = ArchiveStatus.COMPLETE, manualAnalysisState = ManualAnalysisState.PENDING_CONFIRMATION)
        val suggestions = CallerSuggestions(-7, "31234", 2, "example-token", listOf(
            CallerSuggestion(CallerSuggestionField.NAME, "李四", "张三", "我是张三"),
            CallerSuggestion(CallerSuggestionField.DEPARTMENT, "", "技术部", "在技术部工作"),
        ))
        compose.setContent {
            ArchiveScreen(
                LoadState(archive, loaded = true), LoadState(), MutationState(),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onAnalyze = { requests++ },
                suggestions = ArchiveSuggestionsState(load = LoadState(suggestions, loaded = true)),
                onConfirmSuggestions = { _, selected -> fields = selected },
            )
        }
        compose.onNodeWithText("播放").assertDoesNotExist()
        compose.onNodeWithText("展开转写").performScrollTo().performClick()
        compose.onNodeWithText("我是张三，在技术部工作。").assertExists()
        compose.onNodeWithText("AI 分析").performScrollTo().performClick()
        compose.onNodeWithText("当前：李四").assertExists()
        compose.onNodeWithText("建议：张三").assertExists()
        compose.onNodeWithText("原文：我是张三").assertExists()
        compose.onNodeWithText("确认写入档案").assertIsNotEnabled()
        compose.onNodeWithText("姓名").performScrollTo().performClick()
        compose.onNodeWithText("确认写入档案").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, requests)
            assertEquals(setOf(CallerSuggestionField.NAME), fields)
        }
    }
}
