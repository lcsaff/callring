package com.greatwall.callring

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.io.*
import java.time.*

class ConversationFixtures {
    val coordinator = BusinessRevisionCoordinator()
    val state = BackendState().apply { schedules += defaultWorkSchedule("UTC"); nextConfigVersion = 2 }
    val bindings = linkedMapOf<Int, WidgetBinding>()
    var appearance = AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM)
    var ringtone = RingtoneSettings(false, AudioSource.BUILT_IN, "built-in", 8)
    var capabilities = Capability.entries.associateWith { AccessState.GRANTED }
    var now = 100000000L
    private val store = object : BackendStore {
            override fun <T> read(block: (BackendState) -> T) = block(state)
            override fun <T> transaction(block: (BackendState) -> T) = block(state)
        }
    val backend = CallringBackend(
        object : PreferenceStore {
            override fun getAppearance() = appearance
            override fun saveAppearance(settings: AppearanceSettings) { appearance = settings }
            override fun getBinding(widgetId: Int) = bindings[widgetId]
            override fun saveBinding(binding: WidgetBinding) { bindings[binding.widgetId] = binding }
            override fun removeBinding(widgetId: Int) { bindings.remove(widgetId) }
            override fun bindingsForNumber(number: String) = listBindings().filter { com.greatwall.callring.application.sameRecordingNumber(it.number, number) }
            override fun listBindings() = bindings.values.toList()
        },
        object : DocumentStore {
            override fun <T> read(source: DocumentRef, block: (InputStream) -> T) = ByteArrayInputStream(byteArrayOf()).use(block)
            override fun <T> write(destination: DocumentRef, block: (OutputStream) -> T) = ByteArrayOutputStream().use(block)
            override fun readCalendar(source: DocumentRef) = CalendarImport(CalendarYear(2026, "fixture", "2026-01-01", "1").toDomain(), emptyMap(), emptySet())
        },
        object : RuntimeProbe {
            override fun getInstalledWidgets() = com.greatwall.callring.contracts.widget.WidgetInstallState(emptyList(), AccessState.GRANTED)
            override fun getStatus() = RuntimeStatus(capabilities, Audibility.AUDIBLE, PhoneActivity.IDLE, PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE), true, now)
            override fun getDiagnostics() = emptyList<DiagnosticEntry>()
        },
        object : AudioController {
            override fun getSettings() = ringtone
            override fun setEnabled(enabled: Boolean) = ringtone.copy(enabled = enabled).also { ringtone = it }
            override fun prepareAudio(source: DocumentRef, checkCancellation: () -> Unit): PreparedRingtoneImport {
                checkCancellation()
                return object : PreparedRingtoneImport {
                    override fun commit(checkCancellation: () -> Unit): RingtoneSettings {
                        checkCancellation()
                        return ringtone.copy(source = AudioSource.IMPORTED).also { ringtone = it }
                    }
                    override fun close() {}
                }
            }
            override fun restoreBuiltIn() = ringtone.copy(source = AudioSource.BUILT_IN).also { ringtone = it }
            override fun startPreview() = Unit
            override fun stopPlayback() = Unit
        },
        object : SyncScheduler { override fun requestSync(atEpochMillis: Long) = SyncRequestReceipt(SyncRequestState.QUEUED, atEpochMillis) },
        Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), coordinator = coordinator, coreTransactions = MemoryQuietTransactions(store), numberRecordings = com.greatwall.callring.EmptyNumberRecordings, identityArchive = { com.greatwall.callring.contracts.common.ApiResult.Success(null) }, operationActive = { true }, conversationProvider = { com.greatwall.callring.testing.FixtureConversationApi }, aiSettingsProvider = { com.greatwall.callring.testing.FixtureAiSettingsApi }, updateProvider = { com.greatwall.callring.testing.FixtureAppUpdateApi }, snapshotsProvider = { com.greatwall.callring.testing.FixtureAiSnapshotApi }, recordingsProvider = { com.greatwall.callring.testing.FixtureRecordingApi }, calendarSource = com.greatwall.callring.application.ports.CalendarSource { throw AssertionError("Unexpected calendar source") }, persistenceActive = { true })
}

/** Write-only tests must supply the source capability explicitly and reject accidental reads. */
fun unusedToolQueries() = com.greatwall.callring.application.conversation.QueryService(listOf(object : com.greatwall.callring.application.ports.ScopedReadPort {
    override val domains = com.greatwall.callring.contracts.ai.AiDomain.entries.toSet()
    override fun version(domain: com.greatwall.callring.contracts.ai.AiDomain) = 0L
    override fun query(spec: com.greatwall.callring.contracts.ai.QuerySpec, after:String?):com.greatwall.callring.application.ports.SourcePage = error("Unexpected source query in write-only test")
}))
