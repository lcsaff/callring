package com.greatwall.callring

import com.greatwall.callring.application.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ConversationLazyTest {
    @Test fun ordinaryChatMakesOneRequestWithDiscoveryOnlyAndZeroSourceReads()=runBlocking {
        val f=ConversationFixtures();val q=QueryService(emptyList())
        var requests=0;var offered=emptyList<String>()
        val client=object:ConversationClient {
            override fun encode(connection:AiConnection,request:ConversationRequest):ByteArray {offered=request.tools.map{it.name};assertEquals(AiJson.Null,request.snapshot);return byteArrayOf()}
            override suspend fun exchange(connection:AiConnection,encoded:ByteArray):ConversationReply {requests++;return ConversationReply.Final("你好")}
            override fun cancel()=Unit
        }
        val service=ConversationService({f.backend},q,{RecordingSettings(analysis=AiConnection(url="https://example.com",apiKey="fixture",model="fixture"))},f.coordinator,{client})
        try{service.send(service.state.value.sessionId,"你好");withTimeout(5000){service.state.first{!it.busy}};assertEquals(1,requests);assertEquals(listOf("discover_tools"),offered);assertEquals(0,q.sourceQueries.get());assertEquals(ConversationPhase.COMPLETED,service.state.value.phase)}finally{service.close()}
    }
}
