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
    var now = 100000000L
    private val store = object : BackendStore {
            override fun <T> read(block: (BackendState) -> T) = block(state)
            override fun <T> transaction(block: (BackendState) -> T) = block(state)
            override fun <T> captureFull(block: (BackendState, List<AuditRecord>) -> T) = block(state, emptyList())
        }
    val backend = CallringBackend(
        store,
        object : PreferenceStore {
            override fun getAppearance() = appearance
            override fun saveAppearance(settings: AppearanceSettings) { appearance = settings }
            override fun getBinding(widgetId: Int) = bindings[widgetId]
            override fun saveBinding(binding: WidgetBinding) { bindings[binding.widgetId] = binding }
            override fun removeBinding(widgetId: Int) { bindings.remove(widgetId) }
            override fun listBindings() = bindings.values.toList()
        },
        object : DocumentStore {
            override fun <T> read(source: DocumentRef, block: (InputStream) -> T) = ByteArrayInputStream(byteArrayOf()).use(block)
            override fun <T> write(destination: DocumentRef, block: (OutputStream) -> T) = ByteArrayOutputStream().use(block)
            override fun readCalendar(source: DocumentRef) = CalendarImport(CalendarYear(2026, "fixture", "2026-01-01", "1").toDomain(), emptyMap(), emptySet())
        },
        object : RuntimeProbe {
            override fun getStatus() = RuntimeStatus(Capability.values().associateWith { AccessState.GRANTED }, Audibility.AUDIBLE, PhoneActivity.IDLE, PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE), true, now)
            override fun getDiagnostics() = emptyList<DiagnosticEntry>()
        },
        object : AudioController {
            override fun getSettings() = ringtone
            override fun setEnabled(enabled: Boolean) = ringtone.copy(enabled = enabled).also { ringtone = it }
            override fun importAudio(source: DocumentRef, checkCancellation: () -> Unit): RingtoneSettings { checkCancellation(); return ringtone.copy(source = AudioSource.IMPORTED).also { ringtone = it } }
            override fun restoreBuiltIn() = ringtone.copy(source = AudioSource.BUILT_IN).also { ringtone = it }
            override fun startPreview() = Unit
            override fun stopPlayback() = Unit
        },
        object : SyncScheduler { override fun requestSync(atEpochMillis: Long) = SyncRequestReceipt(SyncRequestState.QUEUED, atEpochMillis) },
        Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), coordinator = coordinator, quietTransactions = MemoryQuietTransactions(store),
    )
}
