package com.greatwall.callring.data

import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.data.codec.NumberRuleCodec
import com.greatwall.callring.contracts.settings.NumberRuleSettings
import com.greatwall.callring.contracts.settings.NumberRuleEntry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NumberRuleStorageTest {
    @Test fun missingKeyDoesNotWriteAndSavedRuleSurvivesReopen() {
        val context = RuntimeEnvironment.getApplication(); val name = "number-rule-reopen.db"
        SqliteCoreStorageResource(context,name).use { store ->
            assertEquals(NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("3", 5)), 0),store.sessions.readNumberRule { it.numberRule() })
        }
        context.openOrCreateDatabase(name,0,null).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM settings WHERE key='number_rule'",null).use { c -> c.moveToFirst(); assertEquals(0,c.getInt(0)) }
        }
        SqliteCoreStorageResource(context,name).use { store ->
            store.sessions.writeNumberRule { it.saveNumberRule(NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("8", 6)), 1)) }
        }
        SqliteCoreStorageResource(context,name).use { store ->
            assertEquals(NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("8", 6)), 1),store.sessions.readNumberRule { it.numberRule() })
            assertThrows(IllegalStateException::class.java) {
                store.sessions.writeNumberRule { it.saveNumberRule(NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("7", 6)), 2)); error("rollback") }
            }
            assertEquals(NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("8", 6)), 1),store.sessions.readNumberRule { it.numberRule() })
        }
    }
    @Test fun presentNullMalformedUnsupportedAndNonIntegerFieldsNeverDefault() {
        val context = RuntimeEnvironment.getApplication(); val name = "number-rule-invalid.db"
        SqliteCoreStorageResource(context,name).close()
        val invalid = listOf<String?>(null,"bad","{}", "null", "{version:1,prefix:3,length:5,revision:0}", "{version:1,prefix:'3',length:5,revision:0}",
            """{"version":1,"prefix":"3","length":5,"revision":0,}""",
            """{"version":2,"prefix":"3","length":5,"revision":0}""",
            """{"version":1,"prefix":"3","length":5.5,"revision":0}""",
            """{"version":1,"prefix":"3","length":"5","revision":0}""",
            """{"version":1,"prefix":"3","length":5,"revision":-1}""",
            """{"version":1,"prefix":"８","length":5,"revision":0}""")
        for (raw in invalid) {
            context.openOrCreateDatabase(name,0,null).use { db ->
                db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES('number_rule',?)", arrayOf(raw))
            }
            SqliteCoreStorageResource(context,name).use { store ->
                assertThrows(Exception::class.java) { store.sessions.readNumberRule { it.numberRule() } }
            }
        }
    }
    @Test fun codecRoundTripsEmptyPrefixAndMaximumRevisionExactly() {
        val value = NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("", 30)), Long.MAX_VALUE)
        assertEquals(value,NumberRuleCodec.decode(NumberRuleCodec.encode(value)))
    }
    @Test fun legacyLoadsOneEntryWithRevisionAndDoesNotRewriteStorage() {
        val context = RuntimeEnvironment.getApplication(); val name = "number-rule-legacy.db"
        val legacy = """{"version":1,"prefix":"08","length":6,"revision":47}"""
        SqliteCoreStorageResource(context,name).close()
        context.openOrCreateDatabase(name,0,null).use { db ->
            db.execSQL("INSERT INTO settings(key,value) VALUES('number_rule',?)",arrayOf(legacy))
        }
        repeat(2) {
            SqliteCoreStorageResource(context,name).use { store ->
                assertEquals(NumberRuleSettings(listOf(NumberRuleEntry("08",6)),47),store.sessions.readNumberRule { it.numberRule() })
            }
        }
        context.openOrCreateDatabase(name,0,null).use { db ->
            db.rawQuery("SELECT value FROM settings WHERE key='number_rule'",null).use { c -> c.moveToFirst();assertEquals(legacy,c.getString(0)) }
        }
    }

    @Test fun versionTwoMultipleAndEmptyListsRoundTripAndReopen() {
        val context = RuntimeEnvironment.getApplication(); val name = "number-rule-multiple.db"
        for ((index,rows) in listOf(listOf(NumberRuleEntry("3",5),NumberRuleEntry("08",6),NumberRuleEntry("",30)),emptyList()).withIndex()) {
            val value = NumberRuleSettings(rows,index+1L)
            val raw = NumberRuleCodec.encode(value)
            assertEquals(2,org.json.JSONObject(raw).getInt("version"))
            assertFalse(org.json.JSONObject(raw).has("prefix"))
            assertEquals(value,NumberRuleCodec.decode(raw))
            SqliteCoreStorageResource(context,name).use { store -> store.sessions.writeNumberRule { it.saveNumberRule(value) } }
            SqliteCoreStorageResource(context,name).use { store -> assertEquals(value,store.sessions.readNumberRule { it.numberRule() }) }
        }
    }

    @Test fun strictVersionTwoRejectsEveryMalformedEntryAndEnvelope() {
        val invalid = listOf(
            """{"version":3,"rules":[],"revision":0}""",
            """{"version":1,"rules":[],"revision":0}""",
            """{"version":2,"rules":[],"revision":0,"prefix":"3","length":5}""",
            """{"version":2,"rules":[],"revision":-1}""",
            """{"version":2,"rules":{},"revision":0}""",
            """{"version":2,"rules":[null],"revision":0}""",
            """{"version":2,"rules":[{"prefix":"3","length":5},{"prefix":"8","length":0}],"revision":0}""",
            """{"version":2,"rules":[{"prefix":"３","length":5}],"revision":0}""",
            """{"version":2,"rules":[{"prefix":"123","length":2}],"revision":0}""",
            """{"version":2,"rules":[{"prefix":"3","length":"5"}],"revision":0}""",
            """{"version":2,"rules":[{"prefix":"3","length":5.0}],"revision":0}""",
            """{"version":2,"rules":[{"prefix":"3","prefix":"8","length":5}],"revision":0}""",
            """{"version":2,"rules":[{"prefix":"3","length":5,"extra":1}],"revision":0}""",
            """{"version":2,"rules":[{"prefix":"3"}],"revision":0}""",
            """{"version":2,"rules":[],"rules":[],"revision":0}""",
            """{"version":2,"rules":[],"revision":0} {}"""
        )
        for (raw in invalid) assertThrows(raw,Exception::class.java) { NumberRuleCodec.decode(raw) }
    }
}
