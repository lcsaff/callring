package com.greatwall.callring.data.codec

import android.util.JsonReader
import android.util.JsonToken
import com.greatwall.callring.application.mapping.toSnapshot
import com.greatwall.callring.contracts.settings.NumberRuleEntry
import com.greatwall.callring.contracts.settings.NumberRuleSettings
import java.io.StringReader
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

/** Sole interpreter of both durable settings versions, also used by read projections. */
object NumberRuleCodec {
    fun decode(raw: String?): NumberRuleSettings {
        requireNotNull(raw) { "number_rule.null" }
        return JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val fields = linkedMapOf<String, Any>()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(key !in fields) { "number_rule.duplicate.$key" }
                fields[key] = when (key) {
                    "version", "length", "revision" -> integer(reader,key)
                    "prefix" -> prefix(reader)
                    "rules" -> {
                        reader.beginArray()
                        val rules = arrayListOf<NumberRuleEntry>()
                        while (reader.hasNext()) rules += entry(reader)
                        reader.endArray()
                        rules
                    }
                    else -> error("number_rule.field.$key")
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "number_rule.trailing" }
            val rules = when (fields["version"]) {
                1L -> {
                    require(fields.keys == setOf("version","prefix","length","revision")) { "number_rule.fields" }
                    listOf(NumberRuleEntry(fields.getValue("prefix") as String,length(fields.getValue("length") as Long)))
                }
                2L -> {
                    require(fields.keys == setOf("version","rules","revision")) { "number_rule.fields" }
                    @Suppress("UNCHECKED_CAST")
                    (fields.getValue("rules") as List<NumberRuleEntry>)
                }
                else -> error("number_rule.version")
            }
            NumberRuleSettings(Collections.unmodifiableList(ArrayList(rules)),fields.getValue("revision") as Long)
                .also { it.toSnapshot() }
        }
    }

    private fun entry(reader: JsonReader): NumberRuleEntry {
        reader.beginObject()
        val seen = hashSetOf<String>()
        var entryPrefix: String? = null
        var entryLength: Int? = null
        while (reader.hasNext()) {
            val key = reader.nextName()
            require(seen.add(key)) { "number_rule.duplicate.$key" }
            when (key) {
                "prefix" -> entryPrefix = prefix(reader)
                "length" -> entryLength = length(integer(reader,key))
                else -> error("number_rule.field.$key")
            }
        }
        reader.endObject()
        return NumberRuleEntry(requireNotNull(entryPrefix) { "number_rule.prefix" },requireNotNull(entryLength) { "number_rule.length" })
    }

    private fun integer(reader: JsonReader, key: String): Long {
        require(reader.peek() == JsonToken.NUMBER) { "number_rule.integer.$key" }
        val token = reader.nextString()
        require(token.matches(Regex("-?(0|[1-9][0-9]*)"))) { "number_rule.integer.$key" }
        return token.toLong()
    }
    private fun prefix(reader: JsonReader): String {
        require(reader.peek() == JsonToken.STRING) { "number_rule.prefix" }
        return reader.nextString()
    }
    private fun length(value: Long): Int {
        require(value in 1..30) { "number_rule.length" }
        return value.toInt()
    }

    fun encode(value: NumberRuleSettings): String {
        value.toSnapshot()
        val rules = JSONArray()
        value.rules.forEach { rules.put(JSONObject().put("prefix",it.prefix).put("length",it.length)) }
        return JSONObject().put("version",2).put("rules",rules).put("revision",value.revision).toString()
    }
}
