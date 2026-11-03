package com.greatwall.callring.data.callers

import com.greatwall.callring.domain.model.IdentityFieldMetadata
import org.json.JSONObject

internal object CallerIdentityCodec {
    fun encode(fields: Map<String, IdentityFieldMetadata>): String = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, JSONObject().apply {
            put("revision", value.revision); put("disputed", value.disputed)
            put("archiveId", value.archiveId); put("analysisGeneration", value.analysisGeneration)
            put("evidence", value.evidence)
        }) }
    }.toString()

    fun decode(raw: String): Map<String, IdentityFieldMetadata> {
        val json = JSONObject(raw)
        return json.keys().asSequence().associateWith { key ->
            val value = json.getJSONObject(key)
            IdentityFieldMetadata(value.getLong("revision"), value.getBoolean("disputed"),
                if (value.isNull("archiveId")) null else value.getLong("archiveId"),
                if (value.isNull("analysisGeneration")) null else value.getLong("analysisGeneration"),
                value.getString("evidence"))
        }
    }
}
