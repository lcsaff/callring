package com.greatwall.callring

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.execution.*
import com.greatwall.callring.application.conversation.foreground.*
import com.greatwall.callring.application.conversation.query.*
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.ports.query.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.calling.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NumberRuleToolTest {
    @Test fun appendedNumberRuleToolsHaveStrictBaseAndVerifiedWritePolicy() {
        val catalog=OperationCatalog()
        val get=catalog.find("settings_getNumberRule")!!
        val save=catalog.find("settings_saveNumberRule")!!
        assertEquals(82,get.legacyIndex);assertEquals("X10",get.operationCode)
        assertEquals(83,save.legacyIndex);assertEquals("X11",save.operationCode)
        assertEquals(ExecutionStrategy.SOURCE,get.execution)
        assertEquals(ExecutionStrategy.DIRECT_WRITE,save.execution)
        assertEquals(ReceiptPolicy.PERSISTED_READBACK,save.receiptPolicy)
        assertEquals(ConfirmationPolicy.NO_SEPARATE_CONFIRM,save.confirmation)
        val registry=ToolRegistry(catalog)
        val good=ConversationToolCall("save",save.name,aiObject("base" to aiObject("prefix" to aiString("3"),"length" to aiNumber(5),"revision" to aiNumber(0)),"prefix" to aiString("8"),"length" to aiNumber(6)))
        registry.validate(good)
        for(field in listOf("base","prefix","length")) assertTrue(runCatching{registry.validate(good.copy(arguments=AiJson.Obj(good.arguments.fields-field)))}.isFailure)
        assertTrue(runCatching{registry.validate(good.copy(arguments=AiJson.Obj(good.arguments.fields+("regex" to aiString(".*")))))}.isFailure)
        assertEquals(ExecutionStrategy.SOURCE,catalog[OperationId.ROOT_CHECK_NUMBER].execution)
    }

    private class Fixture(val matcher:NumberMatcher=PrefixLengthNumberMatcher) {
        val f=ConversationFixtures(matcher)
        var overrideRule:NumberRuleSettings?=null
        var reads=0
        var active=true
        val q=QueryService(listOf(object:ScopedReadPort {
            override val domains=setOf(AiDomain.D10,AiDomain.D11)
            override fun version(domain:AiDomain)=f.coordinator.marker()
            override fun query(spec:QuerySpec,after:String?)=SourcePage(emptyList(),0)
            override fun get(domain:AiDomain,key:String,projection:String):SourceRow {
                reads++
                return SourceRow(key,if(domain==AiDomain.D10)aiObject("numberRule" to safeValue(overrideRule?:f.core.numberRuleSettings)) else safeValue(f.appearance) as AiJson.Obj)
            }
        }),clock={1234L},revision=f.coordinator::marker)
        val foreground=ForegroundActionService(checkCommand={_,_->true}).also{it.attachHost()}
        val executor=ToolExecutor({f.backend},f.coordinator,foreground,ForegroundResourceRegistry(),queries=q,numberMatcher=matcher,documentWriteVerified={false})
        val engine=ToolBatchEngine({f.backend},executor,q,f.coordinator,"session","turn",1,{active})
        fun save(id:String="save",base:NumberRuleSettings=NumberRuleSettings("3",5,0),prefix:String="8",length:Int=6)=ConversationToolCall(id,"settings_saveNumberRule",aiObject("base" to safeValue(base),"prefix" to aiString(prefix),"length" to aiNumber(length)))
        suspend fun execute(call:ConversationToolCall)=engine.execute(listOf(call)).single()
    }
    @Test fun rootUsesObservedRuleAndInjectedMatcherWithOneRealStampedRead()=runBlocking {
        var seen:NumberRule?=null
        val t=Fixture(NumberMatcher { number,rule->seen=rule;number=="91234"&&rule.prefix=="9" })
        t.overrideRule=NumberRuleSettings("9",5,4)
        val call=ConversationToolCall("root","root_checkNumber",aiObject("raw" to aiString("9 1234")))
        val r=t.execute(call)
        assertEquals(setOf("normalized","validProfileNumber","matchesRule"),(r.value as AiJson.Obj).fields.keys)
        assertEquals(aiBool(true),(r.value as AiJson.Obj)["matchesRule"])
        assertEquals(NumberRule("9",5),seen)
        assertEquals(1,t.reads)
        val observed=t.q.get(AiDomain.D10,"configuration").requiredValue()
        val evidence=(r.verificationEvidence as AiJson.Arr).values.single() as AiJson.Obj
        assertEquals(observed.stamp.json(),evidence["stamp"])
        assertEquals(0L,t.q.snapshotObservations()[AiDomain.D10 to "configuration"])
    }
    @Test fun getReturnsFormalThreeFieldsAndPageRuleChangeConflicts()=runBlocking {
        val t=Fixture()
        val read=t.execute(ConversationToolCall("read","settings_getNumberRule",aiObject()))
        assertEquals(safeValue(NumberRuleSettings("3",5,0)),read.value)
        t.f.backend.settings.saveNumberRule(SaveNumberRule(NumberRuleSettings("3",5,0),"7",5)).requiredValue()
        val result=t.execute(t.save())
        assertEquals(ToolStatus.FAILED,result.status)
        assertEquals(ErrorCode.CONFLICT,result.error?.code)
        assertEquals(NumberRuleSettings("7",5,1),t.f.core.numberRuleSettings)
    }
    @Test fun rootObservationAlsoProtectsSaveButAppearanceDoesNotConflict()=runBlocking {
        val t=Fixture()
        t.execute(ConversationToolCall("root","root_checkNumber",aiObject("raw" to aiString("31234"))))
        t.f.backend.settings.saveAppearance(t.f.appearance.copy(colorMode=ColorMode.DARK)).requiredValue()
        val r=t.execute(t.save())
        assertEquals(VerificationStatus.VERIFIED,r.verification)
        assertEquals(NumberRuleSettings("8",6,1),t.f.core.numberRuleSettings)
        val marker=t.f.coordinator.marker()
        val repeated=t.execute(t.save("repeated"))
        assertEquals(VerificationStatus.VERIFIED,repeated.verification)
        assertEquals(marker,t.f.coordinator.marker())
    }
    @Test fun durableMismatchStaysCommittedAndRetryDoesNotWriteAgain()=runBlocking {
        val t=Fixture();t.overrideRule=NumberRuleSettings("8",6,99)
        val r=t.execute(t.save())
        assertEquals(ToolStatus.COMMITTED,r.status)
        assertEquals(VerificationStatus.COMMITTED_UNVERIFIED,r.verification)
        assertEquals(NumberRuleSettings("8",6,1),t.f.core.numberRuleSettings)
        val marker=t.f.coordinator.marker()
        t.overrideRule=null
        val repeated=t.execute(t.save("again"))
        assertEquals(VerificationStatus.COMMITTED_UNVERIFIED,repeated.verification)
        t.engine.resume(2,{true})
        t.engine.execute(listOf(ConversationToolCall("get","settings_getNumberRule",aiObject())))
        assertEquals(VerificationStatus.VERIFIED,t.engine.receipts.first{it.toolCallId=="save"&&it.verification==VerificationStatus.VERIFIED}.verification)
        assertEquals(marker,t.f.coordinator.marker())
    }
    @Test fun cancelledCommandAndStorageFailureDoNotPublishOrPersist()=runBlocking {
        val t=Fixture();val marker=t.f.coordinator.marker()
        val command=CommandContext("cancel",t.f.coordinator.capture(),1){false}
        val cancelled=t.executor.execute(t.save(),ToolExecutionContext("session","turn",1,command))
        assertEquals(ToolStatus.FAILED,cancelled.status);assertEquals("ai.cancelled",cancelled.error?.message)
        assertEquals(NumberRuleSettings("3",5,0),t.f.core.numberRuleSettings)
        assertEquals(marker,t.f.coordinator.marker())
        t.f.core.failCommit=true
        val failed=t.execute(t.save("failure"))
        assertEquals(ToolStatus.FAILED,failed.status)
        assertEquals(NumberRuleSettings("3",5,0),t.f.core.numberRuleSettings)
        assertEquals(marker,t.f.coordinator.marker())
        t.f.core.failCommit=false
        val repeated=t.execute(t.save("same-failed"))
        assertEquals(ToolStatus.FAILED,repeated.status)
        assertEquals(marker,t.f.coordinator.marker())
        val retry=ToolBatchEngine({t.f.backend},t.executor,t.q,t.f.coordinator,"session","new-turn",2,{true})
        assertEquals(VerificationStatus.VERIFIED,retry.execute(listOf(t.save("retry"))).single().verification)
    }
    @Test fun durableNoOpHasVerifiedReadbackWithoutPublishing()=runBlocking {
        val t=Fixture();val marker=t.f.coordinator.marker()
        val r=t.execute(t.save(prefix="3",length=5))
        assertTrue(r.noOp);assertEquals(VerificationStatus.VERIFIED,r.verification)
        assertEquals(marker,t.f.coordinator.marker())
    }
    @Test fun rootObservationRejectsInterveningRulePageSave()=runBlocking {
        val t=Fixture()
        t.execute(ConversationToolCall("root","root_checkNumber",aiObject("raw" to aiString("31234"))))
        t.f.backend.settings.saveNumberRule(SaveNumberRule(NumberRuleSettings("3",5,0),"7",5)).requiredValue()
        assertEquals(ErrorCode.CONFLICT,t.execute(t.save()).error?.code)
        assertEquals(NumberRuleSettings("7",5,1),t.f.core.numberRuleSettings)
    }
}
