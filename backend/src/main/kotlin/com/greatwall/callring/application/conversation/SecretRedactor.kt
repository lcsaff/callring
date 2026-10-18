package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.api.*

class SecretRedactor(secrets: List<String>) {
    var count = 0
        private set
    private val matcher = secrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }.takeIf { it.isNotEmpty() }?.joinToString("|") { secret ->
        secret.map { c ->
            val percent = c.toString().toByteArray(Charsets.UTF_8).joinToString("") { "(?i:%%%02x)".format(it.toInt() and 255) }
            val unicode = "(?i:\\\\u%04x)".format(c.code)
            "(?:${Regex.escape(c.toString())}|$percent|$unicode)"
        }.joinToString("")
    }?.let { Regex("\\[redacted\\]|(?:$it)") }
    fun text(value: String): String = matcher?.replace(value) { if(it.value != "[redacted]") count++; "[redacted]" } ?: value
    fun redact(value: AiJson): AiJson = when (value) {
        is AiJson.Str -> AiJson.Str(text(value.value))
        is AiJson.Arr -> AiJson.Arr(value.values.map(::redact))
        is AiJson.Obj -> AiJson.Obj(value.fields.mapValues { redact(it.value) })
        else -> value
    }
}
