package com.greatwall.callring.data

import com.greatwall.callring.data.database.CallringDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CallerEnrichmentMigrationTest {
    @Test fun versionThreeUpgradePreservesOriginalProfileAndAddsEmptyFields() {
        val context = RuntimeEnvironment.getApplication()
        val name = "enrichment-old.db"
        context.deleteDatabase(name)
        CallringDatabase(context, name).use { helper ->
            helper.writableDatabase.execSQL("INSERT INTO callers(number,name,department,whitelist) VALUES('31234','李明','财务部',1)")
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            stripCallerEnrichmentForFixture(db)
            com.greatwall.callring.data.stripDirectionForFixture(db)
            db.version = 3
        }
        CallringDatabase(context, name).use { helper ->
            assertEquals(6, helper.writableDatabase.version)
            helper.readableDatabase.rawQuery("SELECT name,department,whitelist,self_reported_position,region,identity_metadata FROM callers", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("李明", row.getString(0)); assertEquals("财务部", row.getString(1)); assertEquals(1, row.getInt(2))
                assertEquals("", row.getString(3)); assertEquals("", row.getString(4)); assertEquals("{}", row.getString(5))
            }
        }
        context.deleteDatabase(name)
    }

    @Test fun versionFourUpgradeAddsVisibleNumberIndexAndPreservesProfile() {
        val context = RuntimeEnvironment.getApplication()
        val name = "call-history-v4.db"
        context.deleteDatabase(name)
        CallringDatabase(context, name).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO callers(number,name,region) VALUES('31234','李明','上海')")
            db.execSQL("DROP INDEX IF EXISTS calls_visible_number_time")
            com.greatwall.callring.data.stripDirectionForFixture(db)
            db.version = 4
        }
        CallringDatabase(context, name).use { helper ->
            val db = helper.writableDatabase
            db.rawQuery("SELECT sql FROM sqlite_master WHERE name='calls_visible_number_time'", null).use {
                assertTrue("Visible-number index must be added to the old database", it.moveToFirst())
                assertTrue(it.getString(0).contains("(number,time DESC,id DESC) WHERE hidden=0"))
            }
            db.rawQuery("SELECT name,region FROM callers WHERE number='31234'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("李明", it.getString(0))
                assertEquals("上海", it.getString(1))
            }
        }
        context.deleteDatabase(name)
    }

    @Test fun genuineVersionFiveUpgradeRetainsExistingNumberHistoryIndex() {
        val context = RuntimeEnvironment.getApplication()
        val name = "direction-v5.db"
        context.deleteDatabase(name)
        CallringDatabase(context,name).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO callers(number,name) VALUES('31234','Retained')")
            com.greatwall.callring.data.stripDirectionForFixture(db)
            assertEquals(1L,android.database.DatabaseUtils.longForQuery(db,"SELECT count(*) FROM sqlite_master WHERE name='calls_visible_number_time'",null))
            db.version = 5
        }
        CallringDatabase(context,name).use { helper ->
            assertEquals(6,helper.writableDatabase.version)
            assertEquals("Retained",android.database.DatabaseUtils.stringForQuery(helper.readableDatabase,"SELECT name FROM callers WHERE number='31234'",null))
            assertEquals(1L,android.database.DatabaseUtils.longForQuery(helper.readableDatabase,"SELECT count(*) FROM sqlite_master WHERE name='calls_visible_number_time'",null))
        }
    }

    @Test fun freshDatabaseContainsEnrichmentFields() {
        val context = RuntimeEnvironment.getApplication()
        val name = "enrichment-fresh.db"
        context.deleteDatabase(name)
        CallringDatabase(context, name).use { helper ->
            val db = helper.writableDatabase
            assertEquals(6, db.version)
            db.execSQL("INSERT INTO callers(number,name,department) VALUES('31234','李明','财务部')")
            db.rawQuery("SELECT self_reported_position,region,identity_metadata FROM callers WHERE number='31234'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("", it.getString(0))
                assertEquals("", it.getString(1))
                assertEquals("{}", it.getString(2))
            }
        }
        context.deleteDatabase(name)
    }
}
