package com.greatwall.callring.data

import com.greatwall.callring.application.*
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],manifest=Config.NONE)
class HistorySettingsSqlTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun historicalZonesDstMidnightAndHiddenUncountedRowsUseCompleteInputsAndLeaveNoteRefreshesEveryCurrentVersion() = runBlocking<Unit> {
        val name="history-zone.db"
        SqliteBackendStore(context,name).use { store ->
            LocalAccessFixture.seed(store,1_000)
            store.transaction { s ->
                val original=s.schedules.first()
                s.schedules.clear()
                s.schedules += original.copy(zoneId="America/New_York",weekly=(1..7).associateWith { listOf(com.greatwall.callring.domain.model.TimeWindow(1320,120)) },effectiveFromEpochMillis=0)
                s.schedules += original.copy(configVersion=s.nextConfigVersion++,zoneId="Pacific/Kiritimati",weekly=emptyMap(),effectiveFromEpochMillis=Instant.parse("2026-12-31T09:00:00Z").toEpochMilli())
                val times=listOf("2026-11-01T05:30:00Z","2026-11-01T06:30:00Z","2026-03-08T07:30:00Z","2026-12-31T10:30:00Z")
                times.forEachIndexed { index,time ->
                    val old=s.calls.getValue(index+1L)
                    s.calls[old.id]=old.copy(time=Instant.parse(time).toEpochMilli(),hidden=index%2==0,counted=false)
                }
            }
            val before=store.read { it.calls.toMap() }
            val api=backend(store)
            val future=DateOverride(LocalDate.of(2099,1,1),DateOverrideKind.REST,emptyList())
            api.settings.saveDateOverride(future).requiredValue()
            store.read { state -> state.calls.forEach { (id,row) ->
                assertEquals(WorkClassifier.classify(state,row.time),row.classification)
                assertEquals(before.getValue(id),row.copy(classification=before.getValue(id).classification))
            } }
            val saved=api.settings.saveLeave(SaveLeave(period=LeavePeriod.Timed(1,2),note="first")).requiredValue()
            val first=store.read { it.leaveVersion }
            api.settings.saveLeave(SaveLeave(saved.id,LeavePeriod.Timed(1,2),"second")).requiredValue()
            store.read { state ->
                assertEquals(first+1,state.leaveVersion)
                assertTrue(state.calls.values.all { it.classification.leaveVersion==state.leaveVersion })
                assertEquals(before.mapValues { it.value.originalClassification },state.calls.mapValues { it.value.originalClassification })
            }
            assertThrows(IllegalStateException::class.java) { api.decisionSnapshot() }
        }
    }
    @Test fun failureInSecondHistoryBatchRollsBackConfigurationAuditCurrentFactsAndPublication() = runBlocking<Unit> {
        val name="history-batch-rollback.db"
        SqliteBackendStore(context,name).use { store ->
            LocalAccessFixture.seed(store,1_000)
            val before=store.read { it.calls.toMap() }
            val auditBefore = context.openOrCreateDatabase(name,0,null).use { db -> db.rawQuery("SELECT COUNT(*) FROM calendar_audit",null).use { it.moveToFirst(); it.getInt(0) } }
            val coordinator=BusinessRevisionCoordinator(); val api=backend(store,coordinator)
            context.openOrCreateDatabase(name,0,null).use { db -> db.execSQL("CREATE TRIGGER fail_second_batch BEFORE UPDATE ON calls WHEN OLD.id=300 BEGIN SELECT RAISE(ABORT,'synthetic batch failure'); END") }
            val result=api.settings.saveLeave(SaveLeave(period=LeavePeriod.Timed(1,2),note="rollback"))
            assertTrue(result is ApiResult.Failure)
            assertEquals(0L,coordinator.marker())
            store.read { s -> assertEquals(before,s.calls); assertTrue(s.leaves.isEmpty()); assertEquals(0L,s.leaveVersion) }
            context.openOrCreateDatabase(name,0,null).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM calendar_audit",null).use { assertTrue(it.moveToFirst()); assertEquals(auditBefore,it.getInt(0)) }
            }
        }
    }
    @Test fun scheduleAndLeaveUsePersistentSequenceAndEscapedHistoryCapabilitiesRejectAllCalls() {
        SqliteBackendStore(context,"history-lease.db").use { store ->
            val first=store.sessions.writeLeaves { it.putLeave(LeaveRecord(0,1,2,"a"),true) }
            store.sessions.writeLeaves { it.removeLeave(first.id) }
            val second=store.sessions.writeLeaves { it.putLeave(LeaveRecord(0,1,2,"b"),true) }
            assertTrue(second.id>first.id)
            val escaped=store.sessions.writeSession { it }
            assertThrows(IllegalStateException::class.java) { escaped.workHistory() }
            assertThrows(IllegalStateException::class.java) { escaped.calendarOverview() }
            assertThrows(IllegalStateException::class.java) { escaped.calendarYear(2026) }
            assertThrows(IllegalStateException::class.java) { escaped.latestWorkSchedule() }
            assertThrows(IllegalStateException::class.java) { escaped.leaves() }
            assertThrows(IllegalStateException::class.java) { escaped.leave(second.id) }
            assertThrows(IllegalStateException::class.java) { escaped.classificationBatch(0,256) }
            assertThrows(IllegalStateException::class.java) { escaped.incrementLeaveVersion() }
            assertThrows(IllegalStateException::class.java) { escaped.appendAudit(AuditRecord("a","b","c",0)) }
            assertThrows(IllegalStateException::class.java) { escaped.putDateOverride(com.greatwall.callring.domain.model.DateOverride(LocalDate.of(2026,1,1),com.greatwall.callring.domain.model.DateOverrideKind.REST,emptyList())) }
        }
    }
    @Test fun existingWorkingDayNameAndRawCurrentReasonAreNotReencodedWhenOnlyOtherCurrentColumnsChange() {
        val name="history-raw.db"
        SqliteBackendStore(context,name).use { store ->
            LocalAccessFixture.seed(store,1_000)
            context.openOrCreateDatabase(name,0,null).use { db ->
                db.execSQL("UPDATE calls SET reason='synthetic unknown reason' WHERE id=1")
                db.execSQL("INSERT OR REPLACE INTO calendar_years VALUES(2026,'a','2026-01-01','v')")
                db.execSQL("INSERT OR REPLACE INTO calendar_days VALUES('2026-01-02',2026,1,'preserved working name')")
            }
            store.sessions.writeSession { writer ->
                val before=writer.classificationBatch(0,1).single()
                writer.updateClassification(before,before.current.copy(leaveVersion=123))
                val year=writer.calendarYear(2026)!!
                writer.replaceCalendar(year.copy(metadata=year.metadata.copy(version="v2")))
            }
            context.openOrCreateDatabase(name,0,null).use { db ->
                db.rawQuery("SELECT reason,leave_version FROM calls WHERE id=1",null).use { assertTrue(it.moveToFirst()); assertEquals("synthetic unknown reason",it.getString(0)); assertEquals(123,it.getLong(1)) }
                db.rawQuery("SELECT name FROM calendar_days WHERE date='2026-01-02'",null).use { assertTrue(it.moveToFirst()); assertEquals("preserved working name",it.getString(0)) }
            }
        }
    }
    @Test fun ignoredAuditAndScheduleInsertsAreFailuresWithNoPublicationOrPartialChanges() = runBlocking<Unit> {
        for(table in listOf("calendar_audit","config_versions")) {
            val name="ignored-history-$table.db"
            SqliteBackendStore(context,name).use { store ->
                val coordinator=BusinessRevisionCoordinator(); val api=backend(store,coordinator)
                val before=store.read { it.schedules.toList() to it.overrides.toMap() }
                context.openOrCreateDatabase(name,0,null).use { db ->
                    db.execSQL("CREATE TRIGGER ignore_history_insert BEFORE INSERT ON $table BEGIN SELECT RAISE(IGNORE); END")
                }
                val result=if(table=="calendar_audit") api.settings.saveDateOverride(DateOverride(LocalDate.of(2026,1,1),DateOverrideKind.REST,emptyList()))
                    else api.settings.saveWorkSchedule(SaveWorkSchedule("UTC",emptyMap(),emptyList()))
                assertTrue("ignored $table insert must fail",result is ApiResult.Failure)
                assertEquals(0L,coordinator.marker())
                assertEquals(before,store.read { it.schedules.toList() to it.overrides.toMap() })
            }
        }
    }
    private fun backend(store: SqliteBackendStore, coordinator: BusinessRevisionCoordinator=BusinessRevisionCoordinator()): CallringBackend {
        val unsupported = java.lang.reflect.Proxy.newProxyInstance(DocumentStore::class.java.classLoader,arrayOf(DocumentStore::class.java)) { _,_,_ -> error("unused") } as DocumentStore
        val prefs = java.lang.reflect.Proxy.newProxyInstance(PreferenceStore::class.java.classLoader,arrayOf(PreferenceStore::class.java)) { _,_,_ -> error("unused") } as PreferenceStore
        val runtime = java.lang.reflect.Proxy.newProxyInstance(RuntimeProbe::class.java.classLoader,arrayOf(RuntimeProbe::class.java)) { _,_,_ -> error("unused") } as RuntimeProbe
        val audio = java.lang.reflect.Proxy.newProxyInstance(AudioController::class.java.classLoader,arrayOf(AudioController::class.java)) { _,_,_ -> error("unused") } as AudioController
        val sync = object : SyncScheduler { override fun requestSync(atEpochMillis: Long) = com.greatwall.callring.contracts.runtime.SyncRequestReceipt(com.greatwall.callring.contracts.runtime.SyncRequestState.QUEUED,atEpochMillis) }
        return CallringBackend(prefs,unsupported,runtime,audio,sync,Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"),ZoneOffset.UTC),Dispatchers.Unconfined,coordinator=coordinator,coreTransactions=store.sessions, numberRecordings = com.greatwall.callring.EmptyNumberRecordings, identityArchive = { com.greatwall.callring.contracts.common.ApiResult.Success(null) }, operationActive = { true }, conversationProvider = { com.greatwall.callring.testing.FixtureConversationApi }, aiSettingsProvider = { com.greatwall.callring.testing.FixtureAiSettingsApi }, updateProvider = { com.greatwall.callring.testing.FixtureAppUpdateApi }, snapshotsProvider = { com.greatwall.callring.testing.FixtureAiSnapshotApi }, recordingsProvider = { com.greatwall.callring.testing.FixtureRecordingApi }, calendarSource = com.greatwall.callring.application.ports.CalendarSource { throw AssertionError("Unexpected calendar source") }, persistenceActive = { true })
    }
}
