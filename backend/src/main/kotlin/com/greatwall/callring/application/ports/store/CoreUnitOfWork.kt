package com.greatwall.callring.application.ports.store

import com.greatwall.callring.domain.model.QuietSettings
import com.greatwall.callring.domain.model.QuietMode
import com.greatwall.callring.domain.StatisticsInterval

data class QuietConfiguration(val settings: QuietSettings, val lastMode: QuietMode)
interface QuietReader { fun quiet(): QuietConfiguration }
interface QuietWriter : QuietReader { fun saveQuiet(value: QuietConfiguration): Boolean }
interface QuietTransactions {
    fun <T> readQuiet(block: (QuietReader) -> T): T
    fun <T> writeQuiet(block: (QuietWriter) -> T): T
}
/** Explicit absence for calculation-only compositions; never loads legacy full state. */
object UnavailableQuietTransactions : QuietTransactions {
    override fun <T> readQuiet(block: (QuietReader) -> T): T = error("quiet.storage.unavailable")
    override fun <T> writeQuiet(block: (QuietWriter) -> T): T = error("quiet.storage.unavailable")
}
interface CoreReadSession : QuietReader { fun statistics(): List<StatisticsInterval> }
interface CoreWriteSession : CoreReadSession, QuietWriter { fun appendStatistics(value: StatisticsInterval) }
interface CoreUnitOfWork : QuietTransactions {
    fun <T> readSession(block: (CoreReadSession) -> T): T
    fun <T> writeSession(block: (CoreWriteSession) -> T): T
}
