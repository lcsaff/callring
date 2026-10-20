package com.greatwall.callring

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.recording.*

import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToolBatchEngineTest {
    private class Fixture {
        val f=ConversationFixtures()
        var mismatchProfileReadback=false
        val source=object:ScopedReadPort {
            override val domains=AiDomain.entries.toSet()
            override fun version(domain:AiDomain)=f.coordinator.capture().values.getValue(if(domain==AiDomain.D11)"preferences"else"core")
            override fun get(domain:AiDomain,key:String,projection:String):SourceRow? = if(domain==AiDomain.D11)SourceRow("current",safeValue(f.appearance) as AiJson.Obj)else f.state.profiles[key]?.let{SourceRow(key,aiObject("number" to aiString(key),"stored" to aiNumber(1),"name" to aiString(if(mismatchProfileReadback)"wrong"else it.name),"department" to aiString(it.department),"position_id" to aiNumber(it.positionId),"whitelisted" to aiBool(it.whitelisted)))}
            override fun query(spec:QuerySpec,after:String?)=SourcePage(spec.filter.number?.let{get(spec.domain,it,spec.projection)}?.let{listOf(it)}?:emptyList(),if(spec.filter.number in f.state.profiles)1 else 0)
        }
        val q=QueryService(listOf(source),revision=f.coordinator::marker)
        val foreground=ForegroundActionService(checkCommand = { _, _ -> true }).also{it.attachHost()}
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
    @Test fun discoveringDisabledToolAllowsOneUnstartedRetryWithNewId()=runBlocking {
        val t=Fixture();val call=ConversationToolCall("disabled","settings_saveAppearance",aiObject("colorMode" to aiString("DARK")))
        val bad=t.engine.execute(listOf(call),setOf("discover_tools")).single()
        assertEquals("ai.tool_not_enabled",bad.error!!.message)
        assertEquals(ColorMode.FOLLOW_SYSTEM,t.f.appearance.colorMode)
        val good=t.engine.execute(listOf(call.copy(id="enabled")),setOf("settings_saveAppearance")).single()
        assertEquals(VerificationStatus.VERIFIED,good.verification)
        assertEquals(1,t.engine.budget.repairs)
    }
    @Test fun explicitContinuationKeepsOriginalTenPlanAndCommittedEight()=runBlocking {
        val t=Fixture();val task=t.plan(10)
        t.engine.execute((1..8).map{t.profile("p$it","31%03d".format(it),task)})
        val committed=t.f.coordinator.marker()
        t.engine.resume(2,{true})
        assertEquals(committed,t.f.coordinator.marker())
        t.engine.execute((9..10).map{t.profile("continued$it","31%03d".format(it),task)})
        assertEquals(10,t.f.state.profiles.size);assertTrue(t.engine.complete)
        assertEquals(10,t.engine.planner.get(task).expectedCount)
    }
    @Test fun thirdRepairOfSameStepIsRejectedBeforeAnyWrite()=runBlocking {
        val t=Fixture()
        listOf("dark","Dark","DARKK").forEachIndexed{i,value->t.engine.execute(listOf(ConversationToolCall("bad-$i","settings_saveAppearance",aiObject("colorMode" to aiString(value)))))}
        val rejected=runCatching{t.engine.execute(listOf(ConversationToolCall("third-repair","settings_saveAppearance",aiObject("colorMode" to aiString("DARK")))))}
        assertTrue(rejected.exceptionOrNull() is BackendFailure)
        assertEquals(2,t.engine.budget.repairs);assertEquals(ColorMode.FOLLOW_SYSTEM,t.f.appearance.colorMode)
    }
    @Test fun fifthRepairAcrossDifferentStepsExhaustsTheTurnBudget()=runBlocking {
        val t=Fixture()
        val names=listOf("root_checkNumber","callers_search","settings_getQuietHours","runtime_getStatus","recordings_listArchives")
        names.forEachIndexed{i,name->
            t.engine.execute(listOf(ConversationToolCall("bad-$i",name,aiObject("unknown" to aiBool(true)))))
            val result=runCatching{t.engine.execute(listOf(ConversationToolCall("fixed-$i",name,if(i==0)aiObject("raw" to aiString("31234"))else aiObject())))}
            if(i==4)assertTrue(result.exceptionOrNull() is BackendFailure)else assertTrue(result.isSuccess)
        }
        assertEquals(4,t.engine.budget.repairs);assertTrue(t.f.state.profiles.isEmpty())
    }
    @Test fun thirtyTwoCallReplyIsCompleteAndThirtyThreeHasZeroExecution()=runBlocking {
        val t=Fixture()
        val calls=(1..33).map{ConversationToolCall("r$it","root_checkNumber",aiObject("raw" to aiString("31234")))}
        assertEquals(32,t.engine.execute(calls.take(32)).size)
        val separate=Fixture();assertTrue(runCatching{separate.engine.execute(calls)}.exceptionOrNull() is BackendFailure);assertTrue(separate.engine.receipts.isEmpty())
    }
    @Test fun maximumLengthProtocolIdDoesNotBreakInternalBatchSteps()=runBlocking {
        val t=Fixture();val task=t.plan(1)
        val call=ConversationToolCall("x".repeat(256),"callers_saveProfiles",aiObject("taskId" to aiString(task),"items" to AiJson.Arr(listOf(aiObject("number" to aiString("31234"),"name" to aiString("合成测试"))))))
        assertEquals(VerificationStatus.VERIFIED,t.engine.execute(listOf(call)).single().verification)
        assertTrue(t.engine.complete)
    }
    @Test fun losingForegroundDuringPreparationPreventsActualPublication()=runBlocking {
        val t=Fixture();val host=ForegroundActionService(checkCommand = { _, _ -> true });val token=host.attachHost()
        fun api(command:CommandContext?=null):CallringUiApi {

            val base=command?.let(t.f.backend::commandApi)?:t.f.backend
            return object:CallringUiApi by base {
                override val settings=object:SettingsApi by base.settings {
                    override suspend fun getAppearance():ApiResult<AppearanceSettings>{val result=base.settings.getAppearance();host.detachHost(token);return result}
                }
                override fun commandApi(context:CommandContext)=api(context)
            }
        }
        val executor=ToolExecutor({api()},t.f.coordinator,host,ForegroundResourceRegistry(),queries=t.q)
        val engine=ToolBatchEngine({api()},executor,t.q,t.f.coordinator,"s","t",1,{true})
        val result=engine.execute(listOf(ConversationToolCall("write","settings_saveAppearance",aiObject("colorMode" to aiString("DARK"))))).single()
        assertEquals(ToolStatus.FAILED,result.status);assertEquals(ColorMode.FOLLOW_SYSTEM,t.f.appearance.colorMode)
    }
    @Test fun alreadyAppliedAppearanceIsVerifiedWithoutInventingACommit()=runBlocking {
        val t=Fixture();t.f.backend.settings.saveAppearance(t.f.appearance.copy(colorMode=ColorMode.DARK)).requiredValue()
        val revision=t.f.coordinator.marker()
        val result=t.engine.execute(listOf(ConversationToolCall("same","settings_saveAppearance",aiObject("colorMode" to aiString("DARK"))))).single()
        assertEquals(VerificationStatus.VERIFIED,result.verification);assertTrue(result.noOp);assertNull(result.commitRevision)
        assertEquals(revision,t.f.coordinator.marker())
    }
    @Test fun committedUnverifiedTargetCannotBeRewrittenUnderNewIdAndArguments()=runBlocking {
        val t=Fixture();t.mismatchProfileReadback=true
        val first=ConversationToolCall("one","callers_saveProfile",aiObject("number" to aiString("31234"),"name" to aiString("first")))
        assertEquals(VerificationStatus.COMMITTED_UNVERIFIED,t.engine.execute(listOf(first)).single().verification)
        val revision=t.f.coordinator.marker()
        val second=t.engine.execute(listOf(first.copy(id="new",arguments=aiObject("number" to aiString("31234"),"name" to aiString("second"))))).single()
        assertEquals(ToolStatus.FAILED,second.status);assertEquals("first",t.f.state.profiles.getValue("31234").name);assertEquals(revision,t.f.coordinator.marker())
    }
    @Test fun aDependencyMayUseARegeneratedIdForACachedCompletedStep()=runBlocking {
        val t=Fixture()
        val first=ConversationToolCall("one","settings_saveAppearance",aiObject("colorMode" to aiString("DARK")))
        t.engine.execute(listOf(first));t.engine.execute(listOf(first.copy(id="alias")))
        val read=ConversationToolCall("read","root_checkNumber",aiObject("raw" to aiString("31234"),"dependsOn" to AiJson.Arr(listOf(aiString("alias")))))
        assertEquals(ToolStatus.READ,t.engine.execute(listOf(read)).single().status)
    }
    @Test fun aPlanCannotWriteMoreTargetsThanItsOriginalCount()=runBlocking {
        val t=Fixture();val task=t.plan(1)
        t.engine.execute(listOf(t.profile("one","31234",task)))
        val extra=t.engine.execute(listOf(t.profile("extra","35678",task))).single()
        assertEquals(ToolStatus.FAILED,extra.status);assertEquals(1,t.f.state.profiles.size)
    }
}
