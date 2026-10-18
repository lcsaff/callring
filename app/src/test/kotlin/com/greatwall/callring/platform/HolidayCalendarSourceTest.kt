package com.greatwall.callring.platform

import org.junit.Assert.*
import org.junit.Test

class HolidayCalendarSourceTest {
    @Test fun parsesPinnedUpstream2026Response() {
        val text = javaClass.getResource("/calendar/2026.json")!!.readText()
        val result = HolidayCalendarSource.parse(2026, text)
        assertEquals(33, result.holidays.size)
        assertEquals(6, result.makeupDates.size)
        assertEquals("国庆节", result.holidays[java.time.LocalDate.of(2026, 10, 1)])
    }
    private val names = listOf("元旦", "春节", "清明节", "劳动节", "端午节", "中秋节", "国庆节")
    private fun json(year: Int = 2026): String {
        val dates = listOf("01-01", "02-17", "04-05", "05-01", "06-19", "09-25", "10-01")
        val days = names.zip(dates).flatMap { (name, date) -> (0L..2L).map { offset ->
            val day = java.time.LocalDate.parse("$year-$date").plusDays(offset)
            """{"name":"$name","date":"$day","isOffDay":true}"""
        } }.joinToString(",")
        return """{"year":$year,"papers":["https://www.gov.cn/zhengce/content_123.htm"],"days":[$days]}"""
    }
    @Test fun parsesAnnualHolidaysAndMakeupDates() {
        val text = json().replace("]}", ", {\"name\":\"国庆节\",\"date\":\"2026-10-10\",\"isOffDay\":false}]}")
        val result = HolidayCalendarSource.parse(2026, text)
        assertEquals(21, result.holidays.size)
        assertEquals(setOf(java.time.LocalDate.of(2026, 10, 10)), result.makeupDates)
        assertEquals(text, result.rawJson)
    }
    @Test fun rejectsUnpublishedEmptyAndPartialYear() {
        for (text in listOf(json().replace("https://www.gov.cn/zhengce/content_123.htm", ""),
            """{"year":2026,"papers":[],"days":[]}""", json().replace("国庆节", "其他"))) {
            assertInvalid(text)
        }
    }
    @Test fun rejectsWrongYearDateAndNonBooleanFlags() {
        for (text in listOf(json(2027), json().replace("2026-01-01", "2027-01-01"),
            json().replace("true", "\"true\""), json().replace("2026-01-01", "2026-02-30"))) assertInvalid(text)
    }
    @Test fun rejectsDuplicateDatesAndMalformedJson() {
        assertInvalid(json().replace("2026-02-17", "2026-01-01"))
        assertInvalid(json() + "garbage")
    }
    private fun assertInvalid(text: String) {
        try { HolidayCalendarSource.parse(2026, text); fail("Accepted invalid calendar") }
        catch (_: IllegalArgumentException) { }
    }
}
