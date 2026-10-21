package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.conversation.execution.json
import com.greatwall.callring.application.conversation.query.json
import com.greatwall.callring.data.recording.AndroidRecordingStore
import com.greatwall.callring.data.recording.RecordingCipher

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.conversation.query.QueryService
import com.greatwall.callring.application.mapping.requiredValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],manifest=Config.NONE)
class RecordingScopedQueryTest {
    private val coordinator = com.greatwall.callring.application.coordination.BusinessRevisionCoordinator()
    private val cipher=object:RecordingCipher{override fun encrypt(value:String)=value;override fun decrypt(value:String)=value}
    @Test fun newArchiveMetadataExcludesTextButEveryAnalysisFieldIsReachable()=runBlocking {
        AndroidRecordingStore(RuntimeEnvironment.getApplication(),"scoped_recording", cipher, recordingVersion = { coordinator.capture().values.getValue("recording") }).use{store->
            val call=com.greatwall.callring.application.recording.RecordingRegistrationService(store, coordinator).registerIncoming("one","999",1,null, null);val old=store.get(call.id)!!
            val transcript="头😀\"\\\n".repeat(3000)+"尾哨兵"
            val next=old.copy(generation=old.generation+1,transcript=transcript,audioPath="/private/audio",analysis=CallAnalysis(name="合成",summary="全文分析",actionItems=listOf("动作一","动作二")))
            assertTrue(com.greatwall.callring.application.recording.RecordingArchiveCommands(store, store, coordinator).compareAndSet(call.id,old.generation,next, null, false))
            val api=QueryService(listOf(store.scopedReads()))
            val page=api.query(QuerySpec(AiDomain.D16)).requiredValue()
            assertTrue(page.complete);assertFalse(page.json().encode().contains("/private/audio"));assertFalse(page.json().encode().contains("尾哨兵"))
            val fields=page.items.single()["textFields"] as AiJson.Obj
            assertTrue(fields.fields.keys.containsAll(listOf("transcript","analysis.name","analysis.summary","analysis.actionItems[0]","analysis.actionItems[1]")))
            val reference=((fields["transcript"] as AiJson.Obj)["reference"] as AiJson.Str).value
            var cursor:String?=null;val text=StringBuilder()
            do{val chunk=api.readText(reference,cursor,511).requiredValue();text.append(chunk.text);cursor=chunk.nextCursor}while(cursor!=null)
            assertEquals(transcript,text.toString())
            assertTrue(com.greatwall.callring.application.recording.RecordingArchiveCommands(store, store, coordinator).compareAndSet(call.id,next.generation,next.copy(generation=next.generation+1,transcript="新一代"), null, false))
            assertEquals("ai.cursor_stale",(api.readText(reference) as ApiResult.Failure).error.message)
            listOf("events","links","deleted").forEach{projection->api.query(QuerySpec(AiDomain.D15,projection)).requiredValue()}
            val event=api.query(QuerySpec(AiDomain.D15,"events")).requiredValue().items.single()
            assertEquals(call.id,event["callId"].long())
        }
    }
}
