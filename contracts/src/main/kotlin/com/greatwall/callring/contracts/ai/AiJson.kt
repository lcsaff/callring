package com.greatwall.callring.contracts.ai

sealed class AiJson {
    data class Obj(val fields: Map<String, AiJson>) : AiJson() {
        operator fun get(key: String): AiJson = fields[key] ?: Null
    }
    data class Arr(val values: List<AiJson>) : AiJson()
    data class Str(val value: String) : AiJson()
    data class Num(val value: String) : AiJson()
    data class Bool(val value: Boolean) : AiJson()
    data object Null : AiJson()
    fun encode(): String = when (this) {
        is Obj -> fields.entries.joinToString(",", "{", "}") { quote(it.key) + ":" + it.value.encode() }
        is Arr -> values.joinToString(",", "[", "]") { it.encode() }
        is Str -> quote(value)
        is Num -> value
        is Bool -> value.toString()
        Null -> "null"
    }
    companion object {
        fun quote(value: String): String = buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }
    }
}

fun aiObject(vararg values: Pair<String, AiJson>) = AiJson.Obj(linkedMapOf(*values))

fun aiString(value: String?) = value?.let(AiJson::Str) ?: AiJson.Null

fun aiNumber(value: Number?) = value?.let { AiJson.Num(it.toString()) } ?: AiJson.Null

fun aiBool(value: Boolean) = AiJson.Bool(value)

fun AiJson.string(): String = (this as? AiJson.Str)?.value ?: throw IllegalArgumentException("ai.argument_string")

fun AiJson.long(): Long = (this as? AiJson.Num)?.value?.toLongOrNull() ?: throw IllegalArgumentException("ai.argument_integer")

fun AiJson.boolean(): Boolean = (this as? AiJson.Bool)?.value ?: throw IllegalArgumentException("ai.argument_boolean")
