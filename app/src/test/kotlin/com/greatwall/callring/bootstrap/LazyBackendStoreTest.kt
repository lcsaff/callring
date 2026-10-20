package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.BackendState
import org.junit.Assert.*
import org.junit.Test

class LazyBackendStoreTest {
    @Test
    fun constructionDoesNotOpenDatabaseAndCloseNeverReopensIt() {
        var opened = 0
        var closed = 0
        val state = BackendState()
        val store = StorageResources {
            opened++
            object : BackendStore, com.greatwall.callring.application.ports.store.CoreStorageResource {
                override val legacy: BackendStore get() = this
                override val fullSnapshot: com.greatwall.callring.application.ports.store.FullSnapshotReader get() = error("unused")
                override val sessions: com.greatwall.callring.application.ports.store.CoreUnitOfWork get() = error("unused")
                override val scoped: ScopedReadPort get() = error("unused")
                override fun stateLoadCount() = 0L
                override fun <T> read(block: (BackendState) -> T) = block(state)

                override fun <T> transaction(block: (BackendState) -> T) = block(state)

                override fun close() {
                    closed++
                }
            }
        }
        assertEquals(0, opened)
        store.read { it.nextCallId }
        store.read { it.nextCallId }
        assertEquals(1, opened)
        store.close()
        store.close()
        assertEquals(1, closed)
        assertThrows(BackendFailure::class.java) { store.read { it.nextCallId } }
        assertEquals(1, opened)
    }

    @Test
    fun closingUnusedFixtureDoesNotCreateItsDatabase() {
        val store = StorageResources { error("must_not_open") }
        store.close()
    }
}
