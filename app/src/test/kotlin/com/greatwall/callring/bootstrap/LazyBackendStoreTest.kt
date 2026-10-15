package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.ports.*
import org.junit.Assert.*
import org.junit.Test

class LazyBackendStoreTest {
    @Test
    fun constructionDoesNotOpenDatabaseAndCloseNeverReopensIt() {
        var opened = 0
        var closed = 0
        val state = BackendState()
        val store = LazyBackendStore {
            opened++
            object : BackendStore, AutoCloseable {
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
        val store = LazyBackendStore { error("must_not_open") }
        store.close()
    }
}
