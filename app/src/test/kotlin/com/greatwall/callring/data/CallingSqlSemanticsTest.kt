package com.greatwall.callring.data

import com.greatwall.callring.application.*
import com.greatwall.callring.application.mapping.toContract
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
class CallingSqlSemanticsTest {
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

    private fun enabled(store: SqliteCoreStorageResource) {
        store.fixtureSql { it.seedInterval(StatisticsInterval(0, null))}
    }

    private fun calls(store: SqliteCoreStorageResource) = store.fullSnapshot.capture().let { it.calls.toMap() }

    private fun provider(log: String, number: String, time: Long, account: String? = null) =
        SystemCallRecord(
            log,
            number,
            time,
            30,
            com.greatwall.callring.domain.model.CallResult.ANSWERED,
            account,
        )

    @Test
    fun globalLogCorrectionReconcilesBothNumbersAndPreservesOriginal() {
        SqliteCoreStorageResource(context, "calling-move.db").use { store ->
            enabled(store)
            val c = BusinessRevisionCoordinator()
            val api = backend(store, c)
            val old = api.recordIncoming("31234", at, CallDecision.NORMAL_RINGING, "A")
            val next = api.recordIncoming("31235", at + 30000, CallDecision.NORMAL_RINGING, "B")
            api.importSystemCalls(listOf(provider("global", "31234", at + 1, "A")))
            val original = calls(store).values.single { it.logId == "global" }
            assertEquals(old, original.linkedScreenId)
            val before = c.marker()
            assertEquals(
                setOf("31234", "31235"),
                api.importSystemCalls(listOf(provider("global", "31235", at + 30001, "B"))),
            )
            assertEquals(before + 1, c.marker())
            val rows = calls(store)
            assertFalse(rows.getValue(old).hidden)
            assertTrue(rows.getValue(old).counted)
            assertTrue(rows.getValue(next).hidden)
            assertEquals(next, rows.getValue(original.id).linkedScreenId)
            assertEquals(
                original.originalClassification,
                rows.getValue(original.id).originalClassification,
            )
            assertEquals(original.id, rows.values.single { it.logId == "global" }.id)
        }
    }

    @Test
    fun linkSwapUnlinksEntireBatchBeforeReassignmentAndDuplicateLogsUseLastInput() {
        SqliteCoreStorageResource(context, "calling-swap.db").use { store ->
            enabled(store)
            val api = backend(store)
            val a = api.recordIncoming("31234", at, CallDecision.NORMAL_RINGING, "A")
            val b = api.recordIncoming("31234", at, CallDecision.NORMAL_RINGING, "B")
            api.importSystemCalls(
                listOf(provider("a", "31234", at, "A"), provider("b", "31234", at, "B")),
            )
            api.importSystemCalls(
                listOf(provider("a", "31234", at, "B"), provider("b", "31234", at, "A")),
            )
            val rows = calls(store).values
            assertEquals(b, rows.single { it.logId == "a" }.linkedScreenId)
            assertEquals(a, rows.single { it.logId == "b" }.linkedScreenId)
            api.importSystemCalls(
                listOf(provider("a", "31234", at + 100000, "A"), provider("a", "31234", at, "B")),
            )
            assertEquals(b, calls(store).values.single { it.logId == "a" }.linkedScreenId)
        }
    }

