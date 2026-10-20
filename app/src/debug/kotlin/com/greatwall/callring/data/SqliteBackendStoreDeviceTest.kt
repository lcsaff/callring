package com.greatwall.callring.data

import com.greatwall.callring.application.mapping.*
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.domain.AuditRecord
import com.greatwall.callring.domain.LeaveRecord
import com.greatwall.callring.domain.PositionRecord
import com.greatwall.callring.domain.ProfileRecord
import com.greatwall.callring.domain.RawCallRecord
import com.greatwall.callring.domain.WorkClassifier
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Disposable databases only. This test never installs itself or touches the production database.
 */
class SqliteBackendStoreDeviceTest : InstrumentationTestCase() {
    private lateinit var store: SqliteBackendStore
    private lateinit var database: String

    override fun setUp() {
        super.setUp()
        database = "qa-backend-${UUID.randomUUID()}.db"
        store = SqliteBackendStore(instrumentation.targetContext, database)
    }

    override fun tearDown() {
        store.close()
        instrumentation.targetContext.deleteDatabase(database)
        super.tearDown()
    }

    private fun fixture() =
        SQLiteDatabase.openDatabase(
            instrumentation.targetContext.getDatabasePath(database).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )

    fun testStableSnapshotPerformsZeroCallUpdatesAndDurationCorrectionTouchesOneRow() {
        store.transaction { state ->
            state.profiles["31234"] = ProfileRecord("31234")
            val fact = WorkClassifier.classify(state, 1780275600000L)
            state.calls[1] =
                RawCallRecord(
                    1,
                    "31234",
                    1780275600000L,
                    0,
                    CallResult.ANSWERED.toDomain(),
                    CallDecision.SYSTEM_LOG_ONLY.toDomain(),
                    CallSource.SYSTEM_CALL_LOG.toDomain(),
                    "one",
                    null,
                    true,
                    fact,
                    fact,
                )
            state.nextCallId = 2
        }
        fixture().use { db ->
            db.execSQL("CREATE TABLE qa_updates(n INTEGER NOT NULL)")
            db.execSQL("INSERT INTO qa_updates VALUES(0)")
            db.execSQL(
                "CREATE TRIGGER qa_updates_calls AFTER UPDATE ON calls BEGIN UPDATE qa_updates SET n=n+1; END"
            )
        }
        store.transaction {}
        fixture().use { assertEquals(0L, it.scalar("SELECT n FROM qa_updates")) }
        store.transaction { state -> state.calls[1] = state.calls.getValue(1).copy(duration = 20) }
        fixture().use { assertEquals(1L, it.scalar("SELECT n FROM qa_updates")) }
        assertEquals(20L, store.read { it.calls.getValue(1).duration })
    }

    fun testDeletedHighestIdentifierIsNotReusedAfterReopen() {
        store.transaction { state ->
            state.positions[7] = PositionRecord(7, "temporary", 1, true)
            state.nextPositionId = 8
        }
        store.transaction { it.positions.clear() }
        store.close()
        store = SqliteBackendStore(instrumentation.targetContext, database)
        assertEquals(8L, store.read { it.nextPositionId })
        store.transaction { state -> state.nextPositionId = 12 }
        store.close()
        store = SqliteBackendStore(instrumentation.targetContext, database)
        assertEquals(12L, store.read { it.nextPositionId })
    }

    fun testInvalidMapPrimaryKeyRollsBackTheEntireSnapshot() {
        try {
            store.transaction { state ->
                state.positions[1] = PositionRecord(2, "invalid", 1, true)
                state.overlayEnabled = true
            }
            fail("Mismatched primary key accepted")
        } catch (expected: IllegalArgumentException) {}
        assertTrue(store.read { it.positions.isEmpty() })
        assertFalse(store.read { it.overlayEnabled })
    }

    fun testIgnoredSettingsWriteRollsBackOtherValues() {
        fixture().use { db ->
            db.execSQL(
                "CREATE TRIGGER qa_ignore_weight BEFORE INSERT ON settings WHEN NEW.key='repeat_weight' BEGIN SELECT RAISE(IGNORE); END"
            )
        }
        try {
            store.transaction { it.score = ScoreSettings(7, 3, 15, 45).toDomain() }
            fail("Ignored insert accepted")
        } catch (expected: IllegalStateException) {}
        assertEquals(ScoreSettings(5, 2, 10, 30), store.read { it.score })
    }

