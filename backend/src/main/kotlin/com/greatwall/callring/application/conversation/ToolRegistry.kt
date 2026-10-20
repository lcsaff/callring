package com.greatwall.callring.application.conversation

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.operation.*

class ToolRegistry(val catalog:OperationCatalog = OperationCatalog()) {
    val definitions:List<OperationDefinition> get()=catalog.discovery
    private fun invalid(path:String,reason:String):Nothing = throw com.greatwall.callring.application.ports.BackendFailure(ApiError(ErrorCode.VALIDATION,"ai.invalid_tool_arguments",fieldErrors=mapOf(path to reason)))
    fun validate(call: ConversationToolCall): OperationDefinition {
        if(call.id.isBlank()||call.id.length>256)invalid("$","ai.invalid_call_id")
        call.argumentError?.let { throw com.greatwall.callring.application.ports.BackendFailure(it) }
        val definition = catalog.find(call.name) ?: throw com.greatwall.callring.application.ports.BackendFailure(ApiError(ErrorCode.VALIDATION,"ai.unknown_tool"))
        validateValue(call.arguments, definition.schema,"$")
        return definition
    }
    private fun validateValue(value: AiJson, schema: AiJson.Obj,path:String) {
        val type = schema["type"]
        val allowed = if(type is AiJson.Arr) type.values.map { it.string() } else listOf(type.string())
        if(value == AiJson.Null) { if("null" !in allowed)invalid(path,"ai.null_not_allowed"); return }
        when {
            "object" in allowed -> {
                if(value !is AiJson.Obj)invalid(path,"ai.expected_object")
                val properties = schema["properties"] as? AiJson.Obj ?: aiObject()
                val names = schema["propertyNames"] as? AiJson.Obj
                val additional = schema["additionalProperties"] as? AiJson.Obj
                if(value.fields.keys.any { it !in properties.fields && (names == null || (names["enum"] as AiJson.Arr).values.none { name -> name.string() == it }) })invalid(path,"ai.extra_argument")
                ((schema["required"] as? AiJson.Arr)?.values ?: emptyList()).forEach{if(it.string() !in value.fields)invalid("$path.${it.string()}","ai.missing_argument")}
                value.fields.forEach { (key, item) -> validateValue(item, (properties[key] as? AiJson.Obj ?: additional) ?: error("invalid schema"),"$path.$key") }
            }
            "array" in allowed -> { if(value !is AiJson.Arr || value.values.size > 20000)invalid(path,"ai.expected_bounded_array"); value.values.forEachIndexed { i,v->validateValue(v, schema["items"] as AiJson.Obj,"$path[$i]") } }
            "integer" in allowed -> if(runCatching{value.long()}.isFailure)invalid(path,"ai.expected_integer")
            "boolean" in allowed -> if(value !is AiJson.Bool)invalid(path,"ai.expected_boolean")
            "string" in allowed -> {
                if(value !is AiJson.Str)invalid(path,"ai.expected_string")
                val enum = schema["enum"]
                if(enum!=AiJson.Null&&(enum as AiJson.Arr).values.none{it==value})invalid(path,"ai.expected_enum:"+enum.values.joinToString("/"){it.string()})
            }
            else -> error("ai.invalid_schema")
        }
    }
}
