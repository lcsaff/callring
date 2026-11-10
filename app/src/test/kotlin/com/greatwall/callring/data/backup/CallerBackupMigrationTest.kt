package com.greatwall.callring.data.backup

import com.greatwall.callring.data.database.CallringDatabase
import com.greatwall.callring.data.stripCallerEnrichmentForFixture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CallerBackupMigrationTest {
    @Test fun versionThreeBackupUpgradesWithoutDiscardingProfileAndMatchesFreshSchema() = verifyUpgrade(3)

    @Test fun versionFourBackupKeepsEnrichmentAndMatchesFreshSchema() = verifyUpgrade(4)

    private fun verifyUpgrade(oldVersion: Int) {
        val context = RuntimeEnvironment.getApplication()
        val old = context.getDatabasePath("bk-old-$oldVersion.db")
        val current = context.getDatabasePath("bk-new-$oldVersion.db")
        context.deleteDatabase(old.name); context.deleteDatabase(current.name)
        CallringDatabase(context, old.name).use { it.writableDatabase.execSQL("INSERT INTO callers(number,name,department,whitelist,region) VALUES('31234','old','department',1,'上海')") }
        CallringDatabase(context, current.name).use { it.writableDatabase }
        android.database.sqlite.SQLiteDatabase.openDatabase(old.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            if (oldVersion == 3) stripCallerEnrichmentForFixture(it)
            else it.execSQL("DROP INDEX calls_visible_number_time")
            it.version = oldVersion
        }
        SqliteBackupCopy.upgradeCoreCandidate(context, old)
        SqliteBackupCopy.validate(old, current)
        CallringDatabase(context, old.name).use { helper ->
            helper.readableDatabase.rawQuery("SELECT name,department,whitelist,region FROM callers", null).use {
                assertTrue(it.moveToFirst()); assertEquals("old", it.getString(0)); assertEquals("department", it.getString(1)); assertEquals(1, it.getInt(2)); assertEquals(if (oldVersion == 3) "" else "上海", it.getString(3))
            }
        }
        context.deleteDatabase(old.name); context.deleteDatabase(current.name)
    }
}
