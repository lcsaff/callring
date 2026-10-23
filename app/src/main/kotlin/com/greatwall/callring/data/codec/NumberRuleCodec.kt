package com.greatwall.callring.data.codec

import com.greatwall.callring.application.mapping.toSnapshot
import com.greatwall.callring.contracts.settings.NumberRuleSettings
import org.json.JSONObject

/** Sole interpreter of the versioned settings value, also used by read projections. */
object NumberRuleCodec {
    fun decode(raw: String?): NumberRuleSettings {
        requireNotNull(raw) { "number_rule.null" }
        return android.util.JsonReader(java.io.StringReader(raw)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val fields = linkedMapOf<String, Any>()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(key !in fields) { "number_rule.duplicate.$key" }
                when (key) {
                    "version", "length", "revision" -> {
                        require(reader.peek() == android.util.JsonToken.NUMBER) { "number_rule.integer.$key" }
                        val token = reader.nextString()
                        require(token.matches(Regex("-?(0|[1-9][0-9]*)"))) { "number_rule.integer.$key" }
                        fields[key] = token.toLong()
                    }
                    "prefix" -> {
                        require(reader.peek() == android.util.JsonToken.STRING) { "number_rule.prefix" }
                        fields[key] = reader.nextString()
                    }
                    else -> error("number_rule.field.$key")
                }
            }
            reader.endObject()
            require(reader.peek() == android.util.JsonToken.END_DOCUMENT) { "number_rule.trailing" }
            require(fields["version"] == 1L) { "number_rule.version" }
            val length = fields["length"] as? Long ?: error("number_rule.length")
            require(length in 1..30) { "number_rule.length" }
            NumberRuleSettings(fields["prefix"] as? String ?: error("number_rule.prefix"),
                length.toInt(),fields["revision"] as? Long ?: error("number_rule.revision"))
                .also { it.toSnapshot() }
        }
    }

    fun encode(value: NumberRuleSettings): String {
        value.toSnapshot()
        return JSONObject().put("version",1).put("prefix",value.prefix).put("length",value.length)
            .put("revision",value.revision).toString()
    }
}
