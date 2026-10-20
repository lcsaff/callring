package com.greatwall.callring.application.conversation

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.application.ports.BackendFailure
import java.security.MessageDigest

/** Local comparison only: neither the digest nor source text is included in wire/UI evidence. */
class ScopedTextFingerprint private constructor(private val digest:ByteArray,val blank:Boolean) {
    fun matches(other:ScopedTextFingerprint)=blank==other.blank&&MessageDigest.isEqual(digest,other.digest)
    override fun toString()="ScopedTextFingerprint([private])"
    companion object {
        suspend fun read(q:QueryService,row:AiJson.Obj?,field:String,value:AiJson):ScopedTextFingerprint {
            val digest=MessageDigest.getInstance("SHA-256");var end=digest.digest();var started=false
            fun append(text:String){
                val begin=if(started)0 else text.indexOfFirst{!it.isWhitespace()}.takeIf{it>=0}?:return
                started=true;val last=text.indexOfLast{!it.isWhitespace()}
                if(last>=begin){digest.update(text.substring(begin,last+1).toByteArray(Charsets.UTF_8));end=(digest.clone() as MessageDigest).digest();digest.update(text.substring(last+1).toByteArray(Charsets.UTF_8))}
                else digest.update(text.substring(begin).toByteArray(Charsets.UTF_8))
            }
            if(value is AiJson.Str)append(value.value)
            else {
                val ref=(((row?.get("textFields") as? AiJson.Obj)?.get(field) as? AiJson.Obj)?.get("reference") as? AiJson.Str)?.value
                if(ref!=null){var cursor:String?=null;var chunks=0
                    do{if(chunks++>=32)throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.verification_text_capacity"));val part=q.readText(ref,cursor,16384).requiredValue();append(part.text);cursor=part.nextCursor}while(cursor!=null)
                }else require(value==AiJson.Null)
            }
            return ScopedTextFingerprint(end,!started)
        }
    }
}
data class AdoptionExpectation(val number:String,val generation:AiJson,val name:ScopedTextFingerprint,val department:ScopedTextFingerprint)
