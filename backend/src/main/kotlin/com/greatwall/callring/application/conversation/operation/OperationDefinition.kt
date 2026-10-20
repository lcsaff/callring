package com.greatwall.callring.application.conversation.operation

import com.greatwall.callring.contracts.ai.AiJson

enum class ExecutionStrategy { SOURCE, COMPUTATION, CATALOG, PLAN, DIRECT_WRITE, PROFILE_BATCH }
enum class ScopeStrategy { DIRECT, PROFILE_CHILDREN }
enum class ReadStrategy { SOURCE, PURE_COMPUTATION, CATALOG, IN_MEMORY_PLAN }
enum class VerificationStrategy { DIRECT, CHILD_PERSISTED_READBACK }
enum class ConfirmationPolicy { NO_SEPARATE_CONFIRM, AUTHORITATIVE_TARGET_CONFIRM, LOCAL_CREDENTIAL_EDITOR, LOCAL_CONNECTION_COPY_CONFIRM, AUTHORITATIVE_TARGET_DOUBLE_CONFIRM }

data class OperationDefinition(
    val id:OperationId,
    val name:String,
    val operationCode:String,
    val legacyIndex:Int,
    val displayOrder:Int,
    val schema:AiJson.Obj,
    val description:String,
    val runtimeDefaults:String,
    val write:Boolean,
    val sharedApi:String,
    val receiptPolicy:ReceiptPolicy,
    val confirmation:ConfirmationPolicy?,
    val execution:ExecutionStrategy?,
    val scope:ScopeStrategy?,
    val verification:VerificationStrategy?,
    val readSource:ReadStrategy?,
)
