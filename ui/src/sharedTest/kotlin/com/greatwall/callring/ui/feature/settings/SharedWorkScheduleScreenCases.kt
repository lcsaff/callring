package com.greatwall.callring.ui.feature.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.settings.TimeWindow
import com.greatwall.callring.contracts.settings.WorkScheduleSettings
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

abstract class SharedWorkScheduleScreenCases {
    @get:Rule val compose = createComposeRule()
    private val morning = TimeWindow(540, 720)
    private val afternoon = TimeWindow(780, 1080)
    private val saved = WorkScheduleSettings("Asia/Shanghai",
        mapOf(1 to listOf(morning, afternoon), 2 to listOf(TimeWindow(600, 1200))),
        listOf(TimeWindow(600, 1200)), 1, 0)

    @Test fun commonWorkdayEditorHasFourTimesAndNoWeekdayOrMakeupEditor() {
        compose.setContent { CallringTheme {
            WorkScheduleScreen(LoadState(saved, loaded = true), saved, MutationState(), {}, {}, {}, {})
        } }
        listOf("work-start", "work-lunch-start", "work-lunch-end", "work-end").forEach {
            compose.onNodeWithTag("time-$it").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("星期一").assertDoesNotExist()
        compose.onNodeWithText("调休上班时段").assertDoesNotExist()
    }

    @Test fun changingCommonTimeUpdatesEveryWorkdayAndMakeupTogether() {
        val draft = mutableStateOf(saved)
        compose.setContent { CallringTheme {
            WorkScheduleScreen(LoadState(saved, loaded = true), draft.value, MutationState(), {}, {},
                { draft.value = it }, {})
        } }
        chooseHour("work-start", "08")
        compose.runOnIdle {
            val expected = listOf(TimeWindow(480, 720), afternoon)
            (1..5).forEach { assertEquals(expected, draft.value.weekly[it]) }
            (6..7).forEach { assertEquals(emptyList<TimeWindow>(), draft.value.weekly[it]) }
            assertEquals(expected, draft.value.makeup)
        }
    }

    @Test fun invalidTimeOrderStaysLocalAndResetClearsItWithTheZone() {
        val draft = mutableStateOf(saved)
        val mutation = mutableStateOf(MutationState())
        val commits = mutableListOf<WorkScheduleSettings>()
        compose.setContent { CallringTheme {
            WorkScheduleScreen(LoadState(saved, loaded = true), draft.value, mutation.value, {}, {},
                { commits += it; draft.value = it }, {
                    draft.value = saved
                    mutation.value = MutationState(completed = mutation.value.completed + 1)
                })
        } }
        compose.onNodeWithTag("work-zone").performScrollTo().performClick()
        compose.onNodeWithText("UTC").performClick()
        compose.runOnIdle { assertEquals("UTC", draft.value.zoneId) }
        val submitted = commits.size
        chooseHour("work-start", "12")
        compose.onNodeWithTag("settings-save").assertDoesNotExist()
        compose.onNodeWithText("请按上班、午休开始、午休结束、下班的顺序设置同一天的时间").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(submitted, commits.size) }
        compose.onNodeWithText("恢复默认工作时间").performScrollTo().performClick()
        compose.onNodeWithText("确认").performClick()
        compose.onNodeWithTag("work-zone").performScrollTo().assertTextContains("Asia/Shanghai")
        compose.onNodeWithTag("time-work-start").performScrollTo().assertTextContains("09:00")
    }

    @Test fun legacySingleWindowGetsEditableLunchDefaultsWithinItsHours() {
        val legacy = saved.copy(weekly = mapOf(3 to listOf(TimeWindow(540, 1080))))
        compose.setContent { CallringTheme {
            WorkScheduleScreen(LoadState(legacy, loaded = true), legacy, MutationState(), {}, {}, {}, {})
        } }
        compose.onNodeWithTag("time-work-start").performScrollTo().assertTextContains("09:00")
        compose.onNodeWithTag("time-work-lunch-start").performScrollTo().assertTextContains("12:00")
        compose.onNodeWithTag("time-work-lunch-end").performScrollTo().assertTextContains("13:00")
        compose.onNodeWithTag("time-work-end").performScrollTo().assertTextContains("18:00")
    }

    private fun chooseHour(key: String, hour: String) {
        compose.onNodeWithTag("time-$key").performScrollTo().performClick()
        compose.onNodeWithText("切换为键盘输入").performClick()
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("time-input")))[0]
            .performTextReplacement(hour)
        compose.onNodeWithText("确定").performClick()
    }
}
