package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.api.*
import com.greatwall.callring.platform.StrictJson
import org.json.*

fun parseConversationJson(text: String): AiJson {
    StrictJson.validate(text)
    return conversationJsonValue(JSONTokener(text).nextValue())
}
private fun conversationJsonValue(value: Any?): AiJson = when(value) {
    null, JSONObject.NULL -> AiJson.Null
    is JSONObject -> AiJson.Obj(value.keys().asSequence().associateWith { conversationJsonValue(value.get(it)) })
    is JSONArray -> AiJson.Arr((0 until value.length()).map { conversationJsonValue(value.get(it)) })
    is String -> AiJson.Str(value)
    is Boolean -> AiJson.Bool(value)
    is Number -> AiJson.Num(value.toString())
    else -> throw IllegalArgumentException("ai.invalid_response")
}
