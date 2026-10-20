package com.greatwall.callring.data

import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.domain.CalendarImport
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** JSON persistence format stays byte compatible with shipped schema versions 1–3. */
object WorkConfigCodec {
    fun encode(config: WorkScheduleSettings): String =
        JSONObject()
            .put("zone", config.zoneId)
            .put("weekly", weeklyJson(config.weekly))
            .put("makeup", windowsJson(config.makeup))
            .toString()

    fun decode(raw: String, version: Long, effective: Long): WorkScheduleSettings {
        val json = JSONObject(raw)
        val zone = ZoneId.of(json.getString("zone")).id
        return WorkScheduleSettings(
            zone,
            weekly(json.getJSONObject("weekly")),
            windows(json.getJSONArray("makeup")),
            version,
            effective,
        )
    }

    fun weeklyJson(weekly: Map<Int, List<TimeWindow>>): JSONObject =
        JSONObject().apply {
            for (day in 1..7) put(day.toString(), windowsJson(weekly[day].orEmpty()))
        }

    fun weekly(json: JSONObject): Map<Int, List<TimeWindow>> =
        (1..7).associateWith { windows(json.optJSONArray(it.toString())) }

    fun windowsJson(windows: List<TimeWindow>): JSONArray =
        JSONArray().apply {
            windows.forEach { put(JSONArray().put(it.startMinute).put(it.endMinute)) }
        }

    fun windows(json: JSONArray?): List<TimeWindow> =
        if (json == null) emptyList()
        else
            (0 until json.length()).map {
                val pair = json.getJSONArray(it)
                require(pair.length() == 2) { "A window requires two minutes" }
                val start = pair.getInt(0)
                val end = pair.getInt(1)
                require(start in 0..1439 && end in 0..1439 && start != end) {
                    "Invalid window minutes"
                }
                TimeWindow(start, end)
            }

    fun readLimited(stream: InputStream): String {
        val limit = 1024 * 1024
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val length = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (length == -1) break
            if (length == 0) continue
            output.write(buffer, 0, length)
            require(output.size() <= limit) { "Calendar exceeds 1 MiB" }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
                .removePrefix("\uFEFF")
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("Calendar is not UTF-8", failure)
        }
    }

    fun calendar(raw: String): CalendarImport {
        val json = JSONObject(raw)
        val rawYear = json.get("year")
        require(rawYear is Int || rawYear is Long) { "Calendar year must be an integer" }
        val yearNumber = (rawYear as Number).toLong()
        require(yearNumber in 1970L..9998L) { "Invalid calendar year" }
        val year = yearNumber.toInt()
        val source = json.textField("source").trim()
        val version = json.textField("version").trim()
        val published = LocalDate.parse(json.textField("published")).toString()
        require(source.isNotEmpty() && version.isNotEmpty()) {
            "Calendar source and version are required"
        }
        val holidays = linkedMapOf<LocalDate, String>()
        val entries = json.getJSONArray("holidays")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val first = LocalDate.parse(entry.textField("start"))
            val last = LocalDate.parse(entry.textField("end"))
            val name = entry.textField("name").trim()
            require(
                first.year == year &&
                    last.year == year &&
                    !last.isBefore(first) &&
                    name.isNotEmpty()
            ) {
                "Invalid holiday range"
            }
            var date = first
            while (!date.isAfter(last)) {
                require(holidays.put(date, name) == null) { "Duplicate holiday date" }
                date = date.plusDays(1)
            }
        }
        val workdays = linkedSetOf<LocalDate>()
        val days = json.getJSONArray("workdays")
        for (index in 0 until days.length()) {
            val date = LocalDate.parse(textValue(days.get(index), "workdays[$index]"))
            require(date.year == year && date !in holidays && workdays.add(date)) {
                "Duplicate or conflicting makeup date"
            }
        }
        return CalendarImport(
            CalendarYear(year, source, published, version),
            holidays,
            workdays,
            raw,
        )
    }

    private fun JSONObject.textField(key: String): String = textValue(get(key), key)

    private fun textValue(value: Any?, field: String): String {
        require(value is String) { "Calendar $field must be a string" }
        return value
    }
}
