package com.greatwall.callring.data

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.data.database.SqlAccessProbe
import com.greatwall.callring.data.database.SqlTable
import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.data.database.text
import com.greatwall.callring.domain.calling.WorkClassifier
import com.greatwall.callring.domain.model.ProfileRecord
import com.greatwall.callring.domain.model.RawCallRecord

import com.greatwall.callring.application.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.NumberIdentity
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CallerSqlSemanticsTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private val at = LocalAccessFixture.at

    private fun backend(
        store: SqliteCoreStorageResource,
        c: BusinessRevisionCoordinator = BusinessRevisionCoordinator(),
    ): CallringBackend {
        fun <T : Any> unused(type: Class<T>): T =
            requireNotNull(
                type.cast(
                    java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) {
                        _,
                        _,
                        _ ->
                        error("unexpected platform")
                    },
                ),
            )
        val runtime =
            object : RuntimeProbe {
            override fun getInstalledWidgets() = com.greatwall.callring.contracts.widget.WidgetInstallState(emptyList(), AccessState.GRANTED)
                override fun getStatus() =
                    RuntimeStatus(
                        mapOf(Capability.CALL_LOG to AccessState.GRANTED),
                        Audibility.AUDIBLE,
                        PhoneActivity.IDLE,
                        PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE),
                        false,
                        at,
                    )

                override fun getDiagnostics() = emptyList<DiagnosticEntry>()
            }
        val sync =
            object : SyncScheduler {
                override fun requestSync(atEpochMillis: Long) =
                    SyncRequestReceipt(SyncRequestState.QUEUED, atEpochMillis)
            }
        return CallringBackend(
            unused(PreferenceStore::class.java),
            unused(DocumentStore::class.java),
            runtime,
            unused(AudioController::class.java),
            sync,
            Clock.fixed(Instant.ofEpochMilli(at + 60000), ZoneOffset.UTC),
            Dispatchers.Unconfined,
            coordinator = c,
            coreTransactions = store.sessions, numberRecordings = com.greatwall.callring.EmptyNumberRecordings, identityArchive = { com.greatwall.callring.contracts.common.ApiResult.Success(null) }, operationActive = { true }, conversationProvider = { com.greatwall.callring.testing.FixtureConversationApi }, aiSettingsProvider = { com.greatwall.callring.testing.FixtureAiSettingsApi }, updateProvider = { com.greatwall.callring.testing.FixtureAppUpdateApi }, snapshotsProvider = { com.greatwall.callring.testing.FixtureAiSnapshotApi }, recordingsProvider = { com.greatwall.callring.testing.FixtureRecordingApi }, calendarSource = com.greatwall.callring.application.ports.CalendarSource { throw AssertionError("Unexpected calendar source") }, persistenceActive = { true })
    }

    private fun seed(store: SqliteCoreStorageResource) {
        store.fixtureSql { s ->
            s.seedCaller(ProfileRecord("31234", "A"))
            s.seedCaller(ProfileRecord("39999", "B"))
            val f = WorkClassifier.classify(s.fixtureWork(), at)
            s.seedCall(RawCallRecord(
                    1,
                    "31234",
                    at,
                    30,
                    com.greatwall.callring.domain.model.CallResult.ANSWERED,
                    com.greatwall.callring.domain.model.CallDecision.NORMAL_RINGING,
                    com.greatwall.callring.domain.model.CallSource.SCREENING,
                    null,
                    "s1",
                    true,
                    f,
                    f,
                ))
            s.seedCall(s.callFact(1)!!
                    .copy(id = 2, number = "39999", logId = "b2", screeningKey = null))
            s.seedSequence("calls", 3)
        }
    }

    @Test
    fun searchUsesOriginalUnicodeAndLiteralRulesWithBoundedPagesAndLongOffsets() =
        runBlocking<Unit> {
            val probe = SqlAccessProbe()
            SqliteCoreStorageResource(context, "caller-unicode.db", probe).use { store ->
                val labels =
                    listOf(
                        "İıiI",
                        "Σσς",
                        "KkK",
                        "ßẞ",
                        "\uD801\uDC00\uD801\uDC28",
                        "literal %_*?[]",
                        " e\u0301 é ",
                        "before\u0000after",
                    )
                val profiles =
                    (0 until 600).map { index ->
                        ProfileRecord(
                            (30000 + index).toString(),
                            labels[index % labels.size],
                            labels[(index + 1) % labels.size],
                        )
                    } +
                        listOf(
                            ProfileRecord("(3-1234)", "historical"),
                            ProfileRecord("+31234", "historical"),
                            ProfileRecord("3 1234", "historical"),
                        )
                store.fixtureSql { s -> profiles.forEach { s.seedCaller(it)} }
                val api = backend(store)
                val callReadsBefore =
                    probe.snapshot().filter { SqlTable.CALLS in it.tables }.sumOf { it.calls }
                val queries =
                    listOf(
                        "",
                        "  ",
                        "i",
                        "İ",
                        "ı",
                        "σ",
                        "Σ",
                        "ς",
                        "k",
                        "K",
                        "ß",
                        "ẞ",
                        "\uD801\uDC28",
                        "%",
                        "_",
                        "*",
                        "?",
                        "[",
                        "]",
                        " e\u0301 ",
                        "\u0000",
                        "historical",
                        "missing",
                    )
                for (text in queries) for (page in listOf(0, 1, Int.MAX_VALUE)) {
                    val expected =
                        profiles
                            .filter {
                                it.number.contains(text.trim(), true) ||
                                    it.name.contains(text.trim(), true) ||
                                    it.department.contains(text.trim(), true)
                            }
                            .sortedBy { it.number }
                    val actual =
                        api.callers.search(CallerSearch(text, PageRequest(page, 7))).requiredValue()
                    assertEquals("total query label", expected.size.toLong(), actual.totalItems)
                    val offset = page.toLong() * 7
                    assertEquals(
                        if (offset >= expected.size) emptyList<String>()
                        else expected.drop(offset.toInt()).take(7).map { it.number },
                        actual.items.map { it.number },
                    )
                    assertEquals(page, actual.pageIndex)
                }
                assertEquals(
                    callReadsBefore,
                    probe.snapshot().filter { SqlTable.CALLS in it.tables }.sumOf { it.calls },
                )
                assertTrue(
                    api.callers.search(CallerSearch(page = PageRequest(-1))) is ApiResult.Failure,
                )
            }
        }

    @Test
    fun detailAndLatestUseLinkedScreenClosureAndStableVisibleOrder() =
        runBlocking<Unit> {
            val probe = SqlAccessProbe()
            SqliteCoreStorageResource(context, "caller-linked.db", probe).use { store ->
                seed(store)
                store.fixtureSql { s ->
                    val screen = s.callFact(1)!!
                    s.seedCall(screen.copy(
                            hidden = true,
                            decision = com.greatwall.callring.domain.model.CallDecision.UNKNOWN,
                            decisionNote = "old-unknown",
                            originalClassification =
                                screen.originalClassification.copy(working = false),
                        ))
                    s.seedCall(screen.copy(
                            id = 3,
                            time = at + 1,
                            logId = "provider3",
                            screeningKey = null,
                            linkedScreenId = 1,
                            classification = screen.classification.copy(leaveVersion = 8),
                        ))
                    s.seedCall(screen.copy(id = 4, time = at + 1, logId = "provider4", screeningKey = null))
                    s.seedCall(screen.copy(
                            id = 5,
                            time = at + 2,
                            logId = "hidden5",
                            screeningKey = null,
                            hidden = true,
                        ))
                    s.seedSequence("calls", 6)
                }
                val api = backend(store)
                val before =
                    probe
                        .snapshot()
                        .filter { SqlTable.CALLS in it.tables }
                        .sumOf { it.consumedRows }
                val detail = api.callers.getCallDetail(3).requiredValue()
                assertEquals(CallSource.MERGED, detail.source)
                assertEquals(at, detail.screeningAtEpochMillis)
                assertEquals(CallDecision.UNKNOWN, detail.decision)
                assertEquals("old-unknown", detail.decisionNote)
                assertFalse(detail.originalClassification.working)
                assertEquals(8L, detail.classification.leaveVersion)
                assertEquals(4L, api.callers.getLatestCall().requiredValue()!!.id)
                assertEquals(
                    3L,
                    probe
                        .snapshot()
                        .filter { SqlTable.CALLS in it.tables }
                        .sumOf { it.consumedRows } - before,
                )
            }
        }

    @Test
    fun overviewCountsAndQualityUseAllVisibleAFactsButScoresOnlyTheRange() =
        runBlocking<Unit> {
            SqliteCoreStorageResource(context, "caller-overview.db").use { store ->
                seed(store)
                store.fixtureSql { s ->
                    val row = s.callFact(1)!!
                    val outside = at - 31 * 86400000L
                    s.seedCall(row.copy(
                            time = outside,
                            duration = null,
                            result = com.greatwall.callring.domain.model.CallResult.UNRESOLVED,
                            classification = row.classification.copy(calendarVerified = false),
                        ))
                    s.seedCall(row.copy(id = 3, time = at - 1000, logId = "a3", screeningKey = null))
                    s.seedCall(row.copy(
                            id = 4,
                            time = at - 500,
                            logId = "a4",
                            screeningKey = null,
                            counted = false,
                            result = com.greatwall.callring.domain.model.CallResult.PENDING,
                            legacyResult = null,
                        ))
                    s.seedCall(row.copy(
                            id = 5,
                            time = at + 10000,
                            logId = "a5",
                            screeningKey = null,
                            hidden = true,
                        ))
                    s.seedSequence("calls", 6)
                }
                val api = backend(store)
                val result = api.callers.getOverview(OverviewQuery("31234", at)).requiredValue()
                assertEquals(2L, result.lifetimeCallCount)
                assertEquals(4L, result.latestCall!!.id)
                assertEquals(1L, result.rollingScore.totalCalls)
                assertTrue("duration", result.quality.hasUnknownDuration)
                assertTrue("pending", result.quality.hasPendingResults)
                assertTrue("unresolved", result.quality.hasUnresolvedRecords)
                assertTrue("verified", result.quality.hasUnverifiedCalendar)
            }
        }

    @Test
    fun deletionUnlinksEveryVariantBeforeAnyBatchDeleteAndPreservesBAndVariantProfile() =
        runBlocking<Unit> {
            SqliteCoreStorageResource(context, "caller-delete-batches.db").use { store ->
                seed(store)
                store.fixtureSql { s ->
                    val base = s.callFact(1)!!
                    for (i in 1..405) {
                        val id = i + 2L
                        val n = "-".repeat(i) + "31234"
                        s.seedCaller(ProfileRecord(n))
                        s.seedCall(base.copy(
                                id = id,
                                number = n,
                                screeningKey = null,
                                logId = "variant$id",
                                linkedScreenId = if (i == 405) 1 else null,
                            ))
                    }
                    s.seedCaller(ProfileRecord("-31234", "variant profile stays"))
                    s.seedCaller(ProfileRecord("3x1234"))
                    s.seedCall(base.copy(
                            id = 500,
                            number = "3x1234",
                            screeningKey = null,
                            logId = "falsepositive",
                        ))
                    s.seedSequence("calls", 501)
                }
                val before = store.fullSnapshot.capture().let { s ->
                    s.calls.filterValues { NumberIdentity.parse(it.number).normalized != "31234" }
                }
                val c = BusinessRevisionCoordinator()
                val api = backend(store, c)
                assertEquals(
                    406,
                    api.callers.numberDataImpact("3\u20031234").requiredValue().callCount,
                )
                api.callers.deleteNumberCore("31234", 406).requiredValue()
                store.fullSnapshot.capture().let { s ->
                    assertEquals(before, s.calls)
                    assertFalse(s.profiles.containsKey("31234"))
                    assertEquals("variant profile stays", s.profiles["-31234"]!!.name)
                }
                assertEquals(1L, c.marker())
                assertEquals(
                    "number.not_found",
                    (api.callers.deleteNumberCore("31234", 0) as ApiResult.Failure).error.message,
                )
            }
        }

    @Test
    fun deletionRechecksCountAndExternalFkAndPartialFailureRollBackAllChanges() =
        runBlocking<Unit> {
            for (kind in listOf("foreign", "unlink", "delete")) {
                val name = "caller-rollback-$kind.db"
                SqliteCoreStorageResource(context, name).use { store ->
                    seed(store)
                    store.fixtureSql { s ->
                        val base = s.callFact(1)!!
                        s.seedCall(base.copy(
                                id = 3,
                                logId = "provider3",
                                screeningKey = null,
                                linkedScreenId = 1,
                            ))
                        if (kind == "foreign")
                            s.seedCall(s.callFact(2)!!.copy(linkedScreenId = 3))
                        s.seedSequence("calls", 4)
                    }
                    val before = store.fullSnapshot.capture().let { it.calls.toMap() to it.profiles.toMap() }
                    if (kind != "foreign")
                        context.openOrCreateDatabase(name, 0, null).use { db ->
                            db.execSQL(
                                if (kind == "unlink")
                                    "CREATE TRIGGER ignore_unlink BEFORE UPDATE ON calls WHEN OLD.id=3 BEGIN SELECT RAISE(IGNORE); END"
                                else
                                    "CREATE TRIGGER ignore_delete BEFORE DELETE ON calls WHEN OLD.id=1 BEGIN SELECT RAISE(IGNORE); END",
                            )
                        }
                    val c = BusinessRevisionCoordinator()
                    val api = backend(store, c)
                    assertTrue(kind, api.callers.deleteNumberCore("31234", 2) is ApiResult.Failure)
                    assertEquals(0L, c.marker())
                    assertEquals(before, store.fullSnapshot.capture().let { it.calls.toMap() to it.profiles.toMap() })
                    assertEquals(
                        "number.delete_conflict",
                        (api.callers.deleteNumberCore("31234", 1) as ApiResult.Failure)
                            .error
                            .message,
                    )
                }
            }
        }

    @Test
    fun ignoredProfileInsertUpdateAndDeleteFailAndLeaseCannotEscape() =
        runBlocking<Unit> {
            for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                val name = "caller-ignore-$operation.db"
                SqliteCoreStorageResource(context, name).use { store ->
                    seed(store)
                    val c = BusinessRevisionCoordinator()
                    val api = backend(store, c)
                    val before = store.fullSnapshot.capture().let { it.profiles.toMap() }
                    context.openOrCreateDatabase(name, 0, null).use {
                        it.execSQL(
                            "CREATE TRIGGER ignore_caller BEFORE $operation ON callers BEGIN SELECT RAISE(IGNORE); END",
                        )
                    }
                    val result =
                        when (operation) {
                            "INSERT" ->
                                api.callers.saveProfile(SaveCallerProfile("33333", "new", "", null))
                            "UPDATE" -> api.callers.setWhitelisted("31234", true)
                            else -> api.callers.deleteNumberCore("31234", 1)
                        }
                    assertTrue(operation, result is ApiResult.Failure)
                    assertEquals(0L, c.marker())
                    assertEquals(before, store.fullSnapshot.capture().let { it.profiles.toMap() })
                    val escaped = store.sessions.readCaller { it }
                    assertThrows(IllegalStateException::class.java) { escaped.caller("31234") }
                    assertThrows(IllegalStateException::class.java) { escaped.visitCallerSearch {} }
                }
            }
        }

    @Test
    fun deletionPublishesCallIdentityConflictScopes() =
        runBlocking<Unit> {
            SqliteCoreStorageResource(context, "caller-delete-scopes.db").use { store ->
                seed(store)
                val c = BusinessRevisionCoordinator()
                val api = backend(store, c)
                api.callers.deleteNumberCore("31234", 1).requiredValue()
                assertFalse(
                    "deleted call identity must invalidate its command scope",
                    c.unchanged(setOf("call:1"), 0),
                )
                assertTrue(c.unchanged(setOf("call:2"), 0))
            }
        }

    @Test
    fun whitelistPatchesLatestWarmInputsAndColdRemainsCold() =
        runBlocking<Unit> {
            SqliteCoreStorageResource(context, "caller-decision.db").use { store ->
                seed(store)
                val api = backend(store)
                api.callers
                    .saveProfile(SaveCallerProfile("31234", "name", "", null))
                    .requiredValue()
                assertThrows(IllegalStateException::class.java) { api.decisionSnapshot() }
                val warm = api.refreshDecisionSnapshot()
                api.callers
                    .saveProfile(SaveCallerProfile("31234", "name2", "department", null))
                    .requiredValue()
                assertSame(warm, api.decisionSnapshot())
                api.settings
                    .setQuietMode(com.greatwall.callring.contracts.settings.QuietMode.NON_WORK)
                    .requiredValue()
                val latest = api.decisionSnapshot()
                api.callers.setWhitelisted("31234", true).requiredValue()
                assertEquals(
                    latest.inputs.copy(whitelistedNumbers = setOf("31234")),
                    api.decisionSnapshot().inputs,
                )
                val white = api.decisionSnapshot()
                api.callers.setWhitelisted("31234", true).requiredValue()
                assertSame(white, api.decisionSnapshot())
                api.callers.deleteNumberCore("31234", 1).requiredValue()
                assertEquals(latest.inputs, api.decisionSnapshot().inputs)
            }
        }

    @Test
    fun impactIsRecheckedInsideDeleteAfterANewFormattedVariantAppears() =
        runBlocking<Unit> {
            SqliteCoreStorageResource(context, "caller-count-race.db").use { store ->
                seed(store)
                val api = backend(store)
                val count = api.callers.numberDataImpact("31234").requiredValue().callCount
                store.fixtureSql { s ->
                    val n = "3\u00a01234".replace("\\u00a0", "\u00a0")
                    s.seedCaller(ProfileRecord(n))
                    s.seedCall(s.callFact(1)!!
                            .copy(id = 3, number = n, screeningKey = null, logId = "added3"))
                    s.seedSequence("calls", 4)
                }
                assertEquals(2, api.callers.numberDataImpact("31234").requiredValue().callCount)
                assertEquals(
                    "number.delete_conflict",
                    (api.callers.deleteNumberCore("31234", count) as ApiResult.Failure)
                        .error
                        .message,
                )
                assertEquals(3, store.fullSnapshot.capture().let { it.calls.size })
            }
        }
}
