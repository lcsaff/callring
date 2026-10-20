package com.greatwall.callring.data

import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.domain.StatisticsInterval
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
            val before = sessions.readSession { it.quiet() to it.statistics() }
            assertThrows(IllegalStateException::class.java) {
                sessions.writeSession { session ->
                    session.saveQuiet(session.quiet().copy(settings = session.quiet().settings.copy(mode = QuietMode.NON_WORK)))
                    session.appendStatistics(StatisticsInterval(123L, null))
                    assertEquals(QuietMode.NON_WORK, session.quiet().settings.mode)
                    assertEquals(123L, session.statistics().last().startMillis)
                    error("rollback")
                }
            }
            assertEquals(before, sessions.readSession { it.quiet() to it.statistics() })
            sessions.writeSession { session ->
                session.saveQuiet(session.quiet().copy(settings = session.quiet().settings.copy(mode = QuietMode.NON_WORK)))
                session.appendStatistics(StatisticsInterval(456L, null))
            }
            assertEquals(QuietMode.NON_WORK, sessions.readSession { it.quiet().settings.mode })
            assertEquals(456L, sessions.readSession { it.statistics().last().startMillis })
            assertEquals(0L, store.stateLoads.get())
        }
    }
    @Test fun escapedReaderAndWriterRejectUseAfterSuccessAndFailure() {
        SqliteBackendStore(RuntimeEnvironment.getApplication(), "scoped-escape.db").use { store ->
            val reader = store.sessions.readSession { it }
            val value = store.sessions.readQuiet { it.quiet() }
            assertThrows(IllegalStateException::class.java) { reader.quiet() }
            assertThrows(IllegalStateException::class.java) { reader.statistics() }
            lateinit var writer: CoreWriteSession
            assertThrows(IllegalStateException::class.java) {
                store.sessions.writeSession { writer = it; error("failure") }
            }
            assertThrows(IllegalStateException::class.java) { writer.quiet() }
            assertThrows(IllegalStateException::class.java) { writer.saveQuiet(value) }
            assertThrows(IllegalStateException::class.java) { writer.appendStatistics(StatisticsInterval(123L, null)) }
        }
    }
}
