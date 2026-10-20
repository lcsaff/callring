package com.greatwall.callring

import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScopedToolReaderTest {
    @Test fun legacyReadCoverageAndSourcePagination()=runBlocking {
        val requests=mutableListOf<QuerySpec>()
        val q=QueryService(listOf(object:ScopedReadPort {
            override val domains=AiDomain.entries.toSet()
            override fun version(domain:AiDomain)=0L
            override fun query(spec:QuerySpec,after:String?):SourcePage {requests+=spec;return SourcePage(emptyList(),0)}
        }))
        val r=ScopedToolReader(q)
        val call=ConversationToolCall("a","callers_search",aiObject("text" to aiString("test"),"limit" to aiNumber(20)))
        val value=r.read(3,call.arguments)
        assertEquals(QuerySpec(AiDomain.D01,filter=QueryFilter(text="test"),limit=20),requests.single())
        assertEquals(true,(value as AiJson.Obj)["complete"].boolean())
        assertEquals(ToolRegistry().definitions.filter{!it.write&&it.index!=1}.map{it.index}.toSet(),ScopedToolReader.legacyReads)
    }
    @Test fun UIPageIndexCannotMasqueradeAsCompleteSourcePage()=runBlocking {
        val q=QueryService(emptyList());val r=ScopedToolReader(q)
        val result=runCatching{r.read(3,aiObject("page" to aiObject("pageIndex" to aiNumber(1))))}
        assertTrue(result.exceptionOrNull() is BackendFailure)
    }
}
