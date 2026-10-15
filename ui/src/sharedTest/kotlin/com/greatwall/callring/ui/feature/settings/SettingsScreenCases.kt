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
import java.time.format.DateTimeFormatter
import java.util.Locale

abstract class SettingsScreenCases {
    @get:Rule val compose = createComposeRule()

    @Test fun timeWindowPickerCancellationKeepsDraftAndOvernightIsExplicit() {
        val windows = mutableStateOf(listOf(TimeWindow(1320, 420)))
        compose.setContent {
            CallringTheme {
                Column {
                    com.greatwall.callring.ui.feature.settings.components.WindowsField(
                        "测试时段", windows.value, true, { windows.value = it }, {}, MutationState())
                }
            }
        }
        compose.onNodeWithText("跨至次日").assertIsDisplayed()
        compose.onNodeWithTag("time-测试时段-1-开始").performClick()
        compose.onNodeWithText("切换为键盘输入").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(listOf(TimeWindow(1320, 420)), windows.value) }
        compose.onNodeWithText("添加时段").performClick()
        compose.runOnIdle { assertEquals(listOf(TimeWindow(1320, 420), TimeWindow(540, 720)), windows.value) }
    }

    @Test fun calendarDeletionRequiresConfirmationAndCancelKeepsEntry() {
        val date = LocalDate.of(2026, 10, 15)
        val entry = DateOverride(date, DateOverrideKind.REST, emptyList())
        val deleted = mutableListOf<LocalDate>()
        compose.setContent {
            CallringTheme {
                CalendarScreen(LoadState(CalendarInfo(emptyList(), listOf(entry)), loaded = true),
                    MutationState(), {}, {}, {}, {}, { deleted += it })
            }
        }
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
    }

    @Test fun equalWindowEndpointsStayLocalAndDisableSaving() {
        val initial = listOf(TimeWindow(540, 720))
        val windows = mutableStateOf(initial)
        val valid = mutableStateOf(true)
        compose.setContent {
            CallringTheme {
                Column {
                    com.greatwall.callring.ui.feature.settings.components.WindowsField(
                        "测试时段", windows.value, true, { windows.value = it }, { valid.value = it }, MutationState())
                }
            }
        }
        compose.onNodeWithTag("time-测试时段-1-开始").performClick()
        compose.onNodeWithText("切换为键盘输入").performClick()
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("time-input")))[0].performTextReplacement("12")
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("开始和结束时间不能相同").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(!valid.value)
            assertEquals(initial, windows.value)
        }
        compose.onNodeWithText("移除时段 1").performClick()
        compose.runOnIdle {
            assertTrue(valid.value)
            assertTrue(windows.value.isEmpty())
        }
    }

    @Test fun timedLeaveRejectsNonexistentDstLocalTimeAfterDateSelection() {
        val entry = LeaveEntry(17L, Instant.parse("2026-03-07T07:30:00Z").toEpochMilli(),
            Instant.parse("2026-03-09T08:00:00Z").toEpochMilli(), "", LeaveState.UPCOMING)
        var command: SaveLeave? = null
        compose.setContent {
            CallringTheme {
                LeavesScreen(LoadState(listOf(entry), loaded = true), MutationState(), "America/New_York", {}, {}, { command = it }, {})
            }
        }
        compose.onNodeWithText("编辑").performScrollTo().performClick()
        selectDate("开始日期", LocalDate.of(2026, 3, 8))
        compose.onNodeWithText("开始时间不存在，请检查时区的夏令时切换").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(null, command) }
    }

    @Test fun pickerDatesRoundTripAsUtcDatesRegardlessOfBusinessZone() {
        val date = LocalDate.of(2026, 11, 1)
        val millis = com.greatwall.callring.ui.designsystem.components.dateToPickerMillis(date)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(), millis)
        assertEquals(date, com.greatwall.callring.ui.designsystem.components.pickerMillisToDate(millis))
    }

    @Test fun timePickerDraftSurvivesRestorationBeforeConfirmation() {
        val value = mutableStateOf("09:00")
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CallringTheme {
                com.greatwall.callring.ui.designsystem.components.TimeSelectionField("测试时间", value.value, { value.value = it })
            }
        }
        compose.onNodeWithTag("time-测试时间").performClick()
        compose.onNodeWithText("切换为键盘输入").performClick()
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("time-input")))[0].performTextReplacement("11")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("切换为表盘选择").assertExists()
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("time-input")))[0].assertTextContains("11")
        compose.runOnIdle { assertEquals("09:00", value.value) }
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals("11:00", value.value) }
    }

    @Test fun reportRangeCancellationAndConfirmationPreserveBusinessZone() {
        val range = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), "Pacific/Honolulu")
        val commands = mutableListOf<DateRange>()
        compose.setContent {
            CallringTheme {
                com.greatwall.callring.ui.feature.report.ReportScreen(
                    LoadState<CallerReport>(), ReportQuery("100", range), MutationState(), {}, {}, { commands += it }, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("2026-10-01 至 2026-10-15").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(commands.isEmpty()) }
        compose.onNodeWithText("2026-10-01 至 2026-10-15").performClick()
        compose.onNodeWithText("查询").performClick()
        compose.runOnIdle { assertEquals(listOf(range), commands) }
    }

    @Test fun changedLeaveNoteRequiresDiscardConfirmation() {
        compose.setContent {
            CallringTheme {
                LeavesScreen(LoadState<List<LeaveEntry>>(emptyList(), loaded = true), MutationState(), "Asia/Shanghai", {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("添加请假").performClick()
        compose.onNode(hasSetTextAction() and hasText("备注")).performScrollTo().performTextReplacement("保留草稿")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithText("备注").assertDoesNotExist()
    }

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
        compose.onNodeWithText("确认").performClick()
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
        compose.onNodeWithText("确认").performClick()
        compose.onNodeWithTag("score-repeatMinutes").performScrollTo().assertTextEquals("重复来电窗口（分钟）", "")
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsNotEnabled()
    }

    @Test fun workResetReplacesInvalidZoneAndEditedWindowWhenDefaultsHaveNotChanged() {
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
        compose.onNodeWithTag("time-星期一-1-开始").performScrollTo().performClick()
        compose.onNodeWithText("切换为键盘输入").performClick()
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("time-input")))[0].performTextReplacement("12")
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("开始和结束时间不能相同").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("恢复默认工作时间").performScrollTo().performClick()
        compose.onNodeWithText("确认").performClick()
        compose.onNode(hasSetTextAction() and hasText("时区")).performScrollTo().assertTextContains("Asia/Shanghai")
        compose.onNodeWithTag("time-星期一-1-开始").performScrollTo().assertTextContains("开始 09:00")
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

    @Test fun invalidHexStaysLocalAndPreservesLastValidAppliedColor() {
        val initial = DefaultAppearance.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = 0x123456)
        val draft = mutableStateOf(initial)
        compose.setContent {
            CallringTheme {
                AppearanceScreen(LoadState(initial, loaded = true), draft.value, MutationState(), {}, {},
                    { draft.value = it }, {})
            }
        }
        compose.onNodeWithTag("appearance-hex").performScrollTo().performTextReplacement("#GGGGGG")
        compose.onNodeWithTag("settings-save").assertDoesNotExist()
        compose.onNodeWithText("请输入六位十六进制颜色").performScrollTo().assertIsDisplayed()
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

    @Test fun selectingPresetAppliesAfterInvalidCustomHexWithoutLosingStoredPreferences() {
        val initial = DefaultAppearance.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = 0x123456, colorMode = ColorMode.DARK)
        val draft = mutableStateOf(initial)
        compose.setContent {
            CallringTheme {
                AppearanceScreen(LoadState(initial, loaded = true), draft.value, MutationState(), {}, {},
                    { draft.value = it }, {})
            }
        }
        compose.onNodeWithTag("appearance-hex").performScrollTo().performTextReplacement("#GGGGGG")
        compose.onNodeWithTag("settings-save").assertDoesNotExist()
        compose.onNodeWithTag("preset-sky_blue").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(initial.copy(selection = ThemeSelection.PRESET, presetId = "sky_blue"), draft.value)
        }
    }

    @Test fun appearancePresetsScrollHorizontallyAndModePrecedesIntegratedCustomPreview() {
        compose.setContent {
            CallringTheme {
                AppearanceScreen(LoadState(DefaultAppearance, loaded = true), DefaultAppearance,
                    MutationState(busy = true), {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("settings-save").assertDoesNotExist()
        compose.onNodeWithText("效果预览").assertDoesNotExist()
        compose.onNodeWithTag("appearance-presets")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        compose.onNodeWithTag("preset-sky_blue").performScrollTo().assertIsEnabled()
        val modeY = compose.onNodeWithText("显示模式").fetchSemanticsNode().positionInRoot.y
        val customY = compose.onNodeWithText("自定义主题色").fetchSemanticsNode().positionInRoot.y
        assertTrue(modeY < customY)
        compose.onNode(hasTestTag("appearance-custom-preview") and hasAnyAncestor(hasTestTag("appearance-custom")),
            useUnmergedTree = true).assertExists()
    }

    @Test fun appearanceBackLeavesImmediatelyAfterAppliedChoiceAndInvalidLocalHex() {
        val initial = DefaultAppearance.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = 0x123456)
        val draft = mutableStateOf(initial)
        var backedOut = 0
        compose.setContent {
            CallringTheme {
                AppearanceScreen(LoadState(initial, loaded = true), draft.value, MutationState(),
                    { backedOut++ }, {}, { draft.value = it }, {})
            }
        }
        compose.onNodeWithTag("appearance-hex").performScrollTo().performTextReplacement("#ABCDEF")
        compose.runOnIdle { assertEquals(0xABCDEF, draft.value.customSeedRgb) }
        compose.onNodeWithTag("appearance-hex").performTextReplacement("#invalid")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertEquals(1, backedOut) }
        compose.onNodeWithText("放弃更改").assertDoesNotExist()
    }

    @Test fun appearanceFailureOffersRetryAndKeepsChoicesEnabled() {
        var retries = 0
        compose.setContent {
            CallringTheme {
                AppearanceScreen(LoadState(DefaultAppearance, loaded = true), DefaultAppearance,
                    MutationState(error = ApiError(ErrorCode.STORAGE_FAILURE, "颜色保存失败")),
                    {}, {}, {}, { retries++ })
            }
        }
        compose.onNodeWithText("颜色保存失败").assertIsDisplayed()
        compose.onNodeWithText("重试保存").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithTag("preset-sky_blue").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("settings-save").assertDoesNotExist()
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
        selectDate("日期", LocalDate.of(2026, 11, 2))
        compose.onNodeWithText("工作日").performClick()
        compose.onNodeWithText("添加时段").performScrollTo().performClick()
        compose.runOnIdle { mutation.value = MutationState() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("date-日期").assertTextContains("日期：2026-11-02")
        compose.onNodeWithTag("time-工作时段-1-开始").performScrollTo().assertTextContains("开始 09:00")
        compose.onNodeWithTag("time-工作时段-1-结束").performScrollTo().assertTextContains("结束 12:00")
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
        selectDate("开始日期", LocalDate.of(2026, 10, 20))
        selectDate("结束日期（含当天）", LocalDate.of(2026, 10, 22))
        compose.onNode(hasSetTextAction() and hasText("备注")).performScrollTo().performTextReplacement("恢复请假草稿")
        compose.runOnIdle { mutation.value = MutationState() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("date-开始日期").assertTextContains("开始日期：2026-10-20")
        compose.onNodeWithTag("date-结束日期（含当天）").assertTextContains("结束日期（含当天）：2026-10-22")
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
    private fun selectDate(label: String, date: LocalDate) {
        compose.onNodeWithTag("date-$label").performScrollTo().performClick()
        compose.onNodeWithText("输入日期").performClick()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMd")
            .replace(Regex("y+"), "yyyy").replace(Regex("M+"), "MM").replace(Regex("d+"), "dd")
        val input = date.format(DateTimeFormatter.ofPattern(pattern)).filter(Char::isDigit)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("date-picker-$label")))
            .performTextReplacement(input)
        compose.onNodeWithText("确定").performClick()
    }
}
