package com.greatwall.callring

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.QueryService
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.requiredValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueryObservationIsolationTest {
    @Test fun numericProfileNumbersKeepLeadingZeroAndPlusIdentity()=runBlocking {
        var revision=0L
        val source=object:ScopedReadPort {
            override val domains=setOf(AiDomain.D01,AiDomain.D02)
            override fun version(domain:AiDomain)=0L
            override fun get(domain:AiDomain,key:String,projection:String)=SourceRow(key,aiObject())
            override fun query(spec:QuerySpec,after:String?)=SourcePage(emptyList(),0)
        }
        val q=QueryService(listOf(source),revision={revision})
        q.get(AiDomain.D01,"031234").requiredValue();revision=10
        q.get(AiDomain.D01,"31234").requiredValue();revision=20
        q.get(AiDomain.D01,"+31234").requiredValue()
        assertEquals(0L,q.observedRevision(AiDomain.D01,"031234"))
        assertEquals(10L,q.observedRevision(AiDomain.D01,"31234"))
        assertEquals(20L,q.observedRevision(AiDomain.D01,"+31234"))
        q.get(AiDomain.D02,"00000000000000000001").requiredValue()
        assertEquals(20L,q.observedRevision(AiDomain.D02,"1"))
    }
}
