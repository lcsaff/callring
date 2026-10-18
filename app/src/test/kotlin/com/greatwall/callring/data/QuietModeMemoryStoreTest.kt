package com.greatwall.callring.data

import com.greatwall.callring.application.api.*
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
        SqliteBackendStore(context, "quiet_memory.db").use { store ->
            store.transaction { it.quiet = custom; it.lastQuietMode = QuietMode.CUSTOM }
            store.transaction { it.quiet = it.quiet.copy(mode = QuietMode.OFF) }
        }
        SqliteBackendStore(context, "quiet_memory.db").use { store ->
            store.read { assertEquals(QuietMode.CUSTOM, it.lastQuietMode); assertEquals(custom.copy(mode = QuietMode.OFF), it.quiet) }
        }
    }
    @Test fun rememberedModeChangePersistsEvenWhenActualModeDoesNotChange() {
        SqliteBackendStore(context, "quiet_memory_only.db").use { store ->
            store.transaction { it.lastQuietMode = QuietMode.CUSTOM }
        }
        SqliteBackendStore(context, "quiet_memory_only.db").use { store ->
            store.read { assertEquals(QuietMode.OFF, it.quiet.mode); assertEquals(QuietMode.CUSTOM, it.lastQuietMode) }
        }
    }
    @Test fun existingRememberedModeKeyIsReadAndInvalidValueFallsBack() {
        val name = "quiet_memory_legacy.db"
        SqliteBackendStore(context, name).close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES('last_quiet_mode','CUSTOM')")
        }
        SqliteBackendStore(context, name).use { store ->
            store.read { assertEquals(QuietMode.CUSTOM, it.lastQuietMode) }
        }
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("UPDATE settings SET value='unknown' WHERE key='last_quiet_mode'")
        }
        SqliteBackendStore(context, name).use { store ->
            store.read { assertEquals(QuietMode.NON_WORK, it.lastQuietMode) }
        }
    }
    @Test fun disablingLegacyCustomWithoutRememberedKeyPersistsResumeMode() {
        val name = "quiet_memory_legacy_active.db"
        SqliteBackendStore(context, name).use { store ->
            store.transaction { it.quiet = QuietSettings(QuietMode.CUSTOM, false, mapOf(1 to listOf(TimeWindow(1320, 420)))) }
        }
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("DELETE FROM settings WHERE key='last_quiet_mode'")
        }
        SqliteBackendStore(context, name).use { store ->
            store.transaction { it.quiet = it.quiet.copy(mode = QuietMode.OFF) }
        }
        SqliteBackendStore(context, name).use { store ->
            store.read { assertEquals(QuietMode.CUSTOM, it.lastQuietMode) }
        }
    }
}
