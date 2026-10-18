package com.greatwall.callring.data

import com.greatwall.callring.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CoreSnapshotSourceTest {
    @Test fun completeCaptureIncludesAllPagesAndPersistedAuditTail() {
        SqliteBackendStore(RuntimeEnvironment.getApplication(), "complete_source.db").use { store ->
            store.transaction { s ->
                (1..251).forEach { s.profiles[it.toString()] = ProfileRecord(it.toString(), name = "caller-$it") }
                s.pendingAudits += AuditRecord("import", "2020", "full-audit-tail", 1)
            }
            store.captureFull { state, audits ->
                assertEquals(251, state.profiles.size)
                assertEquals("caller-251", state.profiles["251"]!!.name)
                assertEquals("full-audit-tail", audits.last().raw)
                assertTrue(state.pendingAudits.isEmpty())
            }
        }
    }
    @Test fun oversizedTextFailsBeforeDeliveringAnyState() {
        SqliteBackendStore(RuntimeEnvironment.getApplication(), "large_source.db").use { store ->
            store.transaction { it.profiles["1"] = ProfileRecord("1", name = "x".repeat(8 * 1024 * 1024)) }
            var delivered = false
            assertTrue(runCatching { store.captureFull { _, _ -> delivered = true } }.isFailure)
            assertFalse(delivered)
        }
    }
}
