package com.greatwall.callring.data.backup

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.Reader

/** Scans one persisted JSON object in fixed pages; long transcript/analysis values are skipped. */
internal object ArchiveBackupFields {
    data class Field(val start: Long, val end: Long, val scalar: String?, val present: Boolean = true)
    private val selected = setOf("id", "number", "started", "generation", "status", "audio", "manual", "next", "error", "source", "protocol", "duration", "attempts", "analyzed")
    fun fields(db: SQLiteDatabase, id: Long, check: () -> Unit): Map<String, Field> {
        try {
            val reader = PagedPayload(db, id, check)
            val fields = Scanner(reader).fields()
            validateShape(PagedPayload(db, id, check))
            if (fields.getValue("attempts").scalar?.toIntOrNull() == null || fields.getValue("next").scalar?.toLongOrNull() == null) throw BackupFormatException()
            return fields
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (_: Exception) { throw BackupFormatException() }
    }
    fun string(field: Field?): String? {
        val raw = field?.scalar ?: throw BackupFormatException()
        return if (raw == "null") null else JSONObject("{\"v\":$raw}").getString("v")
    }
    fun replace(db: SQLiteDatabase, id: Long, fields: Map<String, Field>, values: Map<String, String>) {
        values.entries.sortedByDescending { fields[it.key]?.start ?: throw BackupFormatException() }.forEach { (key, value) ->
            val field = fields.getValue(key)
            val replacement = if (field.present) value else "," + JSONObject.quote(key) + ":" + value
            db.execSQL("UPDATE archives SET payload=substr(payload,1,?)||?||substr(payload,?) WHERE call_id=?", arrayOf<Any>(field.start, replacement, field.end + 1, id))
        }
    }
    /** Byte substrings avoid rescanning UTF-8 from the start for every text page. */
    private class PagedPayload(db: SQLiteDatabase, id: Long, check: () -> Unit) : Reader() {
        private val text = java.io.InputStreamReader(object : java.io.InputStream() {
            private var next = 1L
            private var page = byteArrayOf()
            private var at = 0
            private fun load(): Boolean {
                if (at < page.size) return true
                check()
                page = db.rawQuery("SELECT substr(CAST(payload AS BLOB),?,32768) FROM archives WHERE call_id=?", arrayOf(next.toString(), id.toString())).use {
                    if (!it.moveToFirst()) throw BackupFormatException(); it.getBlob(0)
                }
                at = 0; next += page.size
                return page.isNotEmpty()
            }
            override fun read(): Int = if (load()) page[at++].toInt() and 255 else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (!load()) return -1
                val count = minOf(length, page.size - at)
                page.copyInto(bytes, offset, at, at + count); at += count
                return count
            }
        }, Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT))
        override fun read(): Int = text.read()
        override fun read(chars: CharArray, offset: Int, length: Int): Int = text.read(chars, offset, length)
        override fun close() = text.close()
    }
    private fun validateShape(source: Reader) {
        android.util.JsonReader(source).use { reader ->
            reader.isLenient = false
            val required = setOf("id", "number", "started", "generation", "status", "source", "quality", "transcript", "attempts", "next", "analysisModel", "transcriptionModel")
            val strings = setOf("number", "status", "source", "quality", "transcript", "analysisModel", "transcriptionModel")
            val seen = hashSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (key in required && !seen.add(key)) throw BackupFormatException()
                when {
                    key in strings -> { if (reader.peek() != android.util.JsonToken.STRING) throw BackupFormatException(); reader.skipValue() }
                    key == "analysis" && reader.peek() == android.util.JsonToken.BEGIN_OBJECT -> validateAnalysis(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (!seen.containsAll(required) || reader.peek() != android.util.JsonToken.END_DOCUMENT) throw BackupFormatException()
        }
    }
    private fun validateAnalysis(reader: android.util.JsonReader) {
        val required = setOf("name", "organization", "position", "purpose", "evidence", "identity", "summary", "actions")
        val seen = hashSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (key in required && !seen.add(key)) throw BackupFormatException()
            when {
                key == "actions" -> {
                    reader.beginArray()
                    while (reader.hasNext()) { if (reader.peek() != android.util.JsonToken.STRING) throw BackupFormatException(); reader.skipValue() }
                    reader.endArray()
                }
                key in required -> { if (reader.peek() != android.util.JsonToken.STRING) throw BackupFormatException(); reader.skipValue() }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (!seen.containsAll(required)) throw BackupFormatException()
    }    private class Scanner(private val input: Reader) {
        private var current = input.read()
        private var offset = 0L
        private var previousHigh = false
        private fun take(): Int {
            val value = current
            if (value == -1) throw BackupFormatException()
            val char = value.toChar()
            if (!char.isLowSurrogate() || !previousHigh) offset++
            previousHigh = char.isHighSurrogate()
            current = input.read()
            return value
        }
        private fun expect(char: Char) { if (take() != char.code) throw BackupFormatException() }
        private fun whitespace() { while (current in listOf(32, 9, 10, 13)) take() }
        fun fields(): Map<String, Field> {
            val output = linkedMapOf<String, Field>()
            whitespace(); expect('{'); whitespace()
            while (current != '}'.code) {
                val keyRaw = string(true)
                val key = JSONObject("{\"v\":$keyRaw}").getString("v")
                whitespace(); expect(':'); whitespace()
                val start = offset
                val scalar = skipValue(0, key in selected)
                if (key in selected) {
                    if (output.put(key, Field(start, offset, scalar)) != null) throw BackupFormatException()
                }
                whitespace()
                if (current != ','.code) break
                take(); whitespace()
            }
            val closing = offset
            expect('}'); whitespace()
            val optional = setOf("manual", "audio", "error", "protocol", "duration", "analyzed")
            if (current != -1 || !output.keys.containsAll(selected - optional)) throw BackupFormatException()
            optional.forEach { key -> output.putIfAbsent(key, Field(closing, closing, if (key == "manual") "false" else "null", present = false)) }
            return output
        }
        private fun string(capture: Boolean): String {
            val result = StringBuilder()
            fun append(value: Int) { if (capture) { if (result.length > 8192) throw BackupFormatException(); result.append(value.toChar()) } }
            expect('"'); append('"'.code)
            while (current != '"'.code) {
                val value = take(); if (value < 32) throw BackupFormatException(); append(value)
                if (value == '\\'.code) {
                    val escaped = take(); append(escaped)
                    if (escaped == 'u'.code) repeat(4) { val hex = take(); if (hex.toChar().digitToIntOrNull(16) == null) throw BackupFormatException(); append(hex) }
                    else if (escaped.toChar() !in "\"\\/bfnrt") throw BackupFormatException()
                }
            }
            expect('"'); append('"'.code)
            return result.toString()
        }
        private fun skipValue(depth: Int, capture: Boolean): String? {
            if (depth > 128) throw BackupFormatException()
            if (current == '"'.code) return string(capture).takeIf { capture }
            if (current == '{'.code || current == '['.code) {
                val open = take(); val close = if (open == '{'.code) '}' else ']'
                whitespace()
                if (current == close.code) { take(); return null }
                while (true) {
                    if (open == '{'.code) { string(true); whitespace(); expect(':'); whitespace() }
                    skipValue(depth + 1, false); whitespace()
                    if (current != ','.code) break
                    take(); whitespace()
                }
                expect(close)
                return null
            }
            val token = StringBuilder()
            while (current != -1 && current.toChar() !in ",]} \t\r\n") { if (token.length >= 128) throw BackupFormatException(); token.append(take().toChar()) }
            val value = token.toString()
            if (value !in setOf("null", "true", "false") && !value.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) throw BackupFormatException()
            return value.takeIf { capture }
        }
    }
}
