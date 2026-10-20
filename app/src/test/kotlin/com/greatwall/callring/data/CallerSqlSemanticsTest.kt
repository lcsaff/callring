package com.greatwall.callring.data

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
        store: SqliteBackendStore,
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
            store,
            unused(PreferenceStore::class.java),
            unused(DocumentStore::class.java),
            runtime,
            unused(AudioController::class.java),
            sync,
            Clock.fixed(Instant.ofEpochMilli(at + 60000), ZoneOffset.UTC),
            Dispatchers.Unconfined,
            coordinator = c,
            coreTransactions = store.sessions,
        )
    }

    private fun seed(store: SqliteBackendStore) {
        store.transaction { s ->
            s.profiles["31234"] = ProfileRecord("31234", "A")
            s.profiles["39999"] = ProfileRecord("39999", "B")
            val f = WorkClassifier.classify(s, at)
            s.calls[1] =
                RawCallRecord(
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
                )
            s.calls[2] =
                s.calls
                    .getValue(1)
                    .copy(id = 2, number = "39999", logId = "b2", screeningKey = null)
            s.nextCallId = 3
        }
    }

    @Test
    fun searchUsesOriginalUnicodeAndLiteralRulesWithBoundedPagesAndLongOffsets() =
        runBlocking<Unit> {
            val probe = SqlAccessProbe()
            SqliteBackendStore(context, "caller-unicode.db", probe).use { store ->
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
                store.transaction { s -> profiles.forEach { s.profiles[it.number] = it } }
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
            SqliteBackendStore(context, "caller-linked.db", probe).use { store ->
                seed(store)
                store.transaction { s ->
                    val screen = s.calls.getValue(1)
                    s.calls[1] =
                        screen.copy(
                            hidden = true,
                            decision = com.greatwall.callring.domain.model.CallDecision.UNKNOWN,
                            decisionNote = "old-unknown",
                            originalClassification =
                                screen.originalClassification.copy(working = false),
                        )
                    s.calls[3] =
                        screen.copy(
                            id = 3,
                            time = at + 1,
                            logId = "provider3",
                            screeningKey = null,
                            linkedScreenId = 1,
                            classification = screen.classification.copy(leaveVersion = 8),
                        )
                    s.calls[4] =
                        screen.copy(id = 4, time = at + 1, logId = "provider4", screeningKey = null)
                    s.calls[5] =
                        screen.copy(
                            id = 5,
                            time = at + 2,
                            logId = "hidden5",
                            screeningKey = null,
                            hidden = true,
                        )
                    s.nextCallId = 6
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
            SqliteBackendStore(context, "caller-overview.db").use { store ->
                seed(store)
                store.transaction { s ->
                    val row = s.calls.getValue(1)
                    val outside = at - 31 * 86400000L
                    s.calls[1] =
                        row.copy(
                            time = outside,
                            duration = null,
                            result = com.greatwall.callring.domain.model.CallResult.UNRESOLVED,
                            classification = row.classification.copy(calendarVerified = false),
                        )
                    s.calls[3] =
                        row.copy(id = 3, time = at - 1000, logId = "a3", screeningKey = null)
                    s.calls[4] =
                        row.copy(
                            id = 4,
                            time = at - 500,
                            logId = "a4",
                            screeningKey = null,
                            counted = false,
                            result = com.greatwall.callring.domain.model.CallResult.PENDING,
                            legacyResult = null,
                        )
                    s.calls[5] =
                        row.copy(
                            id = 5,
                            time = at + 10000,
                            logId = "a5",
                            screeningKey = null,
                            hidden = true,
                        )
                    s.nextCallId = 6
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
            SqliteBackendStore(context, "caller-delete-batches.db").use { store ->
                seed(store)
                store.transaction { s ->
                    val base = s.calls.getValue(1)
                    for (i in 1..405) {
                        val id = i + 2L
                        val n = "-".repeat(i) + "31234"
                        s.profiles[n] = ProfileRecord(n)
                        s.calls[id] =
                            base.copy(
                                id = id,
                                number = n,
                                screeningKey = null,
                                logId = "variant$id",
                                linkedScreenId = if (i == 405) 1 else null,
                            )
                    }
                    s.profiles["-31234"] = ProfileRecord("-31234", "variant profile stays")
                    s.profiles["3x1234"] = ProfileRecord("3x1234")
                    s.calls[500] =
                        base.copy(
                            id = 500,
                            number = "3x1234",
                            screeningKey = null,
                            logId = "falsepositive",
                        )
                    s.nextCallId = 501
                }
                val before = store.read { s ->
                    s.calls.filterValues { NumberIdentity.parse(it.number).normalized != "31234" }
                }
                val c = BusinessRevisionCoordinator()
                val api = backend(store, c)
                assertEquals(
                    406,
                    api.callers.numberDataImpact("3\u20031234").requiredValue().callCount,
                )
                api.callers.deleteNumberCore("31234", 406).requiredValue()
                store.read { s ->
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
                SqliteBackendStore(context, name).use { store ->
                    seed(store)
                    store.transaction { s ->
                        val base = s.calls.getValue(1)
                        s.calls[3] =
                            base.copy(
                                id = 3,
                                logId = "provider3",
                                screeningKey = null,
                                linkedScreenId = 1,
                            )
                        if (kind == "foreign")
                            s.calls[2] = s.calls.getValue(2).copy(linkedScreenId = 3)
                        s.nextCallId = 4
                    }
                    val before = store.read { it.calls.toMap() to it.profiles.toMap() }
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
                    assertEquals(before, store.read { it.calls.toMap() to it.profiles.toMap() })
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
                SqliteBackendStore(context, name).use { store ->
                    seed(store)
                    val c = BusinessRevisionCoordinator()
                    val api = backend(store, c)
                    val before = store.read { it.profiles.toMap() }
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
                    assertEquals(before, store.read { it.profiles.toMap() })
                    val escaped = store.sessions.readCaller { it }
                    assertThrows(IllegalStateException::class.java) { escaped.caller("31234") }
                    assertThrows(IllegalStateException::class.java) { escaped.visitCallerSearch {} }
                }
            }
        }

    @Test
    fun deletionPublishesCallIdentityConflictScopes() =
        runBlocking<Unit> {
            SqliteBackendStore(context, "caller-delete-scopes.db").use { store ->
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
            SqliteBackendStore(context, "caller-decision.db").use { store ->
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
            SqliteBackendStore(context, "caller-count-race.db").use { store ->
                seed(store)
                val api = backend(store)
                val count = api.callers.numberDataImpact("31234").requiredValue().callCount
                store.transaction { s ->
                    val n = "3\u00a01234".replace("\\u00a0", "\u00a0")
                    s.profiles[n] = ProfileRecord(n)
                    s.calls[3] =
                        s.calls
                            .getValue(1)
                            .copy(id = 3, number = n, screeningKey = null, logId = "added3")
                    s.nextCallId = 4
                }
                assertEquals(2, api.callers.numberDataImpact("31234").requiredValue().callCount)
                assertEquals(
                    "number.delete_conflict",
                    (api.callers.deleteNumberCore("31234", count) as ApiResult.Failure)
                        .error
                        .message,
                )
                assertEquals(3, store.read { it.calls.size })
            }
        }
}