    @Test
    fun historicalStatisticsAndValidationOrderAndSessionDedupArePreserved() {
        SqliteCoreStorageResource(context, "calling-boundaries.db").use { store ->
            store.fixtureSql { it.seedInterval(StatisticsInterval(at, at + 100))}
            val c = BusinessRevisionCoordinator()
            val api = backend(store, c)
            assertEquals(-1L, api.recordIncoming("31234", at - 1, CallDecision.NORMAL_RINGING))
            assertEquals(-1L, api.recordIncoming("31234", at + 100, CallDecision.NORMAL_RINGING))
            assertEquals(-1L, api.recordIncoming("invalid", at, CallDecision.NORMAL_RINGING))
            assertTrue(
                api.importSystemCalls(
                        listOf(provider("", "invalid", at).copy(durationSeconds = -1)),
                    )
                    .isEmpty(),
            )
            assertTrue(
                api.importSystemCalls(
                        listOf(provider("", "31234", at + 100).copy(durationSeconds = -1)),
                    )
                    .isEmpty(),
            )
            assertEquals(0L, c.marker())
            val id = api.recordIncoming("(31234)", at, CallDecision.NORMAL_RINGING, " A ")
            val original = calls(store).getValue(id)
            assertEquals("31234:$at:A", original.screeningKey)
            val marker = c.marker()
            assertEquals(
                id,
                api.recordIncoming(
                    "31234",
                    at,
                    CallDecision.QUIET_SILENCED,
                    "A",
                    original.originalClassification
                        .copy(working = !original.originalClassification.working)
                        .let { it.toContract() },
                ),
            )
            assertEquals(marker, c.marker())
            assertEquals(original, calls(store).getValue(id))
            api.updateDecision(0, CallDecision.QUIET_SILENCED)
            api.updateDecision(Long.MAX_VALUE, CallDecision.QUIET_SILENCED)
            api.updateDecision(id, CallDecision.NORMAL_RINGING)
            assertEquals(marker, c.marker())
            assertEquals(at, api.statisticsStart())
        }
    }

    @Test
    fun failedBatchRollsBackInsertedProfilesCallsAndRevision() {
        SqliteCoreStorageResource(context, "calling-rollback.db").use { store ->
            enabled(store)
            val c = BusinessRevisionCoordinator()
            val api = backend(store, c)
            val before = calls(store)
            try {
                api.importSystemCalls(
                    listOf(
                        provider("one", "31234", at),
                        provider("bad", "31235", at).copy(durationSeconds = -1),
                    ),
                )
                fail("invalid second input")
            } catch (_: BackendFailure) {}
            assertEquals(before, calls(store))
            assertEquals(0L, c.marker())
            assertTrue(store.fullSnapshot.capture().let { it.profiles.isEmpty() })
        }
    }

    @Test
    fun pendingUsesVisibilityAndCountWithoutResultPredicateAndSaturatesBounds() {
        SqliteCoreStorageResource(context, "calling-pending.db").use { store ->
            enabled(store)
            val api = backend(store)
            val id = api.recordIncoming("31234", at, CallDecision.NORMAL_RINGING)
            store.fixtureSql { s ->
                s.seedCall(s.callFact(id)!!
                        .copy(result = com.greatwall.callring.domain.model.CallResult.ANSWERED))
            }
            assertTrue(api.hasRecentPending(at))
            assertTrue(api.hasRecentPending(at + 300000))
            assertFalse(api.hasRecentPending(at + 300001))
            store.fixtureSql { s ->
                s.seedCall(s.callFact(id)!!.copy(time = Long.MAX_VALUE))
            }
            assertFalse(api.hasRecentPending(Long.MAX_VALUE))
            store.fixtureSql { s ->
                s.seedCall(s.callFact(id)!!.copy(time = Long.MIN_VALUE))
            }
            assertTrue(api.hasRecentPending(Long.MIN_VALUE))
        }
    }

    @Test
    fun decisionAndCorrelationWritesNeverReplaceWarmDecisionSnapshot() = runBlocking {
        SqliteCoreStorageResource(context, "calling-warm.db").use { store ->
            enabled(store)
            val api = backend(store)
            val snapshot = api.refreshDecisionSnapshot()
            val id = api.recordIncoming("31234", at, CallDecision.NORMAL_RINGING)
            api.updateDecision(id, CallDecision.QUIET_SILENCED)
            api.importSystemCalls(listOf(provider("x", "31234", at)))
            assertSame(snapshot, api.decisionSnapshot())
            api.callers.setWhitelisted("31234", true)
            assertTrue(api.decisionSnapshot().evaluate("31234", at).whitelisted)
        }
    }

