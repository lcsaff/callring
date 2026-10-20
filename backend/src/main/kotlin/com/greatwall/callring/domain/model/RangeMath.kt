package com.greatwall.callring.domain.model

import java.time.LocalDate
import java.time.ZoneId

/** Calendar boundaries use local midnight and retain DST transitions and skipped dates. */
fun calendarRange(start: LocalDate, endInclusive: LocalDate, zone: ZoneId): TimeRange = TimeRange(
    start.atStartOfDay(zone).toInstant().toEpochMilli(),
    endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    zone.id,
)

fun saturatedAdd(a: Long, b: Long): Long =
    if (b > 0 && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

fun saturatedSubtract(a: Long, b: Long): Long =
    if (b > 0 && a < Long.MIN_VALUE + b) Long.MIN_VALUE else a - b
