package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.testing.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingScreensTest {
    @get:Rule val compose = createComposeRule()
    @Test fun archiveNavigationWorksWithStatisticsApisUnavailable() {
        val archive = CallArchive(-51, "10086", 0, status = ArchiveStatus.COMPLETE)
        val api = object : com.greatwall.callring.ui.testing.UiFakeApi() {
            override val callers: CallersApi get() = error("Archive list must not access caller statistics")
            override val reports: ReportsApi get() = error("Archive list must not access reports")
            override val recordings = object : RecordingApi by FixtureRecordingApi {
                override suspend fun listArchives() = ApiResult.Success(listOf(archive))
            }
        }
        compose.setContent { com.greatwall.callring.ui.app.CallringApp(api,
            requestedDestination = com.greatwall.callring.ui.app.UiDestination.RecordingArchives,
            onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithText("10086").assertIsDisplayed()
        compose.onNodeWithText("已完成").assertIsDisplayed()
    }

    @Test fun independentArchiveListDisplaysStateAndOpensOpaqueId() {
        val archive = CallArchive(-42, "10086", 0, status = ArchiveStatus.COMPLETE,
            analysis = CallAnalysis(summary = "商量下次会议"))
        var selected: CallArchive? = null
        var refreshes = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            RecordingArchivesScreen(LoadState(listOf(archive), loaded = true), {}, { refreshes++ }, { selected = it })
        } }
        compose.onNodeWithText("10086").assertIsDisplayed()
        compose.onNodeWithText("已完成").assertIsDisplayed()
        compose.onNodeWithText("商量下次会议").assertIsDisplayed()
        compose.onNodeWithText("10086").performClick()
        assertEquals(archive, selected)
        compose.onNodeWithText("刷新").performClick()
        assertEquals(1, refreshes)
    }

    @Test fun listOpensArchiveDetailUsingNegativeRecordingIdWithoutReports() {
        val archive = CallArchive(-91, "10086", 0, status = ArchiveStatus.COMPLETE)
        var requestedId: Long? = null
        val api = object : com.greatwall.callring.ui.testing.UiFakeApi() {
            override val reports: ReportsApi get() = error("Recording navigation must not access reports")
            override val recordings = object : RecordingApi by FixtureRecordingApi {
                override suspend fun listArchives() = ApiResult.Success(listOf(archive))
                override suspend fun getArchive(callId: Long): ApiResult<CallArchive?> {
                    requestedId = callId
                    return ApiResult.Success(archive)
                }
            }
        }
        compose.setContent { com.greatwall.callring.ui.app.CallringApp(api,
            requestedDestination = com.greatwall.callring.ui.app.UiDestination.RecordingArchives,
            onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithText("10086").performClick()
        compose.onNodeWithText("通话录音归档").assertIsDisplayed()
        compose.waitUntil { requestedId != null }
        assertEquals(-91L, requestedId)
    }

    @Test fun independentArchiveListShowsEmptyAndErrorFeedback() {
        val state = mutableStateOf(LoadState<List<CallArchive>>(emptyList(), loaded = true))
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) { RecordingArchivesScreen(state.value, {}, {}, {}) } }
        compose.onNodeWithText("暂无通话录音").assertIsDisplayed()
        compose.runOnIdle { state.value = LoadState(error = ApiError(ErrorCode.STORAGE_FAILURE, "recording.storage_or_network")) }
        compose.onNodeWithText(recordingMessage("recording.storage_or_network")).assertIsDisplayed()
    }

    @Test fun recordingSettingsHasNoSaveOrAutoAnalysisAndSwitchesCommitImmediately() {
        val view = RecordingSettingsView(recordingEnabled = false, automaticAnalysis = true,
            captureMode = CaptureMode.SYSTEM_ONLY, folderConfigured = false, revision = 1)
        var enabled: Boolean? = null
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            RecordingSettingsScreen(LoadState(view, loaded = true), MutationState(), {}, {}, { enabled = it }, {}, {}, {})
        } }
        compose.onNodeWithTag("settings-save").assertDoesNotExist()
        compose.onNodeWithText("保存设置").assertDoesNotExist()
        compose.onNodeWithText("录音就绪后自动分析").assertDoesNotExist()
        compose.onNode(hasContentDescription("启用录音归档") and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState))
            .performClick()
        compose.runOnIdle { assertEquals(true, enabled) }
    }

    @Test fun deletingArchiveRequiresExplicitConfirmation() {
        var deletes = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            ArchiveScreen(LoadState(CallArchive(1, "10086", 0), loaded = true), LoadState(), MutationState(),
                {}, {}, {}, {}, {}, {}, { deletes++ }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("删除本次归档").performScrollTo().performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("删除本次归档").performClick()
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        assertEquals(1, deletes)
    }
}
