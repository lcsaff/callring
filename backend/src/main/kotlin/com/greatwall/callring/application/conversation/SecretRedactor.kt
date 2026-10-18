package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.api.*

class SecretRedactor(secrets: List<String>) {
    var count = 0
        private set
    private val matcher = secrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }.takeIf { it.isNotEmpty() }?.joinToString("|") { secret ->
        secret.codePoints().toArray().joinToString("") { point ->
            val chars = String(Character.toChars(point))
            val percent = chars.toByteArray(Charsets.UTF_8).joinToString("") { "(?i:%%%02x)".format(it.toInt() and 255) }
            val unicode = chars.map { "(?i:\\\\u%04x)".format(it.code) }.joinToString("")
            "(?:${Regex.escape(chars)}|$percent|$unicode)"
        }
    }?.let { Regex("\\[redacted\\]|(?:$it)") }
    fun text(value: String): String = matcher?.replace(value) { if(it.value != "[redacted]") count++; "[redacted]" } ?: value
    fun redact(value: AiJson): AiJson = when (value) {
        is AiJson.Str -> AiJson.Str(text(value.value))
        is AiJson.Arr -> AiJson.Arr(value.values.map(::redact))
        is AiJson.Obj -> AiJson.Obj(value.fields.mapValues { redact(it.value) })
        else -> value
    }
}

/** Keep protocol identifiers intact; only application data is subject to credential redaction. */
fun redactAssistantBlock(block: AiJson, redactor: SecretRedactor): AiJson {
    val fields = (block as AiJson.Obj).fields
    return when (fields.getValue("type").string()) {
        "text" -> aiObject("type" to aiString("text"), "text" to redactor.redact(fields.getValue("text")))
        "tool_use" -> aiObject("type" to aiString("tool_use"), "id" to fields.getValue("id"), "name" to fields.getValue("name"), "input" to redactor.redact(fields.getValue("input")))
        else -> throw IllegalArgumentException("ai.invalid_assistant_block")
    }
}