    private fun sql(name: String, block: (android.database.sqlite.SQLiteDatabase) -> Unit) {
        android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            )
            .use(block)
    }

    @Test
    fun ignoredInsertAndUpdateDoNotPublishOrLeavePartialFacts() {
        val name = "calling-ignore.db"
        val probe = SqlAccessProbe()
        SqliteCoreStorageResource(context, name, probe).use { store ->
            enabled(store)
            val c = BusinessRevisionCoordinator()
            val api = backend(store, c)
            sql(name) {
                it.execSQL(
                    "CREATE TRIGGER ignore_call BEFORE INSERT ON calls BEGIN SELECT RAISE(IGNORE); END",
                )
            }
            try {
                api.recordIncoming("31234", at, CallDecision.NORMAL_RINGING)
                fail("ignored insert")
            } catch (_: IllegalStateException) {}
            assertEquals(0L, c.marker())
            assertEquals(
                0L,
                probe
                    .snapshot()
                    .filter {
                        it.tag == QueryTag.DIRECT_INSERT && it.tables == setOf(SqlTable.CALLS)
                    }
                    .sumOf { it.writtenRows },
            )
            assertTrue(calls(store).isEmpty())
            assertTrue(store.fullSnapshot.capture().let { it.profiles.isEmpty() })
            sql(name) { it.execSQL("DROP TRIGGER ignore_call") }
            val id = api.recordIncoming("31234", at, CallDecision.NORMAL_RINGING)
            val marker = c.marker()
            val before = calls(store)
            sql(name) {
                it.execSQL(
                    "CREATE TRIGGER ignore_update BEFORE UPDATE ON calls BEGIN SELECT RAISE(IGNORE); END",
                )
            }
            try {
                api.updateDecision(id, CallDecision.QUIET_SILENCED)
                fail("ignored update")
            } catch (_: IllegalStateException) {}
            assertEquals(marker, c.marker())
            assertEquals(before, calls(store))
        }
    }

    @Test
    fun sequenceNeverReusesDeletedHighestIdAndRawColumnsSurviveTargetedDecisionWrite() {
        val name = "calling-raw.db"
        SqliteCoreStorageResource(context, name).use { store ->
            enabled(store)
            val api = backend(store)
            val first = api.recordIncoming("31234", at, CallDecision.NORMAL_RINGING, "kept")
            store.fixtureSql { check(it.delete("calls", "id=?", args(first)) == 1) }
            val next = api.recordIncoming("31234", at + 1, CallDecision.NORMAL_RINGING, "kept")
            assertTrue(next > first)
            sql(name) { db ->
                db.execSQL(
                    "UPDATE calls SET result='unknown-result',source='unknown-source',reason='unknown-reason',original_reason='unknown-original',calendar_version='unparsed-calendar' WHERE id=?",
                    arrayOf(next),
                )
            }
            fun raw() =
                android.database.sqlite.SQLiteDatabase.openDatabase(
                        context.getDatabasePath(name).absolutePath,
                        null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                    )
                    .use { db ->
                        db.rawQuery("SELECT * FROM calls WHERE id=?", arrayOf(next.toString()))
                            .use { c ->
                                check(c.moveToFirst())
                                c.columnNames
                                    .filter { it != "decision" }
                                    .associateWith { c.getString(c.getColumnIndexOrThrow(it)) }
                            }
                    }
            val untouched = raw()
            api.updateDecision(next, CallDecision.QUIET_SILENCED)
            assertEquals(untouched, raw())
            assertEquals("kept", calls(store).getValue(next).account)
        }
    }

    @Test
    fun ignoredUnitInsertRollsBackEarlierScoreWriteAndDoesNotPublish() = runBlocking {
        val name = "calling-unit-ignore.db"
        val probe = SqlAccessProbe()
        SqliteCoreStorageResource(context, name, probe).use { store ->
            sql(name) { db ->
                db.execSQL("DELETE FROM settings WHERE key IN ('off_weight','repeat_weight')")
                db.execSQL(
                    "CREATE TRIGGER ignore_unit_insert BEFORE INSERT ON settings WHEN NEW.key='repeat_weight' BEGIN SELECT RAISE(IGNORE); END",
                )
            }
            val c = BusinessRevisionCoordinator()
            val api = backend(store, c)
            val baseline = api.settings.getScoreSettings()
            val result =
                api.settings.saveScoreSettings(
                    com.greatwall.callring.contracts.settings.ScoreSettings(6, 3, 10, 30),
                )
            assertTrue(result is ApiResult.Failure)
            assertEquals(0L, c.marker())
            assertEquals(baseline, api.settings.getScoreSettings())
            assertEquals(
                1L,
                probe
                    .snapshot()
                    .filter {
                        it.tag == QueryTag.DIRECT_INSERT && it.tables == setOf(SqlTable.SETTINGS)
                    }
                    .sumOf { it.writtenRows },
            )
        }
    }
}
