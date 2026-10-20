package com.greatwall.callring.data

import android.content.ContextWrapper
import android.content.res.AssetManager
import android.database.sqlite.SQLiteDatabase
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33], manifest=Config.NONE)
class DatabaseInitializationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun freshSchemaStoresOnlyTheOriginalScheduleCalendarAndExactRawAudit() {
        val raw = context.assets.open("holidays-2026.json").use(WorkConfigCodec::readLimited)
        val calendar = WorkConfigCodec.calendar(raw)
        val before = System.currentTimeMillis()
        CallringDatabase(context, "initial-facts.db").use { helper ->
            val db = helper.writableDatabase
            assertEquals(3, db.version)
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM settings"))
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM config_versions"))
            assertEquals(0L, db.scalar("SELECT effective FROM config_versions"))
            assertEquals(1L, db.scalar("SELECT seq FROM sqlite_sequence WHERE name='config_versions'"))
            db.rawQuery("SELECT id,effective,snapshot FROM config_versions",null).use { c ->
                assertTrue(c.moveToFirst())
                val expected = com.greatwall.callring.domain.model.defaultWorkSchedule()
                // The shipped persistent format records all seven weekdays, including empty weekends.
                assertEquals(expected.copy(weekly = expected.weekly + mapOf(6 to emptyList(), 7 to emptyList())),
                    WorkConfigCodec.decode(c.getString(2), c.getLong(0), c.getLong(1)))
                assertFalse(c.moveToNext())
            }
            db.rawQuery("SELECT year,source,published,version FROM calendar_years",null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(calendar.metadata.year,c.getInt(0)); assertEquals(calendar.metadata.source,c.getString(1))
                assertEquals(calendar.metadata.published,c.getString(2)); assertEquals(calendar.metadata.version,c.getString(3))
                assertFalse(c.moveToNext())
            }
            assertEquals(calendar.holidays.size.toLong()+calendar.makeupDates.size, db.scalar("SELECT COUNT(*) FROM calendar_days"))
            db.rawQuery("SELECT date,working,name FROM calendar_days ORDER BY date", null).use { c ->
                while(c.moveToNext()) {
                    val date=java.time.LocalDate.parse(c.getString(0))
                    if(c.getInt(1)==1) { assertTrue(date in calendar.makeupDates); assertEquals(LegacyValueCodec.MAKEUP_DAY_NAME,c.getString(2)) }
                    else assertEquals(calendar.holidays.getValue(date),c.getString(2))
                }
            }
            db.rawQuery("SELECT kind,target,raw,created FROM calendar_audit",null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("calendar",c.getString(0)); assertEquals("2026",c.getString(1)); assertEquals(raw,c.getString(2))
                assertTrue(c.getLong(3) in before..System.currentTimeMillis()); assertFalse(c.moveToNext())
            }
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM pragma_foreign_key_check"))
            assertThrows(android.database.sqlite.SQLiteException::class.java) { db.execSQL("DELETE FROM calendar_audit") }
        }
    }
    @Test fun assetFailureRollsBackSchemaAndConfigurationThenNormalOpenCanRetry() {
        val name="initial-rollback.db"
        val broken = object: ContextWrapper(context) {
            override fun getAssets(): AssetManager = throw IOException("fixture.asset_failure")
        }
        CallringDatabase(broken,name).use { helper ->
            assertThrows(IOException::class.java) { helper.writableDatabase }
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(0,db.version)
            assertEquals(0L,db.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('config_versions','calendar_years','calendar_audit')"))
        }
        CallringDatabase(context,name).use { helper ->
            assertEquals(3,helper.writableDatabase.version)
            assertEquals(1L,helper.writableDatabase.scalar("SELECT COUNT(*) FROM calendar_audit"))
        }
    }
}
