package com.greatwall.callring.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SqlAccessProbeTest {
    @Test
    fun consumptionPreservesTypesAndCountsEveryMovementWithoutCountingJoinTablesTwice() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE calls(id INTEGER, value BLOB)")
            db.execSQL("INSERT INTO calls VALUES(1,X'0102'),(2,NULL),(3,X'03')")
            val probe = SqlAccessProbe()
            val sql = SqlAccess(db, probe)
            val cursor =
                sql.rawQuery(
                    QueryTag.SCOPED_ROWS,
                    setOf(SqlTable.CALLS, SqlTable.CALLERS),
                    "SELECT id,value,1.5,'secret-binding' FROM calls",
                    null,
                )
            cursor.use {
                assertTrue(it.moveToFirst())
                assertEquals(Cursor.FIELD_TYPE_INTEGER, it.getType(0))
                assertArrayEquals(byteArrayOf(1, 2), it.getBlob(1))
                assertEquals(Cursor.FIELD_TYPE_FLOAT, it.getType(2))
                assertEquals("secret-binding", it.getString(3))
                assertTrue(it.moveToPosition(1))
                assertTrue(it.isNull(1))
                assertTrue(it.moveToLast())
                assertTrue(it.moveToPrevious())
                assertTrue(it.move(1))
                assertFalse(it.moveToNext())
            }
            cursor.close()
            val result = probe.snapshot().single()
            assertEquals(1L, result.calls)
            assertEquals(5L, result.consumedRows)
            assertEquals(1L, result.closed)
            assertFalse(probe.snapshot().toString().contains("secret-binding"))
        }
    }

    @Test
    fun aggregatesCountResultRowsAndEarlyExitIsVisibleBeforeClose() {
        SQLiteDatabase.create(null).use { db ->
            val probe = SqlAccessProbe()
            val sql = SqlAccess(db, probe)
            sql.rawQuery(QueryTag.AGGREGATE, setOf(SqlTable.CALLS), "SELECT 10000", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(1L, probe.snapshot().single().consumedRows)
            }
            try {
                sql.rawQuery(
                        QueryTag.SCOPED_ROWS,
                        setOf(SqlTable.CALLS),
                        "SELECT 1 UNION ALL SELECT 2",
                        null,
                    )
                    .use {
                        assertTrue(it.moveToNext())
                        error("consumer failed with secret")
                    }
            } catch (_: IllegalStateException) {}
            assertEquals(
                1L,
                probe.snapshot().single { it.tag == QueryTag.SCOPED_ROWS }.consumedRows,
            )
            assertEquals(1L, probe.snapshot().single { it.tag == QueryTag.SCOPED_ROWS }.closed)
            assertFalse(probe.snapshot().toString().contains("secret"))
        }
    }

    @Test
    fun fullCaptureRowsAndPreflightAreObservedAndSqlValuesRemainTyped() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE calls(id INTEGER, value BLOB, optional TEXT)")
            db.execSQL("INSERT INTO calls VALUES(1,X'0102',NULL)")
            val probe = SqlAccessProbe()
            val sql = SqlAccess(db, probe)
            sql.query(QueryTag.LOAD_ROWS, "calls", null, null, null, null, null, null).use { previous ->
                assertTrue(previous.moveToFirst())
                assertEquals(Cursor.FIELD_TYPE_INTEGER, previous.getType(0))
                assertArrayEquals(byteArrayOf(1, 2), previous.getBlob(1))
                assertTrue(previous.isNull(2))
            }
            assertEquals(1L, probe.snapshot().single().consumedRows)
            checkSnapshotSize(db, sql)
            assertEquals(
                1L,
                probe
                    .snapshot()
                    .single {
                        it.tag == QueryTag.PREFLIGHT_AGGREGATE && it.tables == setOf(SqlTable.CALLS)
                    }
                    .consumedRows,
            )
        }
    }

    @Test
    fun realScopedJoinAndStatementAggregateUseTheSameInstanceProbe() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val probe = SqlAccessProbe()
        SqliteCoreStorageResource(context, "sql-probe-scoped.db", probe).use { store ->
            store.fixtureSql { state ->
                state.seedCaller(com.greatwall.callring.domain.ProfileRecord("synthetic-private"))
            }
            val before = probe.snapshot().sumOf { it.calls }
            val port =
                store.fixtureScopedReads(com.greatwall.callring.application.BusinessRevisionCoordinator())
            val page =
                port.query(
                    com.greatwall.callring.contracts.ai.QuerySpec(
                        com.greatwall.callring.contracts.ai.AiDomain.D01,
                    ),
                    null,
                )
            assertEquals(1L, page.matchedTotal)
            val rows = probe.snapshot().filter { it.tag == QueryTag.SCOPED_ROWS }
            assertEquals(1, rows.size)
            assertEquals(
                setOf(SqlTable.CALLERS, SqlTable.CALLS, SqlTable.POSITIONS),
                rows.single().tables,
            )
            assertEquals(1L, rows.single().consumedRows)
            assertEquals(1L, probe.snapshot().single { it.tag == QueryTag.AGGREGATE }.consumedRows)
            assertEquals(2L, probe.snapshot().sumOf { it.calls } - before)
            assertFalse(probe.snapshot().toString().contains("synthetic-private"))
        }
    }

    @Test
    fun failedQueriesAndRolledBackTransactionsNeverClaimSuccessfulWrites() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE calls(id INTEGER)")
            val probe = SqlAccessProbe()
            val sql = SqlAccess(db, probe)
            try {
                sql.rawQuery(
                        QueryTag.SCOPED_ROWS,
                        setOf(SqlTable.CALLS),
                        "SELECT secret_column FROM calls",
                        null,
                    )
                    .use { it.moveToFirst() }
                fail("invalid SQL must fail")
            } catch (_: android.database.sqlite.SQLiteException) {}
            assertEquals(1L, probe.snapshot().single().failures)
            assertEquals(0L, probe.snapshot().single().consumedRows)
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO calls VALUES(42)")
                assertEquals(
                    1L,
                    sql.longForQuery(
                        QueryTag.AGGREGATE,
                        setOf(SqlTable.CALLS),
                        "SELECT count(*) FROM calls",
                        null,
                    ),
                )
                error("synthetic rollback")
            } catch (_: IllegalStateException) {} finally {
                db.endTransaction()
            }
            assertEquals(
                0L,
                sql.longForQuery(
                    QueryTag.AGGREGATE,
                    setOf(SqlTable.CALLS),
                    "SELECT count(*) FROM calls",
                    null,
                ),
            )
            // This test writes through raw db and observes reads, including uncommitted data.
            // SqlAccess writtenRows elsewhere measures statement effects, never transaction
            // commits.
            assertEquals(2L, probe.snapshot().single { it.tag == QueryTag.AGGREGATE }.consumedRows)
            assertFalse(probe.snapshot().toString().contains("secret_column"))
        }
    }

    @Test
    fun absentObserverKeepsNativeCursorAndStatementBehavior() {
        SQLiteDatabase.create(null).use { db ->
            val sql = SqlAccess(db)
            sql.rawQuery(QueryTag.SCOPED_ROWS, setOf(SqlTable.CALLS), "SELECT NULL,X'07',2.5", null)
                .use {
                    assertFalse(it is android.database.CursorWrapper)
                    assertTrue(it.moveToFirst())
                    assertTrue(it.isNull(0))
                    assertArrayEquals(byteArrayOf(7), it.getBlob(1))
                    assertEquals(2.5, it.getDouble(2), 0.0)
                }
            assertEquals(
                "native",
                sql.stringForQuery(
                    QueryTag.SCOPED_SETTING,
                    setOf(SqlTable.SETTINGS),
                    "SELECT ?",
                    arrayOf("native"),
                ),
            )
        }
    }

    @Test
    fun unitInsertRejectsIgnoredRowsAndReportsZeroStatementWrites() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE calls(id INTEGER PRIMARY KEY)")
            db.execSQL(
                "CREATE TRIGGER ignore_insert BEFORE INSERT ON calls BEGIN SELECT RAISE(IGNORE); END",
            )
            val probe = SqlAccessProbe()
            val failure = runCatching {
                SqlAccess(db, probe).insert("calls", values("id" to 1))
            }
                .exceptionOrNull()
            assertEquals(0L, probe.snapshot().single().writtenRows)
            assertTrue(failure is IllegalStateException)
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM calls"))
        }
    }
}
