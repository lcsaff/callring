package com.greatwall.callring

import com.greatwall.callring.application.conversation.execution.*
import com.greatwall.callring.application.conversation.foreground.*
import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.contracts.CallringUiApi
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UpdateToolTest {
    private class Fixture {
        val f=ConversationFixtures()
        var reads=0;var checks=0
        var result:ApiResult<UpdateStatus> = ApiResult.Success(UpdateStatus(AppVersionInfo("0.5.0",17)))
        var cancel=false
        val update=object:AppUpdateApi {
            override suspend fun status():ApiResult<UpdateStatus> { reads++;if(cancel)throw CancellationException();return result }
            override suspend fun check():ApiResult<UpdateStatus> { checks++;return result }
            override suspend fun download():ApiResult<UpdateStatus> = error("AI must never download")
            override suspend fun cancel():ApiResult<UpdateStatus> = error("AI must never cancel")
            override suspend fun install():ApiResult<UpdateStatus> = error("AI must never install")
        }
        val api=object:CallringUiApi by f.backend {
            override val update=this@Fixture.update
            override fun commandApi(context:CommandContext):CallringUiApi=error("Platform reads must not bind business commands")
        }
        val queries=unusedToolQueries()
        val executor=ToolExecutor({api},f.coordinator,ForegroundActionService(checkCommand={_,_->error("No foreground action")}),ForegroundResourceRegistry(),queries=queries,numberMatcher=com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher,documentWriteVerified={false})
        suspend fun execute(name:String)=executor.execute(ConversationToolCall(name,name,aiObject()),ToolExecutionContext("s","t",1,CommandContext("op",f.coordinator.capture(),1){true}))
    }
    @Test fun discoversOnlyTwoStrictNonWritingPlatformObservations() {
        val registry=ToolRegistry()
        val rows=CapabilityCatalog(registry).discover(category="update").tools
        assertEquals(listOf("update_status","update_check"),rows.map{it.name})
        assertEquals(listOf("X12","X13"),rows.map{it.operationCode})
        assertEquals(listOf(84,85),rows.map{it.legacyIndex})
        rows.forEach {
            assertFalse(it.write)
            assertEquals("PLATFORM_READ",it.execution?.name)
            assertEquals("PLATFORM_OBSERVATION",it.readSource?.name)
            assertEquals("PLATFORM_OBSERVATION",it.receiptPolicy.name)
            assertTrue(runCatching { OperationCatalog(registry.definitions.map { row -> if(row.id==it.id)row.copy(readSource=ReadStrategy.SOURCE)else row }) }.isFailure)
            registry.validate(ConversationToolCall("ok",it.name,aiObject()))
            assertTrue(runCatching{registry.validate(ConversationToolCall("bad",it.name,aiObject("download" to aiBool(true))))}.isFailure)
        }
        listOf("update_download","update_cancel","update_install").forEach{assertNull(registry.catalog.find(it))}
    }
    @Test fun statusAndCheckUseFormalApiWithoutHostCommandsOrBusinessReads()=runBlocking {
        val t=Fixture();val before=t.f.coordinator.marker()
        val status=t.execute("update_status")
        assertEquals(ToolStatus.READ,status.status)
        val value=status.value as AiJson.Obj
        assertEquals(aiString("IDLE"),value["phase"])
        assertEquals(AiJson.Null,value["latest"])
        assertEquals(AiJson.Null,value["checkedAtEpochMillis"])
        assertEquals(ToolStatus.READ,t.execute("update_check").status)
        assertEquals(1,t.reads);assertEquals(1,t.checks)
        assertEquals(before,t.f.coordinator.marker())
        assertEquals(0L,t.queries.sourceQueries.get())
        assertTrue(status.changedDomains.isEmpty());assertNull(status.commitRevision)
    }
    @Test fun safelyProjectsEveryPhaseAndNeverExposesDownloadAddressOrFailureDetail()=runBlocking {
        val t=Fixture()
        UpdatePhase.entries.forEach { phase ->
            t.result=ApiResult.Success(UpdateStatus(AppVersionInfo("0.5.0",17),phase,
                AppReleaseInfo("0.6.0",18,123,"Release notes","app.apk",999,"digest","https://secret.example/app.apk"),
                100,999,"update.network_failed","/private/data/failure",456,"https://secret.example/releases"))
            val value=t.execute("update_status").value as AiJson.Obj
            assertEquals(aiString(phase.name),value["phase"])
            assertEquals(aiNumber(100),value["downloadedBytes"])
            assertEquals(aiString("update.network_failed"),value["failureReason"])
            val latest=value["latest"] as AiJson.Obj
            assertEquals(setOf("versionName","versionCode","publishedAtEpochMillis","notes","sizeBytes"),latest.fields.keys)
            assertFalse(value.encode().contains("secret.example"));assertFalse(value.encode().contains("/private"))
            assertFalse(value.fields.containsKey("failureDetail"))
        }
    }
    @Test fun formalFailuresRemainFailuresAndCancellationPropagates()=runBlocking {
        val t=Fixture()
        t.result=ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE,"update.check_failed"))
        val failed=t.execute("update_check")
        assertEquals(ToolStatus.FAILED,failed.status)
        assertEquals("update.check_failed",failed.error?.message)
        t.cancel=true
        assertTrue(runCatching{t.execute("update_status")}.exceptionOrNull() is CancellationException)
    }
    @Test fun productionBatchExecutesPlatformReadsWithoutBusinessLoaders()=runBlocking {
        val t=Fixture();val before=t.f.coordinator.marker()
        val engine=ToolBatchEngine({t.api},t.executor,t.queries,t.f.coordinator,"s","t",1,{true})
        val receipts=engine.execute(listOf(ConversationToolCall("status","update_status",aiObject()),ConversationToolCall("check","update_check",aiObject())))
        assertEquals(listOf(ToolStatus.READ,ToolStatus.READ),receipts.map{it.status})
        assertEquals(1,t.reads);assertEquals(1,t.checks)
        assertEquals(0L,t.queries.sourceQueries.get())
        assertEquals(before,t.f.coordinator.marker())
        assertTrue(engine.complete)
    }
}
