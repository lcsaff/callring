package com.greatwall.callring

import com.greatwall.callring.application.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToolBatchEngineTest {
    private class Fixture {
        val f=ConversationFixtures()
        val source=object:ScopedReadPort {
            override val domains=AiDomain.entries.toSet()
            override fun version(domain:AiDomain)=f.coordinator.capture().values.getValue(if(domain==AiDomain.D11)"preferences"else"core")
            override fun get(domain:AiDomain,key:String,projection:String):SourceRow? = if(domain==AiDomain.D11)SourceRow("current",safeValue(f.appearance) as AiJson.Obj)else f.state.profiles[key]?.let{SourceRow(key,aiObject("number" to aiString(key),"stored" to aiNumber(1),"name" to aiString(it.name),"department" to aiString(it.department),"position_id" to aiNumber(it.positionId),"whitelisted" to aiBool(it.whitelisted)))}
            override fun query(spec:QuerySpec,after:String?)=SourcePage(spec.filter.number?.let{get(spec.domain,it,spec.projection)}?.let{listOf(it)}?:emptyList(),if(spec.filter.number in f.state.profiles)1 else 0)
        }
        val q=QueryService(listOf(source),revision=f.coordinator::marker)
        val foreground=ForegroundActionService().also{it.attachHost()}
        val executor=ToolExecutor({f.backend},f.coordinator,foreground,ForegroundResourceRegistry(),queries=q)
        val engine=ToolBatchEngine({f.backend},executor,q,f.coordinator,"session","turn",1,{true})
        fun profile(id:String,number:String,task:String)=ConversationToolCall(id,"callers_saveProfile",aiObject("number" to aiString(number),"name" to aiString("合成测试-$number"),"taskId" to aiString(task)))
        suspend fun plan(n:Int):String {
            val r=engine.execute(listOf(ConversationToolCall("plan","task_prepare",aiObject("kind" to aiString("CREATE_PROFILES"),"expectedCount" to aiNumber(n.toLong()))))).single()
            return (r.value as AiJson.Obj)["taskId"].string()
        }
    }
    @Test fun tenWritesInOneResponseAreVerifiedWithoutEightCallTruncation()=runBlocking {
        val t=Fixture();val task=t.plan(10)
        val receipts=t.engine.execute((1..10).map{t.profile("p$it","31%03d".format(it),task)})
        assertEquals(10,receipts.count{it.verification==VerificationStatus.VERIFIED})
        assertEquals(10,t.f.state.profiles.size)
        assertTrue(t.engine.complete)
        assertTrue(t.engine.budget.work<=64)
    }
    @Test fun eightThenTwoAndRegeneratedIdsDoNotCreateExtraWrites()=runBlocking {
        val t=Fixture();val task=t.plan(10)
        t.engine.execute((1..8).map{t.profile("p$it","31%03d".format(it),task)})
        assertFalse(t.engine.complete)
        t.engine.execute((9..10).map{t.profile("p$it","31%03d".format(it),task)})
        val revision=t.f.coordinator.marker()
        val duplicate=t.engine.execute(listOf(t.profile("new-id","31001",task))).single()
        assertEquals(VerificationStatus.VERIFIED,duplicate.verification)
        assertEquals("new-id",duplicate.toolCallId)
        assertEquals(revision,t.f.coordinator.marker())
        assertTrue(t.engine.complete)
    }
    @Test fun planCannotOverwriteAnExistingCaller()=runBlocking {
        val t=Fixture();t.f.backend.callers.saveProfile(SaveCallerProfile("31234","existing","",null,false))
        val task=t.plan(1)
        val r=t.engine.execute(listOf(t.profile("p","31234",task))).single()
        assertEquals(ToolStatus.FAILED,r.status)
        assertEquals("existing",t.f.state.profiles.getValue("31234").name)
        assertFalse(t.engine.complete)
    }
    @Test fun correctableEnumGetsSameWireIdFeedbackAndNewIdRepair()=runBlocking {
        val t=Fixture()
        val bad=t.engine.execute(listOf(ConversationToolCall("bad","settings_saveAppearance",aiObject("colorMode" to aiString("dark"))))).single()
        assertEquals("bad",bad.toolCallId);assertEquals(ToolStatus.FAILED,bad.status)
        val good=t.engine.execute(listOf(ConversationToolCall("good","settings_saveAppearance",aiObject("colorMode" to aiString("DARK"))))).single()
        assertEquals(VerificationStatus.VERIFIED,good.verification)
        assertEquals(1,t.engine.budget.repairs)
    }
    @Test fun batchWrapperPreservesAllTenChildReceipts()=runBlocking {
        val t=Fixture();val task=t.plan(10)
        val r=t.engine.execute(listOf(ConversationToolCall("batch","callers_saveProfiles",aiObject("taskId" to aiString(task),"items" to AiJson.Arr((1..10).map{aiObject("number" to aiString("31%03d".format(it)),"name" to aiString("合成测试-$it"))}))))).single()
        assertEquals("batch",r.toolCallId)
        assertEquals(10,((r.value as AiJson.Obj)["items"] as AiJson.Arr).values.size)
        assertTrue(t.engine.complete)
    }
    @Test fun dependencyCycleRejectsWholeReplyBeforeWriting()=runBlocking {
        val t=Fixture();val task=t.plan(2)
        val a=t.profile("a","31234",task).let{it.copy(arguments=AiJson.Obj(it.arguments.fields+("dependsOn" to AiJson.Arr(listOf(aiString("b"))))))}
        val b=t.profile("b","35678",task).let{it.copy(arguments=AiJson.Obj(it.arguments.fields+("dependsOn" to AiJson.Arr(listOf(aiString("a"))))))}
        assertTrue(runCatching{t.engine.execute(listOf(a,b))}.exceptionOrNull() is BackendFailure)
        assertTrue(t.f.state.profiles.isEmpty())
    }
}
