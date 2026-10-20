package com.greatwall.callring

import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.conversation.operation.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class OperationCatalogTest {
    private val rows = OperationCatalog.defaultDefinitions()
    private fun rejects(change:(MutableList<OperationDefinition>)->Unit) {
        val changed=rows.toMutableList().also(change)
        assertTrue("Invalid inventory must fail at startup",runCatching { OperationCatalog(changed) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun duplicateProtocolAndBusinessIdentitiesFailAtStartup() {
        rejects { it[1]=it[1].copy(id=it[0].id) }
        rejects { it[1]=it[1].copy(name=it[0].name) }
        rejects { it[1]=it[1].copy(operationCode=it[0].operationCode) }
        rejects { it[1]=it[1].copy(legacyIndex=it[0].legacyIndex) }
        rejects { it[1]=it[1].copy(displayOrder=it[0].displayOrder) }
    }
    @Test fun incompleteInventoryAndMissingExecutionAreRejected() {
        rejects { it.removeFirst() }
        rejects { it[0]=it[0].copy(execution=null) }
    }
    @Test fun writesRequireExplicitScopeConfirmationAndVerification() {
        val pos=rows.indexOfFirst { it.id==OperationId.CALLERS_SAVE_PROFILE }
        rejects { it[pos]=it[pos].copy(scope=null) }
        rejects { it[pos]=it[pos].copy(confirmation=null) }
        rejects { it[pos]=it[pos].copy(verification=null) }
        rejects { it[pos]=it[pos].copy(execution=ExecutionStrategy.SOURCE) }
    }
    @Test fun readsRequireMatchingRealSourceOrComputationDeclaration() {
        val pos=rows.indexOfFirst { it.id==OperationId.CALLERS_SEARCH }
        rejects { it[pos]=it[pos].copy(readSource=null) }
        rejects { it[pos]=it[pos].copy(readSource=ReadStrategy.PURE_COMPUTATION) }
        rejects { it[pos]=it[pos].copy(receiptPolicy=ReceiptPolicy.PERSISTED_READBACK) }
    }
    @Test fun compositeWriteHasChildScopeAndChildPersistedReadback() {
        val c=OperationCatalog();val batch=c[OperationId.CALLERS_SAVE_PROFILES]
        assertEquals(49,rows.count { it.write })
        assertEquals(48,rows.count { it.scope==ScopeStrategy.DIRECT })
        assertEquals(29,rows.count { it.readSource==ReadStrategy.SOURCE })
        assertEquals(1,rows.count { it.readSource==ReadStrategy.PURE_COMPUTATION })
        assertEquals(1,rows.count { it.readSource==ReadStrategy.CATALOG })
        assertEquals(1,rows.count { it.readSource==ReadStrategy.IN_MEMORY_PLAN })
        assertEquals(ScopeStrategy.PROFILE_CHILDREN,batch.scope)
        assertEquals(ReceiptPolicy.BATCH_CHILD_PERSISTED_READBACK,batch.receiptPolicy)
        val pos=rows.indexOf(batch)
        rejects { it[pos]=batch.copy(scope=ScopeStrategy.DIRECT) }
        rejects { it[pos]=batch.copy(verification=VerificationStrategy.DIRECT) }
    }
    @Test fun shuffledCatalogKeepsIdentityAndDiscoveryWithoutImplicitExtensionFields() {
        repeat(12) { seed ->
            val catalog=OperationCatalog(rows.shuffled(Random(seed)))
            val registry=ToolRegistry(catalog)
            assertEquals(rows,registry.definitions)
            rows.forEach { assertEquals(it,registry.catalog.find(it.name));assertEquals(it,catalog[it.id]) }
            val source=registry.catalog[OperationId.DATA_QUERY].schema["properties"] as com.greatwall.callring.contracts.ai.AiJson.Obj
            assertFalse(source.fields.containsKey("dependsOn"));assertFalse(source.fields.containsKey("taskId"))
        }
    }
}
