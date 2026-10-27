package com.greatwall.callring.data

import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.domain.model.AuditRecord
import com.greatwall.callring.domain.model.ProfileRecord

import com.greatwall.callring.domain.*
import com.greatwall.callring.application.conversation.query.projectCoreSnapshot
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.runtime.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CoreSnapshotSourceTest {
    @Test fun completeAndLocalNumberRuleMatchDefaultsAndSavedRevision() {
        SqliteCoreStorageResource(RuntimeEnvironment.getApplication(), "complete-rule.db").use { store ->
            for (rule in listOf(
                NumberRuleSettings(listOf(NumberRuleEntry("3",5)),0),
                NumberRuleSettings(listOf(NumberRuleEntry("8",6),NumberRuleEntry("001",7)),1), NumberRuleSettings(emptyList(),2))) {
                if (rule.revision > 0) store.sessions.writeNumberRule { it.saveNumberRule(rule) }
                val local = store.scoped.get(AiDomain.D10, "current", "default")!!.value["numberRule"]
                val full = projectCoreSnapshot(
                    store.fullSnapshot.capture(), 0,
                    RuntimeStatus(emptyMap(),
                        Audibility.AUDIBLE,
                        PhoneActivity.IDLE,
                        PlaybackStatus(
                            PlaybackMode.NONE,
                            PlaybackState.IDLE), false, 0),
                    AppearanceSettings(
                        ThemeSelection.PRESET, "default", null,
                        ColorMode.FOLLOW_SYSTEM),
                    RingtoneSettings(false,
                        AudioSource.BUILT_IN, "", 1))
                val expected = aiObject(
                    "rules" to AiJson.Arr(rule.rules.map { aiObject("prefix" to aiString(it.prefix), "length" to aiNumber(it.length)) }),
                    "revision" to aiNumber(rule.revision))
                assertEquals(expected, local)
                assertEquals(expected, (full.getValue("D10") as AiJson.Obj)["numberRule"])
            }
        }
    }

    @Test fun malformedStoredRuleFailsLocalAndCompleteSources() {
        val context = RuntimeEnvironment.getApplication()
        val name = "complete-rule-malformed.db"
        SqliteCoreStorageResource(context, name).close()
        for (raw in listOf<String?>(null, "bad", "{}", "{version:1,prefix:'8',length:6,revision:1}",
            """{"version":2,"prefix":"8","length":6,"revision":1}""")) {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES('number_rule',?)", arrayOf(raw))
            }
            SqliteCoreStorageResource(context, name).use { store ->
                assertThrows(Exception::class.java) { store.scoped.get(AiDomain.D10, "current", "default") }
                assertThrows(Exception::class.java) { store.scoped.query(QuerySpec(AiDomain.D10), null) }
                assertThrows(Exception::class.java) { store.fullSnapshot.capture() }
            }
        }
    }

    @Test
    fun completeCaptureIncludesAllPagesAndPersistedAuditTail() {
        SqliteCoreStorageResource(RuntimeEnvironment.getApplication(), "complete_source.db").use { store ->
            store.fixtureSql { s ->
                (1..251).forEach {
                    s.seedCaller(ProfileRecord(it.toString(), name = "caller-$it"))
                }
                s.seedAudit(AuditRecord("import", "2020", "full-audit-tail", 1))
            }
            val loads = store.completeCaptures.get()
            store.fullSnapshot.capture().let { state ->
                val audits = state.audits
                assertEquals(loads + 1, store.completeCaptures.get())
                assertEquals(251, state.profiles.size)
                assertThrows(UnsupportedOperationException::class.java) {
                    (state.profiles as MutableMap).clear()
                }
                assertEquals("caller-251", state.profiles["251"]!!.name)
                assertEquals("full-audit-tail", audits.last().raw)
            }
        }
    }

    @Test
    fun oversizedTextFailsBeforeDeliveringAnyState() {
        SqliteCoreStorageResource(RuntimeEnvironment.getApplication(), "large_source.db").use { store ->
            store.fixtureSql {
                it.seedCaller(ProfileRecord("1", name = "x".repeat(8 * 1024 * 1024)))
            }
            var delivered = false
            assertTrue(
                runCatching { store.fullSnapshot.capture().also { delivered = true } }.isFailure,
            )
            assertFalse(delivered)
        }
    }
}
