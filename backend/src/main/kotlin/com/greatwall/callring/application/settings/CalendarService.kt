package com.greatwall.callring.application.settings

import com.greatwall.callring.application.ports.platform.CalendarDocumentReader
import com.greatwall.callring.application.ports.platform.CalendarSource

import com.greatwall.callring.application.coordination.requireOperationActive

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.frozenList
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.model.AuditRecord
import com.greatwall.callring.domain.model.CalendarImport
import com.greatwall.callring.domain.model.SettingsRules
import java.time.*
import kotlinx.coroutines.*

internal class CalendarService(private val transactions: CalendarTransactions, coordinator: BusinessRevisionCoordinator,
    dispatcher: CoroutineDispatcher, private val clock: Clock, private val source: CalendarSource,
    private val documents: CalendarDocumentReader, private val afterCommit: (WorkHistory) -> Unit,
    private val command: CommandContext? = null,
    private val operationActive: () -> Boolean,
) {
    private val runner = UseCaseRunner(dispatcher)
    private val committer = CommandCommitter(coordinator, operationActive)
    suspend fun get() = runner.io { requireOperationActive(operationActive); transactions.readCalendar { overview(it) } }
    suspend fun synchronize(year: Int): ApiResult<CalendarInfo> {
        val prepared = runner.io { requireOperationActive(operationActive);
            requireCalendar(year in 2008..9998, "year", "calendar.year.invalid")
            runInterruptible { source.fetch(year) }.also {
                requireCalendar(it.metadata.year == year, "year", "calendar.year.mismatch")
                requireCalendar(it.holidays.isNotEmpty(), "holidays", "calendar.incomplete")
            }
        }
        return when (prepared) { is ApiResult.Failure -> prepared; is ApiResult.Success -> { currentCoroutineContext().ensureActive(); replace(prepared.value, true) } }
    }
    suspend fun import(source: DocumentRef): ApiResult<CalendarInfo> = when (val prepared = runner.io { requireOperationActive(operationActive); runInterruptible { documents.readCalendar(source) } }) {
        is ApiResult.Failure -> prepared
        is ApiResult.Success -> { currentCoroutineContext().ensureActive(); replace(prepared.value, false) }
    }
    private suspend fun replace(imported: CalendarImport, preservePublished: Boolean) = runner.write { requireOperationActive(operationActive);
        validate(imported, !preservePublished)
        var committed: WorkHistory? = null
        committer.commit(command, {
            transactions.writeCalendar { writer ->
                val previous = writer.calendarYear(imported.metadata.year)
                val metadata = if (preservePublished && previous?.metadata?.source == imported.metadata.source &&
                    previous.metadata.version == imported.metadata.version) previous.metadata else imported.metadata
                val value = imported.copy(metadata = metadata)
                validate(value)
                if (previous != null && previous.metadata == value.metadata && previous.holidays == value.holidays && previous.makeupDates == value.makeupDates)
                    MutationDelta(overview(writer))
                else {
                    writer.replaceCalendar(value)
                    writer.appendAudit(AuditRecord("calendar", metadata.year.toString(), value.rawJson.orEmpty(), clock.millis()))
                    correctedDelta(writer, overview(writer), setOf("calendar", "audit")) { committed = it }
                }
            }
        }) { committed?.let(afterCommit) }
    }
    suspend fun saveOverride(value: DateOverride) = runner.write { requireOperationActive(operationActive);
        SettingsRules.overrideWindows(value.toDomain()).firstOrNull()?.let { settingFailure(ErrorCode.VALIDATION, it.message, it.path) }
        var committed: WorkHistory? = null
        committer.commit(command, {
            transactions.writeCalendar { writer ->
                val domain = value.toDomain()
                SettingsRules.overrideWindows(domain).firstOrNull()?.let { settingFailure(ErrorCode.VALIDATION, it.message, it.path) }
                if (writer.dateOverride(value.date) == domain) MutationDelta(overview(writer))
                else {
                    writer.putDateOverride(domain)
                    writer.appendAudit(AuditRecord("override", value.date.toString(), value.windows.joinToString(",", "[", "]") { "[${it.startMinute},${it.endMinute}]" }, clock.millis()))
                    correctedDelta(writer, overview(writer), setOf("overrides", "override:${value.date}", "audit")) { committed = it }
                }
            }
        }) { committed?.let(afterCommit) }
    }
    suspend fun deleteOverride(date: LocalDate) = runner.write { requireOperationActive(operationActive);
        var committed: WorkHistory? = null
        committer.commit(command, {
            transactions.writeCalendar { writer ->
                if (writer.dateOverride(date) == null) MutationDelta(overview(writer))
                else {
                    writer.removeDateOverride(date)
                    writer.appendAudit(AuditRecord("override-delete", date.toString(), "", clock.millis()))
                    correctedDelta(writer, overview(writer), setOf("overrides", "override:$date", "audit")) { committed = it }
                }
            }
        }) { committed?.let(afterCommit) }
    }
    private fun overview(reader: CalendarReader) = reader.calendarOverview().let { CalendarInfo(frozenList(it.years.map { it.toContract() }), frozenList(it.overrides.map { it.toContract() })) }
    private fun validate(value: CalendarImport, published: Boolean = true) {
        val m = value.metadata
        requireCalendar(m.year in 1..9999, "year", "calendar.year.invalid")
        requireCalendar(m.source.isNotBlank(), "source", "calendar.source.required")
        requireCalendar(m.version.isNotBlank(), "version", "calendar.version.required")
        requireCalendar(value.holidays.keys.all { it.year == m.year } && value.makeupDates.all { it.year == m.year }, "year", "calendar.date.outside_year")
        requireCalendar(value.holidays.values.none { it.isBlank() }, "holidays", "holiday.name.required")
        requireCalendar(value.makeupDates.none { it in value.holidays }, "workdays", "calendar.conflict")
        if (published) try { LocalDate.parse(m.published) } catch (_: Exception) { settingFailure(ErrorCode.VALIDATION, "calendar.published.invalid", "published") }
    }
    private fun requireCalendar(valid: Boolean, field: String, message: String) { if (!valid) settingFailure(ErrorCode.VALIDATION, message, field) }
}
