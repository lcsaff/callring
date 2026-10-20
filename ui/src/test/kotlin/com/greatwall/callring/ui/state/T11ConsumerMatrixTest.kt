package com.greatwall.callring.ui.state

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.feature.home.HomeViewModel
import com.greatwall.callring.ui.feature.callers.*
import com.greatwall.callring.ui.feature.report.ReportViewModel
import com.greatwall.callring.ui.feature.settings.RuntimeViewModel
import com.greatwall.callring.ui.feature.ai.AiSettingsViewModel
import com.greatwall.callring.ui.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class T11ConsumerMatrixTest {
    @Test fun aiConflictRebasesFromOneRelevantQueryAndKeepsConflictFeedback() = runTest {
        val feed = TestChangeFeed(); var reads = 0
        val connection = AiConnectionView(AiProtocol.OPENAI, "https://old.example", "model", false)
        var view = AiSettingsView(connection, connection, ConversationBudget(), 0)
        val api = object : UiFakeApi() {
            override val changes: ChangeFeed = feed
            override val aiSettings = object : AiSettingsApi by FixtureAiSettingsApi {
                override suspend fun getAiView(): ApiResult<AiSettingsView> { reads++; return ApiResult.Success(view) }
                override suspend fun getRecordingView(): ApiResult<RecordingSettingsView> = ApiResult.Success(RecordingSettingsView(false, false, CaptureMode.SYSTEM_ONLY, false, 0))
                override suspend fun patchAi(base: AiSettingsView, patch: AiSettingsPatch, analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange): ApiResult<AiSettingsView> {
                    view = view.copy(analysis = connection.copy(url = "https://external.example"), revision = 1)
                    feed.publish(setOf(DataDomain.AI_SETTINGS)); yield()
                    return ApiResult.Failure(ApiError(ErrorCode.CONFLICT, "settings.conflict"))
                }
            }
        }
        val model = AiSettingsViewModel(api, backgroundScope)
        runCurrent(); assertEquals(1, reads)
        model.thinkingMode(ThinkingMode.ENABLED); runCurrent()
        assertEquals(2, reads)
        assertEquals(view, model.state.value.data!!.ai)
        assertEquals(ErrorCode.CONFLICT, model.mutation.value.error!!.code)
    }
    @Test fun diagnosticsAndPlaybackAreIsolatedWhileCallLogUpdatesQuality() = runTest {
        val feed = TestChangeFeed()
        val counts = linkedMapOf<String, Int>()
        fun count(key: String) { counts[key] = (counts[key] ?: 0) + 1 }
        val fixture = UiFakeApi()
        val api = object : CallringUiApi by fixture {
            override val changes: ChangeFeed = feed
            override val home = object : HomeApi by fixture { override suspend fun load() = fixture.load().also { count("home") } }
            override val callers = object : CallersApi by fixture { override suspend fun search(query: CallerSearch) = fixture.search(query).also { count("caller") } }
            override val reports = object : ReportsApi by fixture { override suspend fun load(query: ReportQuery) = fixture.load(query).also { count("report") } }
            override val runtime = object : com.greatwall.callring.contracts.runtime.RuntimeApi by fixture {
                override suspend fun getStatus() = fixture.getStatus().also { count("runtime") }
            }
            override val aiSettings = object : AiSettingsApi by FixtureAiSettingsApi {
                override suspend fun getAiView(): ApiResult<AiSettingsView> {
                    count("ai")
                    val connection = AiConnectionView(AiProtocol.OPENAI, "", "", false)
                    return ApiResult.Success(AiSettingsView(connection, connection, ConversationBudget(), 0))
                }
                override suspend fun getRecordingView(): ApiResult<RecordingSettingsView> = ApiResult.Success(RecordingSettingsView(false, false, CaptureMode.SYSTEM_ONLY, false, 0))
            }
        }
        HomeViewModel(api, backgroundScope)
        CallerListViewModel(api.callers::search, api::checkNumber, feed, SavedStateHandle(), backgroundScope)
        ReportViewModel("100", api.reports, feed, SavedStateHandle(), DateRange(LocalDate.now(), LocalDate.now()), backgroundScope)
        RuntimeViewModel(api, backgroundScope)
        AiSettingsViewModel(api, backgroundScope)
        var widgets = 0
        val widget = ObservedQuery(backgroundScope, feed, ChangeSubscription(setOf(DataDomain.QUIET_HOURS, DataDomain.WORK_CALENDAR, DataDomain.APPEARANCE), mapOf(DataDomain.WORK_CALENDAR to setOf("leaves"))),
            load = { widgets++; ApiResult.Success(com.greatwall.callring.ui.widget.WidgetModel(api).load()) })
        widget.start(backgroundScope)
        runCurrent()
        assertEquals(mapOf("home" to 1, "caller" to 1, "report" to 1, "runtime" to 1, "ai" to 1), counts)
        assertEquals(1, widgets)
        feed.entity(DataDomain.RUNTIME, "diagnostics"); runCurrent()
        assertEquals(mapOf("home" to 1, "caller" to 1, "report" to 1, "runtime" to 2, "ai" to 1), counts)
        assertEquals(1, widgets)
        feed.entity(DataDomain.RUNTIME, "recordingPlayback"); runCurrent()
        assertEquals(1, counts["report"]); assertEquals(1, counts["home"]); assertEquals(2, counts["runtime"])
        feed.entity(DataDomain.RUNTIME, "ringtonePlayback"); runCurrent()
        assertEquals(1, counts["report"]); assertEquals(2, counts["home"]); assertEquals(3, counts["runtime"])
        feed.entity(DataDomain.RUNTIME, "status", "capability:CALL_LOG"); runCurrent()
        assertEquals(2, counts["report"]); assertEquals(3, counts["home"]); assertEquals(4, counts["runtime"])
        assertEquals(1, counts["caller"]); assertEquals(1, counts["ai"]); assertEquals(1, widgets)
        feed.entity(DataDomain.RUNTIME, "recordingFolder"); runCurrent()
        assertEquals(2, counts["ai"]); assertEquals(4, counts["runtime"]); assertEquals(3, counts["home"]); assertEquals(2, counts["report"])
    }

    @Test fun realCallerEditorsRetainDraftAndConflatedAThenBReachBothOnce() = runTest {
        val feed = TestChangeFeed(); val fixture = UiFakeApi()
        val counts = linkedMapOf<String, Int>()
        val callers = object : CallersApi by fixture {
            override suspend fun getEditor(number: String?): ApiResult<CallerEditorData> {
                counts[number!!] = (counts[number] ?: 0) + 1
                return fixture.getEditor(number).let { ApiResult.Success(it.value.copy(draft = it.value.draft.copy(number = number))) }
            }
        }
        val a = CallerEditorViewModel("A", callers, feed, SavedStateHandle(), backgroundScope, { _, _ -> error("unused") })
        val b = CallerEditorViewModel("B", callers, feed, SavedStateHandle(), backgroundScope, { _, _ -> error("unused") })
        runCurrent(); b.edit { copy(name = "draft B") }
        feed.entity(DataDomain.CALLERS, "A"); feed.entity(DataDomain.CALLERS, "B"); runCurrent()
        assertEquals(mapOf("A" to 2, "B" to 2), counts)
        assertEquals("draft B", b.draft.value!!.name)
        feed.entity(DataDomain.CALLERS, "A"); runCurrent()
        assertEquals(mapOf("A" to 3, "B" to 2), counts)
        repeat(1025) { feed.entity(DataDomain.CALLERS, "overflow:$it") }; runCurrent()
        assertEquals(mapOf("A" to 4, "B" to 3), counts)
        feed.publish(setOf(DataDomain.POSITIONS)); runCurrent()
        assertEquals(mapOf("A" to 5, "B" to 4), counts)
        feed.reset(); runCurrent(); assertEquals(mapOf("A" to 6, "B" to 5), counts)
        assertEquals("A", a.state.value.data!!.draft.number)
    }

    @Test fun callerParametersAndWriteDuringSlowReadPublishOnlyLatestResult() = runTest {
        val feed = TestChangeFeed(); val gate = CompletableDeferred<Unit>(); var calls = 0
        val queries = mutableListOf<String>()
        val model = CallerListViewModel({ query ->
            queries += query.text; val captured = query.text
            if (++calls == 1) withContext(NonCancellable) { gate.await() }
            ApiResult.Success(Page(listOf(CallerProfile(captured, true, captured, "", null, PositionRelation.UNKNOWN, false)), 0, 50, 1, SnapshotMeta(calls.toLong(), 0)))
        }, { NumberCheck(it, true, false) }, feed, SavedStateHandle(), backgroundScope)
        runCurrent(); model.search("A"); model.search("B"); feed.entity(DataDomain.CALLERS, "B"); runCurrent()
        assertEquals(1, calls)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("", "B"), queries)
        assertEquals("B", model.state.value.data!!.items.single().number)
    }

    @Test fun pausedObserverResumesWithCumulativeTruthAndOnlyOneInitialRead() = runTest {
        val feed = TestChangeFeed(); var loads = 0
        fun create(scope: CoroutineScope) = SnapshotViewModel(feed, setOf(DataDomain.CALLERS), { ApiResult.Success(++loads) }, scope,
            entities = mapOf(DataDomain.CALLERS to setOf("A")))
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        create(firstScope); runCurrent(); assertEquals(1, loads)
        firstScope.cancel()
        feed.entity(DataDomain.CALLERS, "A"); feed.entity(DataDomain.CALLERS, "B"); runCurrent(); assertEquals(1, loads)
        val restored = create(backgroundScope); runCurrent(); assertEquals(2, loads); assertEquals(2, restored.state.value.data)
    }
}
