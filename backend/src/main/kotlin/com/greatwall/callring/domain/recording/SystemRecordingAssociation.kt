package com.greatwall.callring.domain.recording

/** A recording may begin at answer time, after the incoming call started ringing. */
object SystemRecordingAssociation {
    const val CLOCK_SKEW_MILLIS = 3_000L
    const val MAX_START_DELAY_MILLIS = 120_000L

    fun callStarts(recordingStartedAt: Long): LongRange =
        lower(recordingStartedAt, MAX_START_DELAY_MILLIS)..upper(recordingStartedAt, CLOCK_SKEW_MILLIS)

    fun recordingStarts(callStartedAt: Long): LongRange =
        lower(callStartedAt, CLOCK_SKEW_MILLIS)..upper(callStartedAt, MAX_START_DELAY_MILLIS)

    fun matches(callStartedAt: Long, recordingStartedAt: Long) = recordingStartedAt in recordingStarts(callStartedAt)

    private fun lower(at: Long, amount: Long) = if (at < Long.MIN_VALUE + amount) Long.MIN_VALUE else at - amount
    private fun upper(at: Long, amount: Long) = if (at > Long.MAX_VALUE - amount) Long.MAX_VALUE else at + amount
}
