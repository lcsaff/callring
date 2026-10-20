package com.greatwall.callring.application.conversation

/** Checked inventory shared by discovery, execution, source reads, scopes and receipt policy. */
enum class ReceiptPolicy { SOURCE_READ, COMPUTATION, PERSISTED_READBACK, QUEUED, FOREGROUND_OBSERVATION, DOCUMENT_READBACK }
data class OperationContract(val index:Int,val name:String,val write:Boolean,val sharedApi:String,val receiptPolicy:ReceiptPolicy)
fun ToolRegistry.operationContracts():List<OperationContract> = definitions.map{d->
    val policy=when {
        d.index==1->ReceiptPolicy.COMPUTATION
        !d.write->ReceiptPolicy.SOURCE_READ
        d.index==10->ReceiptPolicy.DOCUMENT_READBACK
        d.index in setOf(46,56)->ReceiptPolicy.QUEUED
        d.index in setOf(42,43,53,58,59,66,67,68,69,70,71,72)->ReceiptPolicy.FOREGROUND_OBSERVATION
        else->ReceiptPolicy.PERSISTED_READBACK
    }
    val shared=when(d.index){7->"edits.profile";37->"edits.appearance";51->"aiSettings.getRecordingView + aiSettings.getAiView";52->"aiSettings.patchCombined";53->"aiSettings.testSlot";63->"adoptIdentity";64->"aiSettings.getAiView";65->"aiSettings.patchAi";66->"aiSettings.patchAi via foreground";72->"aiSettings.copyConnection via foreground";in 67..71->"ForegroundActionService";else->d.name.replace('_','.')}
    check(if(d.write)d.index in OperationScopePlanner.writeOperations else d.index==1||d.index in ScopedToolReader.legacyReads)
    OperationContract(d.index,d.name,d.write,shared,policy)
}
