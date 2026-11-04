package com.greatwall.callring.domain.recording

data class SystemRecordingWindow(val now: Long, val days: Int) {
    init { require(days > 0) }
    val since: Long = Math.subtractExact(now, days.toLong() * 86_400_000L)
    fun contains(startedAt: Long): Boolean = startedAt in since..now
}
