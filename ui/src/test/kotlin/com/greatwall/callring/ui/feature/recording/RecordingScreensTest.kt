package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
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

    @Test fun callDetailGroupsFactsAndImportActionsWhilePreservingMissingAndUnreadableStates() {
        val classification = ClassificationFact(false, WorkReason.OUTSIDE_WORK_HOURS, null, true, "Asia/Shanghai", 0, null, 0)
        val call = CallDetail(7, "31234", 0, null, null, CallResult.MISSED, CallDecision.NORMAL_RINGING,
            CallSource.SYSTEM_CALL_LOG, false, classification, classification, false, null)
        val recording = mutableStateOf(CallHistoryRecording(null, CallArchiveAvailability.NONE, false, false))
        var imports = 0
        compose.setContent {
            ArchiveScreen(LoadState(loaded = true), LoadState(), MutationState(), {}, {}, { imports++ }, {}, {},
                {}, {}, {}, {}, {}, history = LoadState(CallHistoryEntry(call, recording.value), loaded = true))
        }
        assertHeading("通话信息")
        compose.onNodeWithText("号码：31234").assertExists()
        compose.onNodeWithText("1970-01-01 08:00:00").assertExists()
        compose.onNodeWithText("时长：未知").assertExists()
        compose.onNodeWithText("工作分类：非工作时段").assertExists()
        compose.onNodeWithText("未接听").assertExists()
        assertHeading("录音与转写")
        compose.onNodeWithText("等待录音，尚无本次通话归档").assertExists()
        compose.onNodeWithText("播放").assertDoesNotExist()
        assertHeading("归档管理")
        compose.onNodeWithText("导入音频文件").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, imports) }
        compose.onNodeWithText("可导入已有录音", substring = true).assertDoesNotExist()
        compose.runOnIdle { recording.value = recording.value.copy(availability = CallArchiveAvailability.INDEXING) }
        compose.onNodeWithText("正在读取归档状态").assertExists()
        compose.onNodeWithText("等待录音，尚无本次通话归档").assertDoesNotExist()
        compose.runOnIdle { recording.value = recording.value.copy(availability = CallArchiveAvailability.UNREADABLE) }
        compose.onNodeWithText("录音归档无法读取").assertExists()
    }

    @Test fun recordingAndAnalysisSectionsFollowArchiveProgressAndRetainFeedback() {
        val archive = mutableStateOf(CallArchive(-7, "31234", 0, status = ArchiveStatus.READY, audioPath = "/owned/fixture.wav"))
        val playback = mutableStateOf(MutationState())
        var analyses = 0
        var plays = 0
        compose.setContent {
            ArchiveScreen(LoadState(archive.value, loaded = true), LoadState(), MutationState(), {}, {}, {}, {}, {},
                {}, {}, { plays++ }, {}, {}, playback = playback.value, onAnalyze = { analyses++ })
        }
        compose.onNodeWithText("播放").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, plays)
            playback.value = MutationState(error = ApiError(ErrorCode.STORAGE_FAILURE, "recording.missing_audio"))
        }
        compose.onNodeWithText(recordingMessage("recording.missing_audio")).assertExists()
        compose.onNodeWithText("录音：可播放").assertDoesNotExist()
        compose.onNodeWithText("转为文本").assertIsEnabled()
        compose.onNodeWithText("AI 分析").assertDoesNotExist()
        compose.runOnIdle { archive.value = archive.value.copy(audioPath = null, transcript = "本地全文") }
        assertHeading("分析与档案补全")
        compose.onNodeWithText("播放").assertDoesNotExist()
        compose.onNodeWithText("录音：缺少音频").assertExists()
        compose.onNodeWithText("转写：已有文字").assertDoesNotExist()
        compose.onNodeWithText("展开转写").performScrollTo().performClick()
        compose.onNodeWithText("本地全文").assertExists()
        compose.onNodeWithText("可能按服务商价格收费", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, analyses) }
        compose.onNodeWithText("AI 分析").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, analyses)
            archive.value = archive.value.copy(status = ArchiveStatus.ANALYZING)
        }
        compose.onNodeWithText("正在分析", substring = true).assertExists()
        compose.onNodeWithText("AI 分析").assertIsNotEnabled()
        compose.runOnIdle {
            archive.value = archive.value.copy(status = ArchiveStatus.COMPLETE,
                analysis = CallAnalysis(name = "张三", evidence = "我是张三", summary = "商量会议"),
                manualAnalysisState = ManualAnalysisState.CONFIRMED)
        }
        compose.onNodeWithText("自述依据：我是张三").assertExists()
        compose.onNodeWithText("已确认写入档案").assertExists()
        compose.onNodeWithText("确认写入档案").assertDoesNotExist()
    }

    @Test fun independentArchiveKeepsEachImportActionInManagement() {
        val file = RecordingFile("content://recording/7", "来电录音.wav", 0)
        val files = mutableStateOf(LoadState<List<RecordingFile>>())
        val actions = mutableListOf<String>()
        compose.setContent {
            ArchiveScreen(LoadState(CallArchive(-7, "31234", 0), loaded = true), files.value, MutationState(),
                {}, {}, { actions += "file" }, { files.value = LoadState(listOf(file), loaded = true) },
                { actions += it.uri }, {}, {}, {}, {}, {}, onImportOffline = { actions += "offline" })
        }
        compose.onNodeWithText("通话录音归档").assertIsDisplayed()
        assertHeading("归档管理")
        compose.onNodeWithText("导入音频文件").performScrollTo().performClick()
        compose.onNodeWithText("导入用于离线转写").performScrollTo().performClick()
        compose.onNodeWithText("选择系统录音").performScrollTo().performClick()
        compose.onNodeWithText(file.name).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("file", "offline", file.uri), actions) }
    }

    private fun assertHeading(text: String) {
        compose.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }

    @Test fun offlineActionReflectsAudioAndCurrentRun() {
        val archive = mutableStateOf(CallArchive(-7, "31234", 0, status = ArchiveStatus.READY))
        var starts = 0
        var cancelled: Long? = null
        compose.setContent { ArchiveScreen(state = LoadState(archive.value, loaded = true), files = LoadState(),
            mutation = MutationState(), onBack = {}, onRefresh = {}, onImport = {}, onList = {}, onSelect = {},
            onRetry = {}, onDelete = {}, onPlay = {}, onStop = {}, onEdit = {},
            onTranscribeOffline = { starts++ }, onCancelOffline = { cancelled = it }) }
        compose.onNodeWithText("转为文本").assertDoesNotExist()
        compose.runOnIdle { archive.value = archive.value.copy(audioPath = "/owned/fixture.wav") }
        compose.onNodeWithText("转为文本").performScrollTo().performClick()
        assertEquals(1, starts)
        compose.runOnIdle { archive.value = archive.value.copy(
            offlineTranscription = OfflineTranscriptionRun(7, OfflineTranscriptionPhase.TRANSCRIBING)) }
        compose.onNodeWithText("正在离线转写").assertExists()
        compose.onNodeWithText("取消离线转写").performScrollTo().performClick()
        assertEquals(7L, cancelled)
        compose.runOnIdle { archive.value = archive.value.copy(
            offlineTranscription = OfflineTranscriptionRun(7, OfflineTranscriptionPhase.FAILED, "recording.silent_audio")) }
        compose.onNodeWithText("重试离线转写").assertIsEnabled()
        compose.runOnIdle { archive.value = archive.value.copy(transcript = "本地全文", offlineTranscription = null) }
        compose.onNodeWithText("展开转写").performScrollTo().performClick()
        compose.onNodeWithText("本地全文").assertExists()
        compose.onNodeWithText("AI 分析").assertIsEnabled()
    }
    @Test fun localEnrichmentRetryDoesNotRequireAudioAndHasNoAdoptionAction() {
        var retries = 0
        val archive = CallArchive(-7, "31234", 0, status = ArchiveStatus.ENRICHMENT_FAILED, analysis = CallAnalysis(summary = "已整理"))
        compose.setContent { ArchiveScreen(state = LoadState(archive, loaded = true), files = LoadState(), mutation = MutationState(),
            onBack = {}, onRefresh = {}, onImport = {}, onList = {}, onSelect = {}, onRetry = { retries++ }, onDelete = {}, onPlay = {}, onStop = {}, onEdit = {}) }
        compose.onNodeWithText("重试档案补全（不调用模型）").performScrollTo().performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("采纳自述姓名和单位").assertDoesNotExist()
    }

    @Test fun archiveNavigationWorksWithStatisticsApisUnavailable() {
        val archive = CallArchive(-51, "10086", 0, status = ArchiveStatus.COMPLETE)
        val api = object : com.greatwall.callring.ui.testing.UiFakeApi() {
            override val callers: CallersApi get() = error("Archive list must not access caller statistics")
            override val reports: ReportsApi get() = error("Archive list must not access reports")
            override val recordings = object : RecordingApi by FixtureRecordingApi {
                override suspend fun queryArchives(query: ArchiveListQuery) = ApiResult.Success(archivePageForTest(archive))
            }
        }
        compose.setContent { com.greatwall.callring.ui.app.CallringApp(api,
            requestedDestination = com.greatwall.callring.ui.app.UiDestination.RecordingArchives,
            onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithText("10086").assertIsDisplayed()
        compose.onNodeWithText("已完成").assertIsDisplayed()
    }

    @Test fun independentArchiveListDisplaysStateAndOpensOpaqueId() {
        val archive = CallArchive(-42, "10086", 0, status = ArchiveStatus.COMPLETE,
            analysis = CallAnalysis(summary = "商量下次会议"))
        var selected: ArchiveListItem? = null
        var refreshes = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            RecordingArchivesScreen(LoadState(listOf(archive.listItemForTest()), loaded = true), {}, { refreshes++ }, { selected = it })
        } }
        compose.onNodeWithText("10086").assertIsDisplayed()
        compose.onNodeWithText("已完成").assertIsDisplayed()
        compose.onNodeWithText("商量下次会议").assertIsDisplayed()
        compose.onNodeWithText("10086").performClick()
        assertEquals(archive.listItemForTest(), selected)
        compose.onNodeWithText("刷新").performClick()
        assertEquals(1, refreshes)
    }

    @Test fun listOpensArchiveDetailUsingNegativeRecordingIdWithoutReports() {
        val archive = CallArchive(-91, "10086", 0, status = ArchiveStatus.COMPLETE)
        var requestedId: Long? = null
        val api = object : com.greatwall.callring.ui.testing.UiFakeApi() {
            override val reports: ReportsApi get() = error("Recording navigation must not access reports")
            override val recordings = object : RecordingApi by FixtureRecordingApi {
                override suspend fun queryArchives(query: ArchiveListQuery) = ApiResult.Success(archivePageForTest(archive))
                override suspend fun getArchive(callId: Long): ApiResult<CallArchive?> {
                    requestedId = callId
                    return ApiResult.Success(archive)
                }
            }
        }
        compose.setContent { com.greatwall.callring.ui.app.CallringApp(api,
            requestedDestination = com.greatwall.callring.ui.app.UiDestination.RecordingArchives,
            onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithText("10086").performClick()
        compose.onNodeWithText("通话录音归档").assertIsDisplayed()
        compose.waitUntil { requestedId != null }
        assertEquals(-91L, requestedId)
    }

    @Test fun independentArchiveListShowsEmptyAndErrorFeedback() {
        val state = mutableStateOf(LoadState<List<ArchiveListItem>>(emptyList(), loaded = true))
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

    @Test fun importDaysInputPreservesInvalidDraftAndExplainsRecentWindow() {
        val draft = mutableStateOf("7")
        val view = RecordingSettingsView(false, false, CaptureMode.SYSTEM_ONLY, false, 1)
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            RecordingSettingsScreen(LoadState(view, loaded = true), MutationState(), {}, {}, {}, {}, {}, {},
                draft.value, { draft.value = it })
        } }
        compose.onNodeWithText("系统录音自动导入天数").performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("请输入大于 0 的整数天数").assertExists()
        compose.onNodeWithText("已有通话记录会匹配全部历史录音，不受天数和当前号码规则限制；找不到则跳过。").assertExists()
        compose.runOnIdle { assertEquals("0", draft.value) }
        compose.onNodeWithText("系统录音自动导入天数").performTextReplacement("2147483647")
        compose.onNodeWithText("没有对应通话记录的录音按此范围导入，默认 7 天").assertExists()
    }

    @Test fun deletingArchiveRequiresExplicitConfirmation() {
        var deletes = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            ArchiveScreen(LoadState(CallArchive(1, "10086", 0), loaded = true), LoadState(), MutationState(),
                {}, {}, {}, {}, {}, {}, { deletes++ }, {}, {}, {})
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
