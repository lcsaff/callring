package com.greatwall.callring.data

import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.data.database.values
import com.greatwall.callring.domain.calling.WorkClassifier
import com.greatwall.callring.domain.model.PositionRecord
import com.greatwall.callring.domain.model.ProfileRecord
import com.greatwall.callring.domain.model.RawCallRecord

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.domain.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CallingMigrationTest {
    private lateinit var store: SqliteCoreStorageResource
    private val database = "calling-migration.db"

    @Before
    fun open() {
        RuntimeEnvironment.getApplication().deleteDatabase(database)
        store = SqliteCoreStorageResource(RuntimeEnvironment.getApplication(), database)
    }

    @After
    fun close() {
        store.close()
    }

    private fun fixture() =
        SQLiteDatabase.openDatabase(
            RuntimeEnvironment.getApplication().getDatabasePath(database).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )

    private fun rejectIncidentalWrites(db: SQLiteDatabase) {
        listOf(
                "callers",
                "positions",
                "settings",
                "config_versions",
                "calendar_years",
                "calendar_days",
                "overrides",
                "leaves",
                "calendar_audit",
            )
            .forEach { table ->
                listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                    db.execSQL(
                        "CREATE TRIGGER guard_${table}_${operation} BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT, 'incidental migration write'); END",
                    )
                }
            }
    }

    @Test
    fun versionOneMigrationRestoresScreeningTimeAndKeepsOriginalFacts() {
        val at =
            LocalDate.of(2026, 6, 1)
                .atTime(11, 59, 59)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        store.fixtureSql { state ->
            state.seedPosition(PositionRecord(1, "manager", 2, true))
            state.seedSequence("positions", 2)
            state.seedCaller(ProfileRecord("31234", "name", "department", 1, true))
            state.execSQL("INSERT INTO settings(key,value,position_id) VALUES('my_position','1',1)")
            val fact = WorkClassifier.classify(state.fixtureWork(), at)
            state.seedCall(RawCallRecord(
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
                ))
            state.seedSequence("calls", 2)
        }
        val original = store.fullSnapshot.capture().let { it.calls.getValue(1).originalClassification }
        store.close()
        fixture().use { db ->
            db.execSQL(
                "CREATE TABLE legacy_calls(id INTEGER PRIMARY KEY AUTOINCREMENT,number TEXT NOT NULL REFERENCES callers(number) ON DELETE RESTRICT,time INTEGER NOT NULL,duration INTEGER CHECK(duration IS NULL OR duration>=0),result TEXT NOT NULL,decision TEXT NOT NULL,source TEXT NOT NULL,log_id TEXT UNIQUE,screening_key TEXT UNIQUE,counted INTEGER NOT NULL CHECK(counted IN(0,1)),working INTEGER NOT NULL,verified INTEGER NOT NULL,reason TEXT NOT NULL,original_working INTEGER NOT NULL,original_verified INTEGER NOT NULL,original_reason TEXT NOT NULL,config_version INTEGER NOT NULL REFERENCES config_versions(id),original_config_version INTEGER NOT NULL REFERENCES config_versions(id),calendar_version TEXT NOT NULL,original_calendar_version TEXT NOT NULL,leave_version INTEGER NOT NULL,original_leave_version INTEGER NOT NULL,zone TEXT NOT NULL,original_zone TEXT NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO legacy_calls SELECT id,number,time,duration,result,decision,source,log_id,screening_key,counted,working,verified,reason,original_working,original_verified,original_reason,config_version,original_config_version,calendar_version,original_calendar_version,leave_version,original_leave_version,zone,original_zone FROM calls",
            )
            db.execSQL("DROP TABLE calls")
            db.execSQL("ALTER TABLE legacy_calls RENAME TO calls")
            db.execSQL("CREATE INDEX calls_number_time ON calls(number,time)")
            db.execSQL("CREATE INDEX calls_pending ON calls(number,time) WHERE log_id IS NULL")
            db.execSQL(
                "UPDATE calls SET log_id='legacy-provider',time=?,duration=8,result='已接听',source='筛选＋系统通话记录',working=0,reason='工作时段外' WHERE id=1",
                arrayOf<Any>(at + 1000),
            )
            rejectIncidentalWrites(db)
            db.version = 1
        }
        store = SqliteCoreStorageResource(RuntimeEnvironment.getApplication(), database)
        store.fullSnapshot.capture().let { state ->
            assertEquals(2, state.calls.size)
            assertEquals("name", state.profiles.getValue("31234").name)
            assertEquals(1L, state.myPositionId)
            val screen = state.calls.getValue(1)
            assertEquals(at, screen.time)
            assertNull(screen.logId)
            assertTrue(screen.hidden)
            assertEquals(original, screen.originalClassification)
            assertTrue(screen.classification.working)
            assertEquals(
                com.greatwall.callring.domain.model.CallDecision.WHITELIST_ALLOWED,
                screen.decision,
            )
            val provider = state.calls.values.single { it.logId != null }
            assertEquals(at + 1000, provider.time)
            assertEquals(1L, provider.linkedScreenId)
        }
        fixture().use { db ->
            assertEquals(3, db.version)
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM pragma_foreign_key_check"))
        }
    }

    @Test
    fun versionTwoMigrationRepairsOnlyCurrentScreeningClassification() {
        val at =
            LocalDate.of(2026, 6, 1)
                .atTime(10, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        store.fixtureSql { state ->
            state.seedCaller(ProfileRecord("31234"))
            val fact = WorkClassifier.classify(state.fixtureWork(), at)
            state.seedCall(RawCallRecord(
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
                ))
            state.seedSequence("calls", 2)
        }
        val original = store.fullSnapshot.capture().let { it.calls.getValue(1).originalClassification }
        store.close()
        fixture().use { db ->
            db.execSQL("UPDATE calls SET working=0,reason='工作时段外' WHERE id=1")
            rejectIncidentalWrites(db)
            db.version = 2
        }
        store = SqliteCoreStorageResource(RuntimeEnvironment.getApplication(), database)
        store.fullSnapshot.capture().let { state ->
            assertTrue(state.calls.getValue(1).classification.working)
            assertEquals(original, state.calls.getValue(1).originalClassification)
        }
    }
}
