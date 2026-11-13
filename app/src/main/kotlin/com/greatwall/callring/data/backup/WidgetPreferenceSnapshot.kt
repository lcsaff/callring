package com.greatwall.callring.data.backup

import android.content.SharedPreferences
import android.util.Xml
import java.io.File
import java.io.FileOutputStream

/** Preserve every Android preference value, including extension keys unknown to widget bindings. */
internal object WidgetPreferenceSnapshot {
    fun capture(preferences: SharedPreferences): Map<String, Any> = preferences.all.mapValues { (_, value) ->
        requireNotNull(value).let { if (it is Set<*>) it.map { item -> item as String }.toSet() else it }
    }
    fun write(file: File, values: Map<String, Any>) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        FileOutputStream(file).use { out ->
            val xml = Xml.newSerializer(); xml.setOutput(out, "UTF-8"); xml.startDocument("UTF-8", true); xml.startTag(null, "map")
            fun string(value: String) { xml.startTag(null,"string"); xml.text(value); xml.endTag(null,"string") }
            values.forEach { (key,value) ->
                val tag = when(value) { is String -> "string"; is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is Set<*> -> "set"; else -> error("preferences.unsupported_type") }
                xml.startTag(null,tag); xml.attribute(null,"name",key)
                when(value) { is String -> xml.text(value); is Set<*> -> value.forEach { string(it as String) }; else -> xml.attribute(null,"value",value.toString()) }
                xml.endTag(null,tag)
            }
            xml.endTag(null,"map"); xml.endDocument(); xml.flush(); out.fd.sync()
        }
    }
    fun refresh(preferences: SharedPreferences, values: Map<String, Any>) {
        val editor = preferences.edit().clear()
        values.forEach { (key,value) -> when(value) {
            is String -> editor.putString(key,value); is Boolean -> editor.putBoolean(key,value); is Int -> editor.putInt(key,value)
            is Long -> editor.putLong(key,value); is Float -> editor.putFloat(key,value)
            is Set<*> -> editor.putStringSet(key,value.map { it as String }.toSet())
            else -> error("preferences.unsupported_type")
        } }
        check(editor.commit()) { "preferences.write_failed" }
    }
}
