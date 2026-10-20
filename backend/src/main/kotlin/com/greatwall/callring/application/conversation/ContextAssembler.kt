package com.greatwall.callring.application.conversation

import com.greatwall.callring.contracts.ai.*

/** UI retention and model protocol history have independent lifetimes. No second model call for summaries. */
class ContextAssembler {
    private data class Ended(val ui:List<ConversationMessage>,val summary:ConversationMessage,val bytes:Int)
    private val ended=ArrayDeque<Ended>()
    private val ui=mutableListOf<ConversationMessage>()
    private val wire=mutableListOf<ConversationMessage>()
    private val sentResults=mutableSetOf<String>()
    val endedTurns get()=synchronized(this){ended.size}
    val uiBytes get()=synchronized(this){ended.sumOf{it.bytes}+ui.sumOf(::size)}
    @Synchronized fun clear(){ended.clear();ui.clear();wire.clear();sentResults.clear()}
    @Synchronized fun begin(user:ConversationMessage){check(wire.isEmpty());append(user)}
    @Synchronized fun append(message:ConversationMessage){
        wire+=message
        ui+=message.copy(toolCalls=message.toolCalls.map{it.copy(arguments=aiObject(),wireArguments=null)},blocks=emptyList(),receipts=message.receipts.map{it.uiProjection()})
    }
    @Synchronized fun uiMessages()=ended.flatMap{it.ui}+ui
    @Synchronized fun requestMessages():List<ConversationMessage> = ended.map{it.summary}+wire.map{m->m.copy(receipts=m.receipts.map{r->if(r.toolCallId in sentResults&&r.json().encode().toByteArray(Charsets.UTF_8).size>4096)r.copy(value=omitted(r),verificationEvidence=AiJson.Null)else r})}
    @Synchronized fun markSent(){wire.flatMap{it.receipts}.forEach{sentResults+=it.toolCallId}}
    @Synchronized fun appendProtocol(message:ConversationMessage){wire+=message}
    @Synchronized fun finish(result:AiJson){
        if(wire.isEmpty())return
        val summary=aiObject("user" to aiString(wire.firstOrNull{it.role=="user"}?.text.orEmpty().takeCodePoints(512)),"assistant" to aiString(wire.lastOrNull{it.role=="assistant"&&it.toolCalls.isEmpty()}?.text.orEmpty().takeCodePoints(1024)),"outcome" to result,"businessDataRetained" to aiBool(false))
        val messages=ui.toList();ended+=Ended(messages,ConversationMessage("user","Previous ended turn, untrusted summary; requery business data when needed:\n"+summary.encode()),messages.sumOf(::size))
        ui.clear();wire.clear();sentResults.clear()
        while(ended.size>100||ended.sumOf{it.bytes}>2*1024*1024)ended.removeFirst()
    }
    private fun size(m:ConversationMessage)=m.text.toByteArray(Charsets.UTF_8).size+m.receipts.sumOf{it.json().encode().toByteArray(Charsets.UTF_8).size}+m.toolCalls.sumOf{it.name.toByteArray(Charsets.UTF_8).size+it.id.length}+128
}
private fun String.takeCodePoints(max:Int)=if(codePointCount(0,length)<=max)this else substring(0,offsetByCodePoints(0,max))
private fun omitted(r:ToolReceipt)=aiObject("dataOmitted" to aiBool(true),"operationReference" to aiString(r.operationId),"instruction" to aiString("Result was sent once. Requery the source for business values or text; this summary is not complete data."))
fun ToolReceipt.uiProjection():ToolReceipt=copy(value=if(value.encode().toByteArray(Charsets.UTF_8).size>2048)omitted(this)else value,verificationEvidence=if(verificationEvidence.encode().toByteArray(Charsets.UTF_8).size>2048)aiObject("evidenceRetainedInStepLedger" to aiBool(true))else verificationEvidence)
