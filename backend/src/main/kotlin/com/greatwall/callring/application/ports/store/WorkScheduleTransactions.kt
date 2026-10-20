package com.greatwall.callring.application.ports.store

import com.greatwall.callring.domain.model.WorkScheduleSettings

interface WorkScheduleReader { fun latestWorkSchedule(): WorkScheduleSettings? }
interface WorkScheduleWriter : WorkScheduleReader {
    /** Allocates its ID using the storage engine's persistent sequence. */
    fun appendWorkSchedule(value: WorkScheduleSettings): WorkScheduleSettings
}
interface WorkScheduleTransactions {
    fun <T> readWorkSchedule(block: (WorkScheduleReader) -> T): T
    fun <T> writeWorkSchedule(block: (WorkScheduleWriter) -> T): T
}
