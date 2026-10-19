package com.greatwall.callring

import com.greatwall.callring.application.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OperationScopePlannerTest {
    private val c=BusinessRevisionCoordinator()
    private val q=QueryService(listOf(object:ScopedReadPort {
        override val domains=AiDomain.entries.toSet()
        override fun version(domain:AiDomain)=0L
        override fun get(domain:AiDomain,key:String,projection:String)=SourceRow(key,aiObject())
        override fun query(spec:QuerySpec,after:String?)=SourcePage(emptyList(),0)
    }),revision=c::marker)
    @Test fun internalPreflightDoesNotReplaceOldModelObservation()=runBlocking {
        q.get(AiDomain.D11,"current").requiredValue()
        c.publishScopes(setOf("appearance:colorMode"))
        val p=OperationScopePlanner(q,c)
        val scopes=p.prepare(ConversationToolCall("a","settings_saveAppearance",aiObject("colorMode" to aiString("DARK"))),37)
        assertEquals(0L,scopes["appearance:colorMode"])
        assertEquals(0L,q.observedRevision(AiDomain.D11,"current"))
        assertFalse(c.unchanged(scopes.keys,scopes.values.first()))
    }
    @Test fun profilesAndAppearanceHaveOnlyTheirAffectedFieldScopes()=runBlocking {
        val p=OperationScopePlanner(q,c)
        val scopes=p.prepare(ConversationToolCall("a","callers_saveProfile",aiObject("number" to aiString("31234"),"name" to aiString("test"))),7)
        assertEquals(setOf("profile:31234:exists","profile:31234:name"),scopes.keys)
        assertEquals(setOf("appearance:colorMode"),p.prepare(ConversationToolCall("b","settings_saveAppearance",aiObject("colorMode" to aiString("DARK"))),37).keys)
    }
    @Test fun ownCommitAdvancesOnlyItsScopesAndUsesCommittedMarker()=runBlocking {
        val p=OperationScopePlanner(q,c)
        val call=ConversationToolCall("a","settings_saveAppearance",aiObject("colorMode" to aiString("DARK")))
        val first=p.prepare(call,37)
        val ctx=CommandContext("own",c.capture(),1,expectedScopes=first){true}
        c.guard(ctx){c.publishScopes(first.keys);c.record(ctx)}
        c.publishScopes(setOf("appearance:colorMode"))
        p.committed("own",first.keys)
        val second=p.prepare(call.copy(id="b"),37)
        assertEquals(c.committedMarker("own"),second.values.single())
        assertFalse(c.unchanged(second.keys,second.values.single()))
    }
    @Test fun everyLegacyWriteHasExplicitScopeContract() {
        val legacy = ToolRegistry().definitions.filter{it.write}.map{it.index}.toSet()
    assertTrue(OperationScopePlanner.writeOperations.containsAll(legacy))
    assertEquals(legacy + setOf(81), OperationScopePlanner.writeOperations)
    }
}
