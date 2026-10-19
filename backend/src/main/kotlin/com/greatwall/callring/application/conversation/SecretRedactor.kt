package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.api.*

class SecretRedactor(secrets: List<String>) {
    /** Longest possible encoded representation; used only for bounded source lookaround. */
    val overlapCodePoints:Int=secrets.maxOfOrNull{maxOf(it.length.toLong()*6,it.toByteArray(Charsets.UTF_8).size.toLong()*3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()}?:0
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
    fun textRange(value:String,startCodePoint:Int,endCodePoint:Int):String {
        val start=value.offsetByCodePoints(0,startCodePoint);val end=value.offsetByCodePoints(0,endCodePoint)
        val matches=matcher?.findAll(value)?.filter{it.range.first<end&&it.range.last+1>start}?.toList().orEmpty()
        if(matches.isEmpty())return value.substring(start,end)
        return buildString{var at=start;matches.forEach{m->val left=maxOf(start,m.range.first);if(left>at)append(value.substring(at,left));append("[redacted]");count++;at=minOf(end,m.range.last+1)};if(at<end)append(value.substring(at,end))}
    }
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
