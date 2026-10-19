package com.greatwall.callring

import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChunkRedactionTest {
    @Test fun keysSpanningChunkEdgesAreFullyRedactedIncludingEncodedForms()=runBlocking {
        val key="key-\"\\\t-SECRET-123"
        val encoded=key.map{"%%%02x".format(it.code)}.joinToString("")
        val short=AiJson.quote(key).drop(1).dropLast(1)
        val text="😀prefix$key/$encoded/$short/tail"
        val port=object:ScopedReadPort {
            override val domains=setOf(AiDomain.D16)
            override fun version(domain:AiDomain)=0L
            override fun query(spec:QuerySpec,after:String?)=SourcePage(listOf(SourceRow("1",aiObject(),mapOf("transcript" to text.codePointCount(0,text.length).toLong()))),1)
            override fun text(domain:AiDomain,key:String,field:String,start:Long,maxChars:Int):SourceText {
                val total=text.codePointCount(0,text.length);val end=minOf(total,start.toInt()+maxChars)
                return SourceText(text.substring(text.offsetByCodePoints(0,start.toInt()),text.offsetByCodePoints(0,end)),start,end.toLong(),total.toLong())
            }
        }
        val q=QueryService(listOf(port),redactor={SecretRedactor(listOf(key))})
        val row=q.query(QuerySpec(AiDomain.D16)).requiredValue().items.single()
        val ref=(((row["textFields"] as AiJson.Obj)["transcript"] as AiJson.Obj)["reference"]).string()
        var cursor:String?=null;val output=StringBuilder()
        do{val chunk=q.readText(ref,cursor,5).requiredValue();output.append(chunk.text);cursor=chunk.nextCursor}while(cursor!=null)
        assertFalse(output.contains("SECRET"));assertFalse(output.contains("%53"));assertTrue(output.contains("tail"));assertTrue(output.contains("[redacted]"))
        assertEquals("😀prefix///tail",output.toString().replace("[redacted]",""))
    }
}
