package com.greatwall.callring.data

import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.domain.model.QuietMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ScopedTransactionTest {
    @Test fun disablingLegacyCustomWithoutRememberedKeyStillPersistsResumeMode() {
        val context = RuntimeEnvironment.getApplication()
        val name = "scoped-legacy-custom.db"
        SqliteBackendStore(context, name).close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("INSERT INTO settings(key,value) VALUES('quiet_mode','CUSTOM')")
        }
        SqliteBackendStore(context, name).use { store ->
            store.sessions.writeQuiet { writer ->
                val before = writer.quiet()
                writer.saveQuiet(before.copy(settings = before.settings.copy(mode = QuietMode.OFF)))
            }
        }
        SqliteBackendStore(context, name).use { store ->
            assertEquals(QuietMode.CUSTOM, store.sessions.readQuiet { it.quiet().lastMode })
        }
    }
    @Test fun quietAndStatisticsUseOneTransactionAndRollbackTogether() {
        SqliteBackendStore(RuntimeEnvironment.getApplication(), "scoped-rollback.db").use { store ->
            val sessions = store.sessions
            val before = sessions.readSession { it.quiet() to it.statisticsHead() }
            assertThrows(IllegalStateException::class.java) {
                sessions.writeSession { session ->
                    session.saveQuiet(session.quiet().copy(settings = session.quiet().settings.copy(mode = QuietMode.NON_WORK)))
                    session.openStatistics(123L)
                    assertEquals(QuietMode.NON_WORK, session.quiet().settings.mode)
                    assertEquals(123L, session.statisticsHead().open!!.interval.startMillis)
                    error("rollback")
                }
            }
            assertEquals(before, sessions.readSession { it.quiet() to it.statisticsHead() })
            sessions.writeSession { session ->
                session.saveQuiet(session.quiet().copy(settings = session.quiet().settings.copy(mode = QuietMode.NON_WORK)))
                session.openStatistics(456L)
            }
            assertEquals(QuietMode.NON_WORK, sessions.readSession { it.quiet().settings.mode })
            assertEquals(456L, sessions.readSession { it.statisticsHead().open!!.interval.startMillis })
            assertEquals(0L, store.stateLoads.get())
        }
    }
    @Test fun simpleSettingsShareRollbackAndSqliteAutoincrementNeverReusesDeletedPosition() {
        SqliteBackendStore(RuntimeEnvironment.getApplication(), "simple-shared-rollback.db").use { store ->
            val original = store.sessions.readSession { it.score() to it.overlay() }
            assertThrows(IllegalStateException::class.java) {
                store.sessions.writeSession { writer ->
                    writer.saveScore(writer.score().copy(repeatWeight = 3))
                    writer.saveOverlay(true)
                    val role = writer.putPosition(com.greatwall.callring.domain.PositionRecord(0, "Transient", 1, true), true)
                    writer.saveMyPosition(role.id)
                    writer.openStatistics(100)
                    error("rollback")
                }
            }
            assertEquals(original, store.sessions.readSession { it.score() to it.overlay() })
            assertEquals(emptyList<com.greatwall.callring.domain.PositionRecord>(), store.sessions.readSession { it.positions() })
            assertNull(store.sessions.readSession { it.myPositionId() })
            assertNull(store.sessions.readSession { it.statisticsHead().open })
            val first = store.sessions.writePositions { it.putPosition(com.greatwall.callring.domain.PositionRecord(0, "First", 1, true), true) }
            store.sessions.writePositions { it.deletePosition(first.id) }
            val second = store.sessions.writePositions { it.putPosition(com.greatwall.callring.domain.PositionRecord(0, "Second", 1, true), true) }
            assertTrue(second.id > first.id)
            store.sessions.writePositions { it.saveMyPosition(second.id) }
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                store.sessions.writePositions { it.deletePosition(second.id) }
            }
            assertNotNull(store.sessions.readPositions { it.position(second.id) })
            assertEquals(0L, store.stateLoads.get())
        }
    }
    @Test fun escapedReaderAndWriterRejectUseAfterSuccessAndFailure() {
        SqliteBackendStore(RuntimeEnvironment.getApplication(), "scoped-escape.db").use { store ->
            val reader = store.sessions.readSession { it }
            val value = store.sessions.readQuiet { it.quiet() }
            assertThrows(IllegalStateException::class.java) { reader.quiet() }
            assertThrows(IllegalStateException::class.java) { reader.statisticsHead() }
            assertThrows(IllegalStateException::class.java) { reader.score() }
            assertThrows(IllegalStateException::class.java) { reader.overlay() }
            assertThrows(IllegalStateException::class.java) { reader.positions() }
            assertThrows(IllegalStateException::class.java) { reader.settingsSummary() }
            lateinit var writer: CoreWriteSession
            assertThrows(IllegalStateException::class.java) {
                store.sessions.writeSession { writer = it; error("failure") }
            }
            assertThrows(IllegalStateException::class.java) { writer.quiet() }
            assertThrows(IllegalStateException::class.java) { writer.saveScore(com.greatwall.callring.domain.model.ScoreSettings(5, 2, 10, 30)) }
            assertThrows(IllegalStateException::class.java) { writer.saveOverlay(true) }
            assertThrows(IllegalStateException::class.java) { writer.openStatistics(123) }
            assertThrows(IllegalStateException::class.java) { writer.saveMyPosition(null) }
            assertThrows(IllegalStateException::class.java) { writer.saveQuiet(value) }
        }
    }
}
