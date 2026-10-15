package com.greatwall.callring.ui.feature.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.designsystem.DefaultAppearance
import com.greatwall.callring.ui.feature.settings.components.FieldError
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

abstract class SettingsScreenCases {
    @get:Rule val compose = createComposeRule()

    @Test fun scoreResetReplacesInvalidTextEvenWhenDefaultsHaveNotChanged() {
        val saved = ScoreSettings(5, 2, 10, 30)
        val draft = mutableStateOf(saved)
        val mutation = mutableStateOf(MutationState())
        compose.setContent {
            CallringTheme {
                ScoreSettingsScreen(LoadState(saved, loaded = true), draft.value, mutation.value, {}, {},
                    { draft.value = it }, {}, {
                        draft.value = saved
                        mutation.value = MutationState(completed = mutation.value.completed + 1)
                    })
            }
        }
        compose.onNodeWithTag("score-repeatMinutes").performScrollTo().performTextClearance()
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("恢复默认评分设置").performScrollTo().performClick()
        compose.onNodeWithTag("score-repeatMinutes").performScrollTo().assertTextContains("10")
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsEnabled()
    }

    @Test fun failedScoreResetKeepsInvalidInputForCorrection() {
        val saved = ScoreSettings(5, 2, 10, 30)
        val mutation = mutableStateOf(MutationState())
        compose.setContent {
            CallringTheme {
                ScoreSettingsScreen(LoadState(saved, loaded = true), saved, mutation.value, {}, {}, {}, {}, {
                    mutation.value = MutationState(error = ApiError(ErrorCode.STORAGE_FAILURE, "reset failed"))
                })
            }
        }
        compose.onNodeWithTag("score-repeatMinutes").performScrollTo().performTextClearance()
        compose.onNodeWithText("恢复默认评分设置").performScrollTo().performClick()
        compose.onNodeWithTag("score-repeatMinutes").performScrollTo().assertTextEquals("重复来电窗口（分钟）", "")
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsNotEnabled()
    }

