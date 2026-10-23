package com.greatwall.callring.data

import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.data.codec.NumberRuleCodec
import com.greatwall.callring.contracts.settings.NumberRuleSettings
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
            assertEquals(NumberRuleSettings("3",5,0),store.sessions.readNumberRule { it.numberRule() })
        }
        context.openOrCreateDatabase(name,0,null).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM settings WHERE key='number_rule'",null).use { c -> c.moveToFirst(); assertEquals(0,c.getInt(0)) }
        }
        SqliteCoreStorageResource(context,name).use { store ->
            store.sessions.writeNumberRule { it.saveNumberRule(NumberRuleSettings("8",6,1)) }
        }
        SqliteCoreStorageResource(context,name).use { store ->
            assertEquals(NumberRuleSettings("8",6,1),store.sessions.readNumberRule { it.numberRule() })
            assertThrows(IllegalStateException::class.java) {
                store.sessions.writeNumberRule { it.saveNumberRule(NumberRuleSettings("7",6,2)); error("rollback") }
            }
            assertEquals(NumberRuleSettings("8",6,1),store.sessions.readNumberRule { it.numberRule() })
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
        val value = NumberRuleSettings("",30,Long.MAX_VALUE)
        assertEquals(value,NumberRuleCodec.decode(NumberRuleCodec.encode(value)))
    }
}
