package com.greatwall.callring.data

import com.greatwall.callring.data.database.SqliteCoreStorageResource

import com.greatwall.callring.domain.model.QuietMode
import com.greatwall.callring.domain.model.QuietSettings
import com.greatwall.callring.domain.model.TimeWindow
import com.greatwall.callring.contracts.settings.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class QuietModeMemoryStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun customResumeAndWeeklyWindowsSurviveCloseAndReopenWhileOff() {
        val custom = QuietSettings(QuietMode.CUSTOM, true,
            (1..7).associateWith { day -> if (day == 1) listOf(TimeWindow(1380, 60)) else emptyList() })
        SqliteCoreStorageResource(context, "quiet_memory.db").use { store ->
            store.sessions.writeQuiet { it.saveQuiet(com.greatwall.callring.application.ports.store.QuietConfiguration(custom, QuietMode.CUSTOM)) }
            store.sessions.writeQuiet { val old = it.quiet(); it.saveQuiet(old.copy(settings = old.settings.copy(mode = QuietMode.OFF))) }
        }
        SqliteCoreStorageResource(context, "quiet_memory.db").use { store ->
            store.fullSnapshot.capture().let { assertEquals(QuietMode.CUSTOM, it.lastQuietMode); assertEquals(custom.copy(mode = QuietMode.OFF), it.quiet) }
        }
    }
    @Test fun rememberedModeChangePersistsEvenWhenActualModeDoesNotChange() {
        SqliteCoreStorageResource(context, "quiet_memory_only.db").use { store ->
            store.sessions.writeQuiet { it.saveQuiet(it.quiet().copy(lastMode = QuietMode.CUSTOM)) }
        }
        SqliteCoreStorageResource(context, "quiet_memory_only.db").use { store ->
            store.fullSnapshot.capture().let { assertEquals(QuietMode.OFF, it.quiet.mode); assertEquals(QuietMode.CUSTOM, it.lastQuietMode) }
        }
    }
    @Test fun existingRememberedModeKeyIsReadAndInvalidValueFallsBack() {
        val name = "quiet_memory_legacy.db"
        SqliteCoreStorageResource(context, name).close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES('last_quiet_mode','CUSTOM')")
        }
        SqliteCoreStorageResource(context, name).use { store ->
            store.fullSnapshot.capture().let { assertEquals(QuietMode.CUSTOM, it.lastQuietMode) }
        }
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("UPDATE settings SET value='unknown' WHERE key='last_quiet_mode'")
        }
        SqliteCoreStorageResource(context, name).use { store ->
            store.fullSnapshot.capture().let { assertEquals(QuietMode.NON_WORK, it.lastQuietMode) }
        }
    }
    @Test fun disablingLegacyCustomWithoutRememberedKeyPersistsResumeMode() {
        val name = "quiet_memory_legacy_active.db"
        SqliteCoreStorageResource(context, name).use { store ->
            store.sessions.writeQuiet { it.saveQuiet(com.greatwall.callring.application.ports.store.QuietConfiguration(QuietSettings(QuietMode.CUSTOM, false, mapOf(1 to listOf(TimeWindow(1320, 420)))), QuietMode.CUSTOM)) }
        }
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("DELETE FROM settings WHERE key='last_quiet_mode'")
        }
        SqliteCoreStorageResource(context, name).use { store ->
            store.sessions.writeQuiet { val old = it.quiet(); it.saveQuiet(old.copy(settings = old.settings.copy(mode = QuietMode.OFF))) }
        }
        SqliteCoreStorageResource(context, name).use { store ->
            store.fullSnapshot.capture().let { assertEquals(QuietMode.CUSTOM, it.lastQuietMode) }
        }
    }
}