    @Test fun workResetReplacesInvalidZoneAndWindowTextWhenDefaultsHaveNotChanged() {
        val saved = WorkScheduleSettings("Asia/Shanghai", mapOf(1 to listOf(TimeWindow(540, 720))),
            listOf(TimeWindow(540, 720)), 1, 0)
        val draft = mutableStateOf(saved)
        val mutation = mutableStateOf(MutationState())
        compose.setContent {
            CallringTheme {
                WorkScheduleScreen(LoadState(saved, loaded = true), draft.value, mutation.value, {}, {},
                    { draft.value = it }, {}, {
                        draft.value = saved
                        mutation.value = MutationState(completed = mutation.value.completed + 1)
                    })
            }
        }
        compose.onNode(hasSetTextAction() and hasText("时区")).performScrollTo().performTextReplacement("invalid-zone")
        compose.onNode(hasSetTextAction() and hasText("星期一")).performScrollTo().performTextReplacement("invalid-window")
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("恢复默认工作时间").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("时区")).performScrollTo().assertTextContains("Asia/Shanghai")
        compose.onNode(hasSetTextAction() and hasText("星期一")).performScrollTo().assertTextContains("09:00-12:00")
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsEnabled()
    }

    @Test fun deniedPermissionsHaveAnApplicationSettingsRecoveryEntry() {
        var settingsOpened = 0
        val status = RuntimeStatus(Capability.entries.associateWith { AccessState.DENIED }, Audibility.UNKNOWN,
            PhoneActivity.UNKNOWN, PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE), false, 0)
        compose.setContent {
            CallringTheme {
                RuntimeScreen(LoadState(RuntimeUiData(status, emptyList(), null), loaded = true),
                    MutationState(), {}, {}, {}, {}, { settingsOpened++ })
            }
        }
        compose.onNodeWithText("打开应用设置").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, settingsOpened) }
    }

    @Test fun invalidHexDisablesSaveAndPreservesLastValidDraft() {
        val initial = DefaultAppearance.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = 0x123456)
        val draft = mutableStateOf(initial)
        compose.setContent {
            CallringTheme {
                AppearanceScreen(LoadState(initial, loaded = true), draft.value, MutationState(), {}, {},
                    { draft.value = it }, {})
            }
        }
        compose.onNodeWithTag("appearance-hex").performScrollTo().performTextReplacement("#GGGGGG")
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0x123456, draft.value.customSeedRgb) }
    }

    @Test fun presetCallbackPreservesCustomSeedAndColorMode() {
        val initial = DefaultAppearance.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = 0x123456, colorMode = ColorMode.DARK)
        val draft = mutableStateOf(initial)
        compose.setContent {
            CallringTheme {
                AppearanceScreen(LoadState(initial, loaded = true), draft.value, MutationState(), {}, {},
                    { draft.value = it }, {})
            }
        }
        compose.onNodeWithTag("preset-sky_blue").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ThemeSelection.PRESET, draft.value.selection)
            assertEquals("sky_blue", draft.value.presetId)
            assertEquals(0x123456, draft.value.customSeedRgb)
            assertEquals(ColorMode.DARK, draft.value.colorMode)
        }
    }

    @Test fun saveFailureRetainsDraftAndShowsAdjacentFieldError() {
        val saved = ScoreSettings(1, 2, 5, 30)
        val draft = saved.copy(repeatMinutes = 12)
        val mutation = MutationState(error = ApiError(ErrorCode.VALIDATION, "保存失败", mapOf("repeatMinutes" to "重复窗口不合法")))
        compose.setContent {
            CallringTheme {
                ScoreSettingsScreen(LoadState(saved, loaded = true), draft, mutation, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("重复窗口不合法").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("score-repeatMinutes").assertTextContains("12")
    }

    @Test fun nestedErrorsAppearBesideTheirOwningWindowFields() {
        val mutation = MutationState(error = ApiError(ErrorCode.VALIDATION, "时段无效", mapOf(
            "weekly.1.0.endMinute" to "周一结束时间无效",
            "weeklyWindows.2.0.startMinute" to "周二免打扰开始时间无效",
            "makeup.0.endMinute" to "调休结束时间无效",
            "windows.0.startMinute" to "日期覆盖开始时间无效",
            "weekly.10.0.endMinute" to "不应匹配星期一",
        )))
        compose.setContent {
            CallringTheme {
                Column {
                    FieldError(mutation, "weekly.1")
                    FieldError(mutation, "weeklyWindows.2")
                    FieldError(mutation, "makeup")
                    FieldError(mutation, "windows")
                }
            }
        }
        compose.onNodeWithText("周一结束时间无效").assertIsDisplayed()
        compose.onNodeWithText("周二免打扰开始时间无效").assertIsDisplayed()
        compose.onNodeWithText("调休结束时间无效").assertIsDisplayed()
        compose.onNodeWithText("日期覆盖开始时间无效").assertIsDisplayed()
        compose.onNodeWithText("不应匹配星期一").assertDoesNotExist()
    }

    @Test fun customQuietWithNoWindowCannotBeSaved() {
        val value = QuietSettings(QuietMode.CUSTOM, false, (1..7).associateWith { emptyList<TimeWindow>() })
        compose.setContent { CallringTheme { QuietScreen(LoadState(value, loaded = true), value, MutationState(), {}, {}, {}, {}) } }
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsNotEnabled()
    }

    @Test fun invalidStoredCustomSeedUsesValidPreviewAndOnlyChangesOnUserInput() {
        val initial = DefaultAppearance.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = -1)
        val draft = mutableStateOf(initial)
        compose.setContent {
            CallringTheme {
                AppearanceScreen(LoadState(initial, loaded = true), draft.value, MutationState(), {}, {}, { draft.value = it }, {})
            }
        }
        compose.onNodeWithTag("appearance-hex").performScrollTo().assertTextContains("#E60012")
        compose.runOnIdle { assertEquals(-1, draft.value.customSeedRgb) }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))[0]
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(42f) }
        compose.runOnIdle {
            assertEquals(0x2A0012, draft.value.customSeedRgb)
            assertTrue(draft.value.customSeedRgb!! in 0x000000..0xFFFFFF)
        }
    }

    @Test fun noteOnlyTimedLeaveEditPreservesExactEpochsInDstOverlap() {
        val start = Instant.parse("2026-11-01T06:30:00.123Z").toEpochMilli()
        val end = Instant.parse("2026-11-01T07:45:00.987Z").toEpochMilli()
        val entry = LeaveEntry(17L, start, end, "原备注", LeaveState.UPCOMING)
        var command: SaveLeave? = null
        compose.setContent {
            CallringTheme {
                LeavesScreen(LoadState(listOf(entry), loaded = true), MutationState(), "America/New_York", {}, {}, { command = it }, {})
            }
        }
        compose.onNodeWithText("编辑").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("备注")).performScrollTo().performTextReplacement("更新备注")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(SaveLeave(17L, LeavePeriod.Timed(start, end), "更新备注"), command)
        }
    }

    @Test fun selectingPresetAllowsSaveAfterInvalidCustomHexWithoutLosingStoredPreferences() {
        val initial = DefaultAppearance.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = 0x123456, colorMode = ColorMode.DARK)
        val draft = mutableStateOf(initial)
        var saved: AppearanceSettings? = null
        compose.setContent {
            CallringTheme {
                AppearanceScreen(LoadState(initial, loaded = true), draft.value, MutationState(), {}, {},
                    { draft.value = it }, { saved = draft.value })
            }
        }
        compose.onNodeWithTag("appearance-hex").performScrollTo().performTextReplacement("#GGGGGG")
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("preset-sky_blue").performScrollTo().performClick()
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(initial.copy(selection = ThemeSelection.PRESET, presetId = "sky_blue"), saved)
        }
    }

    @Test fun rgbSlidersHaveAccessibleNamesAndAdjustableSemantics() {
        val initial = DefaultAppearance.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = 0x123456)
        compose.setContent {
            CallringTheme { AppearanceScreen(LoadState(initial, loaded = true), initial, MutationState(), {}, {}, {}, {}) }
        }
        listOf("红色 R", "绿色 G", "蓝色 B").forEach { label ->
            compose.onNode(hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun quietAllNumbersSwitchHasAccessibleNameAndInvokesChange() {
        val initial = QuietSettings(QuietMode.OFF, false, emptyMap())
        var changed: QuietSettings? = null
        compose.setContent {
            CallringTheme { QuietScreen(LoadState(initial, loaded = true), initial, MutationState(), {}, {}, { changed = it }, {}) }
        }
        compose.onNode(hasContentDescription("应用于所有号码") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .performScrollTo().performClick()
        compose.runOnIdle { assertEquals(initial.copy(allNumbers = true), changed) }
    }

    @Test fun restoredPositionDraftClosesAfterFirstSuccessOfNewMutationRunner() {
        val mutation = mutableStateOf(MutationState(completed = 1L))
        val commands = mutableListOf<SavePosition>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CallringTheme {
                PositionsScreen(LoadState(PositionSettings(emptyList(), null), loaded = true), mutation.value, {}, {},
                    { commands += it; mutation.value = MutationState(completed = mutation.value.completed + 1) }, {}, {})
            }
        }
        compose.onNodeWithText("添加职位").performClick()
        compose.onNode(hasSetTextAction() and hasText("职位名称")).performTextReplacement("恢复职位")
        compose.onNode(hasSetTextAction() and hasText("职位级别")).performTextReplacement("3")
        compose.runOnIdle { mutation.value = MutationState() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction() and hasText("职位名称")).assertTextContains("恢复职位")
        compose.onNode(hasSetTextAction() and hasText("职位级别")).assertTextContains("3")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(listOf(SavePosition(null, "恢复职位", 3, true)), commands) }
        compose.onNodeWithText("取消").assertDoesNotExist()
    }

    @Test fun restoredCalendarDraftClosesAfterFirstSuccessOfNewMutationRunner() {
        val mutation = mutableStateOf(MutationState(completed = 1L))
        val commands = mutableListOf<DateOverride>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CallringTheme {
                CalendarScreen(LoadState(CalendarInfo(emptyList(), emptyList()), loaded = true), mutation.value, {}, {}, {},
                    { commands += it; mutation.value = MutationState(completed = mutation.value.completed + 1) }, {})
            }
        }
        compose.onNodeWithText("添加日期覆盖").performClick()
        compose.onNode(hasSetTextAction() and hasText("日期（yyyy-MM-dd）")).performTextReplacement("2026-11-02")
        compose.onNodeWithText("工作日").performClick()
        compose.onNode(hasSetTextAction() and hasText("工作时段")).performScrollTo().performTextReplacement("09:00-12:00")
        compose.runOnIdle { mutation.value = MutationState() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction() and hasText("日期（yyyy-MM-dd）")).assertTextContains("2026-11-02")
        compose.onNode(hasSetTextAction() and hasText("工作时段")).performScrollTo().assertTextContains("09:00-12:00")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(listOf(DateOverride(LocalDate.of(2026, 11, 2), DateOverrideKind.WORK, listOf(TimeWindow(540, 720)))), commands)
        }
        compose.onNodeWithText("取消").assertDoesNotExist()
    }

    @Test fun restoredLeaveDraftClosesAfterFirstSuccessOfNewMutationRunner() {
        val mutation = mutableStateOf(MutationState(completed = 1L))
        val commands = mutableListOf<SaveLeave>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CallringTheme {
                LeavesScreen(LoadState<List<LeaveEntry>>(emptyList(), loaded = true), mutation.value, "Asia/Shanghai", {}, {},
                    { commands += it; mutation.value = MutationState(completed = mutation.value.completed + 1) }, {})
            }
        }
        compose.onNodeWithText("添加请假").performClick()
        compose.onNode(hasSetTextAction() and hasText("开始日期（yyyy-MM-dd）")).performScrollTo().performTextReplacement("2026-10-20")
        compose.onNode(hasSetTextAction() and hasText("结束日期（含当天）")).performScrollTo().performTextReplacement("2026-10-22")
        compose.onNode(hasSetTextAction() and hasText("备注")).performScrollTo().performTextReplacement("恢复请假草稿")
        compose.runOnIdle { mutation.value = MutationState() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction() and hasText("开始日期（yyyy-MM-dd）")).assertTextContains("2026-10-20")
        compose.onNode(hasSetTextAction() and hasText("结束日期（含当天）")).assertTextContains("2026-10-22")
        compose.onNode(hasSetTextAction() and hasText("备注")).performScrollTo().assertTextContains("恢复请假草稿")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(listOf(SaveLeave(null, LeavePeriod.AllDay(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 22), "Asia/Shanghai"), "恢复请假草稿")), commands)
        }
        compose.onNodeWithText("取消").assertDoesNotExist()
    }

    @Test fun restoredPositionDialogClosesWhenExistingSaveCompletesWhileCompositionIsAbsent() {
        val mutation = mutableStateOf(MutationState())
        val commands = mutableListOf<SavePosition>()
        var completeWhileDisposed = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DisposableEffect(Unit) {
                onDispose {
                    if (completeWhileDisposed) {
                        completeWhileDisposed = false
                        mutation.value = MutationState(completed = 1L)
                    }
                }
            }
            CallringTheme {
                PositionsScreen(LoadState(PositionSettings(emptyList(), null), loaded = true), mutation.value, {}, {},
                    { commands += it; mutation.value = MutationState(busy = true) }, {}, {})
            }
        }
        compose.onNodeWithText("添加职位").performClick()
        compose.onNode(hasSetTextAction() and hasText("职位名称")).performTextReplacement("旋转期间保存")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(listOf(SavePosition(null, "旋转期间保存", 1, true)), commands)
            assertTrue(mutation.value.busy)
            completeWhileDisposed = true
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(1L, mutation.value.completed) }
        compose.onNodeWithText("取消").assertDoesNotExist()
    }
}
