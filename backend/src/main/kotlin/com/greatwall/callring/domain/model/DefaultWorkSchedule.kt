package com.greatwall.callring.domain.model

import java.time.ZoneId

fun defaultWorkSchedule(
    zoneId: String = ZoneId.systemDefault().id,
    version: Long = 1,
    effective: Long = 0,
): WorkScheduleSettings {
    val windows = listOf(TimeWindow(540, 720), TimeWindow(780, 1080))
    return WorkScheduleSettings(
        zoneId,
        (1..5).associateWith { windows },
        windows,
        version,
        effective,
    )
}
