package com.greatwall.callring.ui.feature.callers

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.app.CallringApp
import com.greatwall.callring.ui.testing.FixtureRecordingApi
import com.greatwall.callring.ui.testing.UiFakeApi
import org.junit.Assert.assertEquals

internal object CallHistoryScreenCases {
    fun rowActionsDoNotNavigateAndReturningFromDetailPreservesScroll(compose: ComposeContentTestRule) {
        val classification = ClassificationFact(true, WorkReason.WORKING, null, true, "UTC", 0, null, 0)
        val rows = (1L..20L).map { id ->
            val call = CallDetail(id, "300${id.toString().padStart(2, '0')}", 60_000L * (21 - id), null, null,
                CallResult.MISSED, CallDecision.NORMAL_RINGING, CallSource.SYSTEM_CALL_LOG, false,
                classification, classification, false, null)
            CallHistoryEntry(call, if (id == 1L)
                CallHistoryRecording(-71, CallArchiveAvailability.AVAILABLE, true, true)
            else CallHistoryRecording(null, CallArchiveAvailability.NONE, false, false))
        }
        var pageReads = 0
        val playbackActions = mutableListOf<Long>()
        val detailReads = mutableListOf<Long>()
        var playing: RecordingPlaybackSession? = null
        val api = object : UiFakeApi() {
            fun settingsChanged() { feed.publish(setOf(DataDomain.AI_SETTINGS)) }
            override suspend fun queryCallHistory(query: CallHistoryQuery): ApiResult<CallHistoryPage> {
                pageReads++
                return ApiResult.Success(CallHistoryPage(rows, null, SnapshotMeta(0, 0)))
            }
            override suspend fun getCallHistoryDetail(callId: Long): ApiResult<CallHistoryEntry> {
                detailReads += callId
                return ApiResult.Success(rows.single { it.call.id == callId })
            }
            override val recordings = object : RecordingApi by FixtureRecordingApi {
                override suspend fun getPlaybackSession(): ApiResult<RecordingPlaybackSession?> = ApiResult.Success(playing)
                override suspend fun playOwned(callId: Long, token: String): ApiResult<Unit> {
                    playbackActions += callId
                    playing = RecordingPlaybackSession(callId, token)
                    return ApiResult.Success(Unit)
                }
                override suspend fun stopPlaybackSession(token: String): ApiResult<Unit> {
                    if (playing?.token == token) {
                        playbackActions += 0L
                        playing = null
                    }
                    return ApiResult.Success(Unit)
                }
                override suspend fun getArchive(callId: Long): ApiResult<CallArchive?> =
                    ApiResult.Success(if (callId == -71L) CallArchive(callId, "30001", 1, transcript = "对应来电全文") else null)
            }
        }
        compose.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNodeWithText("通话记录").performClick()
        compose.onNodeWithContentDescription("播放录音").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithContentDescription("停止录音").performTouchInput { click() }
        compose.onNodeWithContentDescription("查看转写").performTouchInput { click() }
        compose.onNodeWithText("对应来电全文").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle {
            assertEquals(listOf(-71L, 0L), playbackActions)
            assertEquals(emptyList<Long>(), detailReads)
        }
        compose.runOnIdle { api.settingsChanged() }
        compose.runOnIdle { assertEquals(1, pageReads) }
        compose.onNodeWithTag("call-history-list").performScrollToKey(15L)
        compose.onNodeWithText("30015").performClick()
        compose.onNodeWithText("时长：未知").assertIsDisplayed()
        compose.onNodeWithText("号码：30015").assertIsDisplayed()
        compose.onNodeWithText("1970-01-01 00:06:00").assertIsDisplayed()
        compose.onNodeWithText("工作分类：工作时间").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(15L), detailReads) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("30015").assertIsDisplayed()
    }
}