    fun testUnknownLegacyStringsSurviveUnrelatedCallChanges() {
        store.transaction { state ->
            state.profiles["31234"] = ProfileRecord("31234")
            val fact = WorkClassifier.classify(state, 1780275600000L)
            state.calls[1] =
                RawCallRecord(
                    1,
                    "31234",
                    1780275600000L,
                    null,
                    CallResult.UNKNOWN.toDomain(),
                    CallDecision.UNKNOWN.toDomain(),
                    CallSource.UNKNOWN.toDomain(),
                    "legacy",
                    null,
                    true,
                    fact,
                    fact,
                    legacyResult = "old result",
                    legacyDecision = "old decision",
                    legacySource = "old source",
                )
            state.nextCallId = 2
        }
        fixture().use {
            it.execSQL(
                "UPDATE calls SET reason='old reason',original_reason='old original' WHERE id=1"
            )
        }
        store.transaction { state -> state.calls[1] = state.calls.getValue(1).copy(duration = 9) }
        fixture().use { db ->
            db.rawQuery(
                    "SELECT result,decision,source,reason,original_reason FROM calls WHERE id=1",
                    null,
                )
                .use {
                    assertTrue(it.moveToFirst())
                    assertEquals("old result", it.getString(0))
                    assertEquals("old decision", it.getString(1))
                    assertEquals("old source", it.getString(2))
                    assertEquals("old reason", it.getString(3))
                    assertEquals("old original", it.getString(4))
                }
        }
    }

