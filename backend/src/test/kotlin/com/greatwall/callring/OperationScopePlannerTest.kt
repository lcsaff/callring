package com.greatwall.callring

import com.greatwall.callring.application.conversation.execution.OperationScopePlanner
import com.greatwall.callring.application.conversation.execution.b
import com.greatwall.callring.application.conversation.operation.ToolRegistry
import com.greatwall.callring.application.conversation.query.QueryService
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.ports.query.ScopedReadPort
import com.greatwall.callring.application.ports.query.SourcePage
import com.greatwall.callring.application.ports.query.SourceRow

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.ai.*
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
        c.publishCore(setOf("appearance:colorMode"), com.greatwall.callring.application.coordination.ChangeImpact(emptyMap()))
        val p=OperationScopePlanner(q,c)
        val scopes=p.prepare(ConversationToolCall("a","settings_saveAppearance",aiObject("colorMode" to aiString("DARK"))),OperationId.SETTINGS_SAVE_APPEARANCE)
        assertEquals(0L,scopes["appearance:colorMode"])
        assertEquals(0L,q.observedRevision(AiDomain.D11,"current"))
        assertFalse(c.unchanged(scopes.keys,scopes.values.first()))
    }
    @Test fun profilesAndAppearanceHaveOnlyTheirAffectedFieldScopes()=runBlocking {
        val p=OperationScopePlanner(q,c)
        val scopes=p.prepare(ConversationToolCall("a","callers_saveProfile",aiObject("number" to aiString("31234"),"name" to aiString("test"))),OperationId.CALLERS_SAVE_PROFILE)
        assertEquals(setOf("profile:31234:exists","profile:31234:name"),scopes.keys)
        assertEquals(setOf("appearance:colorMode"),p.prepare(ConversationToolCall("b","settings_saveAppearance",aiObject("colorMode" to aiString("DARK"))),OperationId.SETTINGS_SAVE_APPEARANCE).keys)
    }
    @Test fun ownCommitAdvancesOnlyItsScopesAndUsesCommittedMarker()=runBlocking {
        val p=OperationScopePlanner(q,c)
        val call=ConversationToolCall("a","settings_saveAppearance",aiObject("colorMode" to aiString("DARK")))
        val first=p.prepare(call,OperationId.SETTINGS_SAVE_APPEARANCE)
        val ctx=CommandContext("own",c.capture(),1,expectedScopes=first){true}
        c.guard(ctx){c.publishCore(first.keys, com.greatwall.callring.application.coordination.ChangeImpact(emptyMap()));c.record(ctx)}
        c.publishCore(setOf("appearance:colorMode"), com.greatwall.callring.application.coordination.ChangeImpact(emptyMap()))
        p.committed("own",first.keys)
        val second=p.prepare(call.copy(id="b"),OperationId.SETTINGS_SAVE_APPEARANCE)
        assertEquals(c.committedMarker("own"),second.values.single())
        assertFalse(c.unchanged(second.keys,second.values.single()))
    }
    @Test fun everyLegacyWriteHasExplicitScopeContract() {
        val direct = ToolRegistry().definitions.filter{it.scope==ScopeStrategy.DIRECT}.map{it.id}.toSet()
    assertTrue(OperationScopePlanner.writeOperations.containsAll(direct))
    assertEquals(direct, OperationScopePlanner.writeOperations)
    }
    @Test fun numberRuleUsesOnlyExactRuleScopeAndPreservesDisclosedD10Revision()=runBlocking {
        q.get(AiDomain.D10,"configuration").requiredValue()
        c.publishCore(setOf("numberRule"),com.greatwall.callring.application.coordination.ChangeImpact(emptyMap()))
        val scopes=OperationScopePlanner(q,c).prepare(ConversationToolCall("save","settings_saveNumberRule",aiObject()),OperationId.SETTINGS_SAVE_NUMBER_RULE)
        assertEquals(mapOf("numberRule" to 0L),scopes)
        assertEquals(0L,q.observedRevision(AiDomain.D10,"configuration"))
    }
}
