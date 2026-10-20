package com.greatwall.callring.bootstrap

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.QueryService
import com.greatwall.callring.application.ports.SourceRow
import com.greatwall.callring.application.requiredValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScopedQueryAssemblyTest {
    @Test fun recordingConfigurationDisclosesProviderObservationWithoutInventingARevision()=runBlocking {
        var name="folder";var access="GRANTED"
        val source=ValueScopedReadSource(setOf(AiDomain.D13),{0L}){sequenceOf(SourceRow("current",aiObject("folderName" to aiString(name),"folderAccess" to aiString(access))))}
        val query=QueryService(listOf(source))
        val before=query.get(AiDomain.D13,"current").requiredValue()
        name="renamed";access="DENIED"
        val after=query.get(AiDomain.D13,"current").requiredValue()
        assertEquals("live_probe",before.stamp.consistency)
        assertEquals(before.stamp.sourceVersion,after.stamp.sourceVersion)
        assertEquals("renamed",after.value["folderName"].string())
        assertEquals("DENIED",after.value["folderAccess"].string())
    }
    @Test fun safeViewsAreLazyAndPagingNeverCallsAnotherDomain()=runBlocking {
        var appearances=0;var ai=0
        val source=ValueScopedReadSource(setOf(AiDomain.D11,AiDomain.D14),{0L}){spec->
            if(spec.domain==AiDomain.D11){appearances++;sequenceOf(SourceRow("current",aiObject("colorMode" to aiString("DARK"))))}
            else {ai++;sequenceOf(SourceRow("current",aiObject("keyConfigured" to aiBool(true))))}
        }
        val query=QueryService(listOf(source));assertEquals(0,appearances+ai)
        query.query(QuerySpec(AiDomain.D11)).requiredValue();assertEquals(1,appearances);assertEquals(0,ai)
        query.get(AiDomain.D14,"current").requiredValue();assertEquals(1,ai)
    }
}
