package com.greatwall.callring.platform

import com.greatwall.callring.application.ports.CalendarSource
import com.greatwall.callring.domain.CalendarImport
import com.greatwall.callring.application.api.CalendarYear
import com.greatwall.callring.data.WorkConfigCodec
import java.io.IOException
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/** Bounded HTTPS download; the backend owns the durable, atomic calendar cache. */
class HolidayCalendarSource(private val clock: Clock = Clock.systemUTC()) : CalendarSource {
    override fun fetch(year: Int): CalendarImport {
        require(year in 2008..9998)
        val urls = listOf(
            "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/$year.json",
            "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json",
        )
        var lastFailure: Exception? = null
        for (url in urls) {
            try {
                val connection = URL(url).openConnection() as HttpsURLConnection
                try {
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "application/json, text/plain")
                    val deadline = System.nanoTime() + 8_000_000_000L
                    if (connection.responseCode != 200) throw IOException("calendar.http_failure")
                    if (connection.contentLengthLong > 1024 * 1024) throw IOException("calendar.too_large")
                    val text = connection.inputStream.use { input ->
                        val checked = object : java.io.FilterInputStream(input) {
                            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                                if (remaining <= 0) throw IOException("calendar.timeout")
                                connection.readTimeout = minOf(4000L, remaining).toInt().coerceAtLeast(1)
                                return super.read(bytes, offset, count)
                            }
                        }
                        WorkConfigCodec.readLimited(checked)
                    }
                    return parse(year, text, LocalDate.now(clock.withZone(ZoneOffset.UTC)))
                } finally { connection.disconnect() }
            } catch (failure: Exception) { lastFailure = failure }
        }
        throw IOException("calendar.download_failed", lastFailure)
    }
    companion object {
        private val requiredNames = listOf("元旦", "春节", "清明", "劳动", "端午", "中秋", "国庆")

        /** published records the fetch date: the upstream format has no publication timestamp. */
        fun parse(year: Int, text: String, fetched: LocalDate = LocalDate.now(ZoneOffset.UTC)): CalendarImport {
            try {
                require(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
                StrictJson.validate(text)
                val root = JSONObject(text)
                val rawYear = root.get("year")
                require((rawYear is Int || rawYear is Long) && (rawYear as Number).toLong() == year.toLong())
                val papers = root.getJSONArray("papers")
                require(papers.length() in 1..20)
                for (i in 0 until papers.length()) {
                    val paper = papers.get(i)
                    require(paper is String)
                    val uri = URI(paper)
                    require(uri.scheme == "https" && uri.host in listOf("www.gov.cn", "gov.cn") && uri.userInfo == null && uri.path.isNotBlank())
                }
                val entries = root.getJSONArray("days")
                require(entries.length() in 20..100)
                val holidays = linkedMapOf<LocalDate, String>()
                val workdays = linkedSetOf<LocalDate>()
                val seen = mutableSetOf<LocalDate>()
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    val rawDate = entry.get("date")
                    val name = entry.get("name")
                    val offDay = entry.get("isOffDay")
                    require(rawDate is String && rawDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
                    val date = LocalDate.parse(rawDate)
                    require(date.year == year && seen.add(date))
                    require(name is String && name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() })
                    require(offDay is Boolean)
                    if (offDay) holidays[date] = name.trim() else workdays.add(date)
                }
                require(holidays.size >= 20 && requiredNames.all { name -> holidays.values.any { name in it } })
                val version = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                return CalendarImport(CalendarYear(year, "holiday-cn（国务院公告）", fetched.toString(), version), holidays, workdays, text)
            } catch (failure: Exception) {
                throw IllegalArgumentException("calendar.invalid_or_incomplete", failure)
            }
        }
    }
}
