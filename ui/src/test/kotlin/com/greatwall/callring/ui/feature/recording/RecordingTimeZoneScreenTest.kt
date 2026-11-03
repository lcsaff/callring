package com.greatwall.callring.ui.feature.recording

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.app.ArchiveRoute
import com.greatwall.callring.ui.app.RecordingArchivesRoute
import com.greatwall.callring.ui.testing.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingTimeZoneScreenTest {
    @get:Rule val compose = createComposeRule()

    private class Api(private var zone: String) : UiFakeApi() {
        var archiveReads = 0
        var listReads = 0
        var summaryReads = 0
        var summaryGate: CompletableDeferred<Unit>? = null
        var summaryError: ApiError? = null
        private val fixture = UiFakeApi()
        override val settings = object : SettingsApi by fixture {
            override suspend fun getSummary(): ApiResult<SettingsSummary> {
                summaryReads++
                summaryGate?.await()
                return summaryError?.let { ApiResult.Failure(it) }
                    ?: ApiResult.Success(fixture.getSummary().value.copy(workZoneId = zone))
            }
        }
        val archive = CallArchive(-1, "10086", Instant.parse("2026-03-08T07:30:00Z").toEpochMilli())
        override val recordings = object : RecordingApi by FixtureRecordingApi {
            override suspend fun queryArchives(query: ArchiveListQuery): ApiResult<ArchiveListPage> {
                listReads++
                return ApiResult.Success(archivePageForTest(archive))
            }
            override suspend fun getArchive(callId: Long): ApiResult<CallArchive?> {
                archiveReads++
                return ApiResult.Success(archive)
            }
        }
        fun zone(id: String) { zone = id; feed.entity(DataDomain.WORK_CALENDAR, "schedule") }
        fun unrelatedChange() { feed.entity(DataDomain.WORK_CALENDAR, "calendar:2026") }
    }

    @Test fun listUsesConfiguredZoneAcrossDateBoundaryInsteadOfDeviceZone() = withDeviceZone("UTC") {
        val api = Api("America/Los_Angeles")
        compose.setContent { RecordingArchivesRoute(api, 0, {}, {}) }
        compose.onNodeWithText("2026-03-07 23:30").assertIsDisplayed()
    }

    @Test fun detailUsesConfiguredDaylightSavingOffsetInsteadOfDeviceZone() = withDeviceZone("Asia/Shanghai") {
        val api = Api("America/New_York")
        compose.setContent { ArchiveRoute(api, -1, "10086", api.archive.startedAtEpochMillis, 0, {}, {}) }
        compose.onNodeWithText("2026-03-08 03:30").assertIsDisplayed()
    }

    @Test fun missingConfigurationShowsLoadingThenErrorAndCanRetry() = withDeviceZone("UTC") {
        val api = Api("America/New_York")
        val gate = CompletableDeferred<Unit>()
        api.summaryGate = gate
        compose.setContent { RecordingArchivesRoute(api, 0, {}, {}) }
        compose.onNodeWithText("正在加载显示时区…").assertIsDisplayed()
        compose.onNodeWithText("2026-03-08 07:30").assertDoesNotExist()
        compose.runOnIdle {
            api.summaryError = ApiError(ErrorCode.STORAGE_FAILURE, "timezone.unavailable", retryable = true)
            gate.complete(Unit)
        }
        compose.onNodeWithText("显示时区加载失败，请重试").assertIsDisplayed()
        compose.runOnIdle { api.summaryError = null }
        compose.onNodeWithText("重试显示时区").performClick()
        compose.onNodeWithText("2026-03-08 03:30").assertIsDisplayed()
    }

    @Test fun configurationUpdateReformatsDetailWithoutReloadingTranscript() = withDeviceZone("UTC") {
        val api = Api("America/New_York")
        compose.setContent { ArchiveRoute(api, -1, "10086", api.archive.startedAtEpochMillis, 0, {}, {}) }
        compose.onNodeWithText("2026-03-08 03:30").assertIsDisplayed()
        val archiveReads = api.archiveReads
        val summaryReads = api.summaryReads
        compose.runOnIdle { api.unrelatedChange() }
        compose.runOnIdle { assertEquals(summaryReads, api.summaryReads) }
        compose.runOnIdle { api.zone("Asia/Shanghai") }
        compose.onNodeWithText("2026-03-08 15:30").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(archiveReads, api.archiveReads)
            assertEquals(summaryReads + 1, api.summaryReads)
        }
    }

    @Test fun configurationUpdateReformatsListWithoutReloadingArchiveData() = withDeviceZone("UTC") {
        val api = Api("America/New_York")
        compose.setContent { RecordingArchivesRoute(api, 0, {}, {}) }
        compose.onNodeWithText("2026-03-08 03:30").assertIsDisplayed()
        val reads = api.listReads
        compose.runOnIdle { api.zone("Asia/Shanghai") }
        compose.onNodeWithText("2026-03-08 15:30").assertIsDisplayed()
        compose.runOnIdle {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            api.unrelatedChange()
        }
        compose.onNodeWithText("2026-03-08 15:30").assertIsDisplayed()
        compose.runOnIdle { assertEquals(reads, api.listReads) }
    }

    private fun withDeviceZone(id: String, block: () -> Unit) {
        val original = TimeZone.getDefault()
        try { TimeZone.setDefault(TimeZone.getTimeZone(id)); block() }
        finally { TimeZone.setDefault(original) }
    }
}
