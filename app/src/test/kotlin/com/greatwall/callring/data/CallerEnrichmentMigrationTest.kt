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
            db.version = 3
        }
        CallringDatabase(context, name).use { helper ->
            assertEquals(4, helper.writableDatabase.version)
            helper.readableDatabase.rawQuery("SELECT name,department,whitelist,self_reported_position,region,identity_metadata FROM callers", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("李明", row.getString(0)); assertEquals("财务部", row.getString(1)); assertEquals(1, row.getInt(2))
                assertEquals("", row.getString(3)); assertEquals("", row.getString(4)); assertEquals("{}", row.getString(5))
            }
        }
        context.deleteDatabase(name)
    }

    @Test fun freshDatabaseContainsEnrichmentFields() {
        val context = RuntimeEnvironment.getApplication()
        val name = "enrichment-fresh.db"
        context.deleteDatabase(name)
        CallringDatabase(context, name).use { helper ->
            val db = helper.writableDatabase
            assertEquals(4, db.version)
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