    fun testForeignKeysAndImmutableAuditRemainEnabled() {
        store.transaction { it.pendingAudits.add(AuditRecord("test", "target", "raw", 1)) }
        fixture().use { db ->
            try {
                db.execSQL("UPDATE calendar_audit SET raw='changed'")
                fail("Audit was mutable")
            } catch (expected: android.database.sqlite.SQLiteException) {}
        }
        try {
            store.transaction { state ->
                state.profiles["31234"] = ProfileRecord("31234", positionId = 99)
            }
            fail("Dangling position accepted")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {}
        assertTrue(store.read { it.profiles.isEmpty() })
    }

    fun testVersionOneMigrationRestoresScreeningTimeAndKeepsOriginalFacts() {
        val at =
            LocalDate.of(2026, 6, 1)
                .atTime(11, 59, 59)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        store.transaction { state ->
            state.positions[1] = PositionRecord(1, "manager", 2, true)
            state.nextPositionId = 2
            state.profiles["31234"] = ProfileRecord("31234", "name", "department", 1, true)
            state.myPositionId = 1
            val fact = WorkClassifier.classify(state, at)
            state.calls[1] =
                RawCallRecord(
                    1,
                    "31234",
                    at,
                    null,
                    CallResult.PENDING.toDomain(),
                    CallDecision.WHITELIST_ALLOWED.toDomain(),
                    CallSource.SCREENING.toDomain(),
                    null,
                    "31234:$at",
                    true,
                    fact,
                    fact,
                )
            state.nextCallId = 2
        }
        val original = store.read { it.calls.getValue(1).originalClassification }
        store.close()
        fixture().use { db ->
            db.execSQL(
                "CREATE TABLE legacy_calls(id INTEGER PRIMARY KEY AUTOINCREMENT,number TEXT NOT NULL REFERENCES callers(number) ON DELETE RESTRICT,time INTEGER NOT NULL,duration INTEGER CHECK(duration IS NULL OR duration>=0),result TEXT NOT NULL,decision TEXT NOT NULL,source TEXT NOT NULL,log_id TEXT UNIQUE,screening_key TEXT UNIQUE,counted INTEGER NOT NULL CHECK(counted IN(0,1)),working INTEGER NOT NULL,verified INTEGER NOT NULL,reason TEXT NOT NULL,original_working INTEGER NOT NULL,original_verified INTEGER NOT NULL,original_reason TEXT NOT NULL,config_version INTEGER NOT NULL REFERENCES config_versions(id),original_config_version INTEGER NOT NULL REFERENCES config_versions(id),calendar_version TEXT NOT NULL,original_calendar_version TEXT NOT NULL,leave_version INTEGER NOT NULL,original_leave_version INTEGER NOT NULL,zone TEXT NOT NULL,original_zone TEXT NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO legacy_calls SELECT id,number,time,duration,result,decision,source,log_id,screening_key,counted,working,verified,reason,original_working,original_verified,original_reason,config_version,original_config_version,calendar_version,original_calendar_version,leave_version,original_leave_version,zone,original_zone FROM calls"
            )
            db.execSQL("DROP TABLE calls")
            db.execSQL("ALTER TABLE legacy_calls RENAME TO calls")
            db.execSQL("CREATE INDEX calls_number_time ON calls(number,time)")
            db.execSQL("CREATE INDEX calls_pending ON calls(number,time) WHERE log_id IS NULL")
            db.execSQL(
                "UPDATE calls SET log_id='legacy-provider',time=?,duration=8,result='已接听',source='筛选＋系统通话记录',working=0,reason='工作时段外' WHERE id=1",
                arrayOf<Any>(at + 1000),
            )
            db.version = 1
        }
        store = SqliteBackendStore(instrumentation.targetContext, database)
        store.read { state ->
            assertEquals(2, state.calls.size)
            assertEquals("name", state.profiles.getValue("31234").name)
            assertEquals(1L, state.myPositionId)
            val screen = state.calls.getValue(1)
            assertEquals(at, screen.time)
            assertNull(screen.logId)
            assertTrue(screen.hidden)
            assertEquals(original, screen.originalClassification)
            assertTrue(screen.classification.working)
            assertEquals(com.greatwall.callring.domain.model.CallDecision.WHITELIST_ALLOWED, screen.decision)
            val provider = state.calls.values.single { it.logId != null }
            assertEquals(at + 1000, provider.time)
            assertEquals(1L, provider.linkedScreenId)
        }
        fixture().use { db ->
            assertEquals(3, db.version)
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM pragma_foreign_key_check"))
        }
    }

    fun testVersionTwoMigrationRepairsOnlyCurrentScreeningClassification() {
        val at =
            LocalDate.of(2026, 6, 1)
                .atTime(10, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        store.transaction { state ->
            state.profiles["31234"] = ProfileRecord("31234")
            val fact = WorkClassifier.classify(state, at)
            state.calls[1] =
                RawCallRecord(
                    1,
                    "31234",
                    at,
                    null,
                    CallResult.PENDING.toDomain(),
                    CallDecision.WHITELIST_ALLOWED.toDomain(),
                    CallSource.SCREENING.toDomain(),
                    null,
                    "31234:$at",
                    true,
                    fact,
                    fact,
                )
            state.nextCallId = 2
        }
        val original = store.read { it.calls.getValue(1).originalClassification }
        store.close()
        fixture().use { db ->
            db.execSQL("UPDATE calls SET working=0,reason='工作时段外' WHERE id=1")
            db.version = 2
        }
        store = SqliteBackendStore(instrumentation.targetContext, database)
        store.read { state ->
            assertTrue(state.calls.getValue(1).classification.working)
            assertEquals(original, state.calls.getValue(1).originalClassification)
        }
    }

    fun testCalendarHolidayToMakeupReplacementAndAuditAreAtomic() {
        val date = LocalDate.of(2026, 10, 1)
        store.transaction { state ->
            state.holidays.remove(date)
            state.makeupDates.add(date)
            state.calendarYears[2026] = CalendarYear(2026, "replacement", "2026-01-01", "test").toDomain()
            state.pendingAudits.add(AuditRecord("calendar", "2026", "exact raw document", 10))
        }
        fixture().use { db ->
            db.rawQuery("SELECT working,name FROM calendar_days WHERE date=?", args(date)).use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
                assertEquals("调休上班", it.getString(1))
            }
        }
        store.read { state ->
            assertTrue(date in state.makeupDates)
            assertFalse(date in state.holidays)
        }
    }

    fun testConcurrentTransactionsCaptureAfterAcquiringTheWriterLock() {
        store.transaction { it.profiles["31234"] = ProfileRecord("31234", "name", "department") }
        val other = SqliteBackendStore(instrumentation.targetContext, database)
        val startTogether = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        try {
            fun worker(connection: SqliteBackendStore) = Thread {
                try {
                    startTogether.await()
                    repeat(25) {
                        connection.transaction { state ->
                            val profile = state.profiles.getValue("31234")
                            state.profiles["31234"] =
                                profile.copy(whitelisted = !profile.whitelisted)
                        }
                    }
                } catch (problem: Throwable) {
                    failure.compareAndSet(null, problem)
                } finally {
                    done.countDown()
                }
            }
            worker(store).start()
            worker(other).start()
            startTogether.countDown()
            assertTrue("Concurrent transactions timed out", done.await(15, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Concurrent transaction failed", it) }
            store.read { state ->
                assertFalse(state.profiles.getValue("31234").whitelisted)
                assertEquals("name", state.profiles.getValue("31234").name)
            }
        } finally {
            other.close()
        }
    }

    fun testCalendarRejectsCoercedYearAndMetadataTypes() {
        val template =
            """{"year":2026,"source":"source","published":"2026-01-01","version":"v1","holidays":[],"workdays":[]}"""
        val invalid =
            listOf(
                template.replace("2026,", "2026.75,"),
                template.replace("2026,", "\"2026\","),
                template.replace("2026,", "2026e0,"),
                template.replace("\"source\":\"source\"", "\"source\":true"),
                template.replace("\"version\":\"v1\"", "\"version\":1"),
                template.replace("\"published\":\"2026-01-01\"", "\"published\":null"),
            )
        invalid.forEach { raw ->
            try {
                WorkConfigCodec.calendar(raw)
                fail("Nonconforming calendar field accepted: $raw")
            } catch (expected: IllegalArgumentException) {}
        }
        val valid = WorkConfigCodec.calendar(template)
        assertEquals(2026, valid.metadata.year)
        assertEquals("source", valid.metadata.source)
        assertEquals("v1", valid.metadata.version)
    }

    fun testCalendarRejectsNonStringHolidayName() {
        val raw =
            """{"year":2026,"source":"source","published":"2026-01-01","version":"v1","holidays":[{"start":"2026-01-01","end":"2026-01-01","name":true}],"workdays":[]}"""
        try {
            WorkConfigCodec.calendar(raw)
            fail("Non-string holiday name accepted")
        } catch (expected: IllegalArgumentException) {}
    }

    fun testIgnoredLeaveDeleteRollsBackClassificationRevisionAuditAndOtherWrites() {
        val at =
            LocalDate.of(2026, 6, 1)
                .atTime(10, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        store.transaction { state ->
            state.profiles["31234"] = ProfileRecord("31234")
            val original = WorkClassifier.classify(state, at)
            state.leaves[1] = LeaveRecord(1, at - 1000, at + 1000, "retained leave")
            state.nextLeaveId = 2
            state.leaveVersion = 1
            val current = WorkClassifier.classify(state, at)
            state.calls[1] =
                RawCallRecord(
                    1,
                    "31234",
                    at,
                    null,
                    CallResult.PENDING.toDomain(),
                    CallDecision.NORMAL_RINGING.toDomain(),
                    CallSource.SCREENING.toDomain(),
                    null,
                    "31234:$at",
                    true,
                    current,
                    original,
                )
            state.nextCallId = 2
        }
        val sourceBefore = store.read { it.calendarYears.getValue(2026).source }
        val callBefore = store.read { it.calls.getValue(1) }
        val auditsBefore = fixture().use { it.scalar("SELECT COUNT(*) FROM calendar_audit") }
        fixture().use {
            it.execSQL(
                "CREATE TRIGGER qa_ignore_leave_delete BEFORE DELETE ON leaves BEGIN SELECT RAISE(IGNORE); END"
            )
        }
        try {
            store.transaction { state ->
                state.calendarYears[2026] =
                    state.calendarYears.getValue(2026).copy(source = "must roll back")
                state.leaves.remove(1)
                state.leaveVersion++
                state.pendingAudits.add(AuditRecord("leave-delete", "1", "", at))
                state.overlayEnabled = true
                WorkClassifier.reclassify(state)
            }
            fail("Ignored leave deletion reported success")
        } catch (expected: IllegalStateException) {}
        store.read { state ->
            assertEquals("retained leave", state.leaves.getValue(1).note)
            assertEquals(1L, state.leaveVersion)
            assertEquals(callBefore, state.calls.getValue(1))
            assertEquals(sourceBefore, state.calendarYears.getValue(2026).source)
            assertFalse(state.overlayEnabled)
        }
        fixture().use {
            assertEquals(auditsBefore, it.scalar("SELECT COUNT(*) FROM calendar_audit"))
        }
    }
}
