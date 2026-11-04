package com.greatwall.callring.domain.recording

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class ParsedSystemRecording(val number: String, val startedAtEpochMillis: Long)

/** An explicit number and one strictly valid local start time are required. */
object SystemRecordingNameParser {
    private val name = Regex("^(?:[^()]*\\((\\+?[0-9]{1,30})\\)|(\\+?[0-9]{1,30}))_([0-9]{8})[_-]?([0-9]{6})\\.(?i:mp3|wav|m4a|amr|aac|flac|ogg|opus|3gp)$")
    private val format = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)

    fun parse(fileName: String, zone: ZoneId): ParsedSystemRecording? {
        val match = name.matchEntire(fileName) ?: return null
        val local = try { LocalDateTime.parse(match.groupValues[3] + match.groupValues[4], format) }
        catch (_: java.time.DateTimeException) { return null }
        val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: return null
        return ParsedSystemRecording(match.groupValues[1].ifEmpty { match.groupValues[2] }, local.toInstant(offset).toEpochMilli())
    }
}
