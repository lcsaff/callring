package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.ports.*
import org.junit.Assert.*
import org.junit.Test

class LazyStorageResourcesTest {
    @Test
    fun constructionDoesNotOpenDatabaseAndCloseNeverReopensIt() {
        var opened = 0
        var closed = 0
        val store = StorageResources {
            opened++
            object : com.greatwall.callring.application.ports.store.CoreStorageResource {
                override val fullSnapshot: com.greatwall.callring.application.ports.store.FullSnapshotReader get() = error("unused")
                override val sessions: com.greatwall.callring.application.ports.store.CoreUnitOfWork get() = error("unused")
                override val scoped = object : ScopedReadPort {
                    override val domains = setOf(com.greatwall.callring.contracts.ai.AiDomain.D10)
                    override fun version(domain: com.greatwall.callring.contracts.ai.AiDomain) = 0L
                    override fun query(spec: com.greatwall.callring.contracts.ai.QuerySpec, after: String?) = SourcePage(emptyList(), 0)
                }
                override fun narrowAccessCounts() = emptyMap<String, Long>()
                override fun close() {
                    closed++
                }
            }
        }
        assertEquals(0, opened)
        store.scopedReads(com.greatwall.callring.application.BusinessRevisionCoordinator()).query(com.greatwall.callring.contracts.ai.QuerySpec(com.greatwall.callring.contracts.ai.AiDomain.D10), null)
        store.scopedReads(com.greatwall.callring.application.BusinessRevisionCoordinator()).query(com.greatwall.callring.contracts.ai.QuerySpec(com.greatwall.callring.contracts.ai.AiDomain.D10), null)
        assertEquals(1, opened)
        store.close()
        store.close()
        assertEquals(1, closed)
        assertThrows(BackendFailure::class.java) { store.scopedReads(com.greatwall.callring.application.BusinessRevisionCoordinator()).query(com.greatwall.callring.contracts.ai.QuerySpec(com.greatwall.callring.contracts.ai.AiDomain.D10), null) }
        assertEquals(1, opened)
    }

    @Test
    fun closingUnusedFixtureDoesNotCreateItsDatabase() {
        val store = StorageResources { error("must_not_open") }
        store.close()
    }
}
