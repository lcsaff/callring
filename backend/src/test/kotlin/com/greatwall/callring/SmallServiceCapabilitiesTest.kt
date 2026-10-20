package com.greatwall.callring

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ringtone.RingtoneService
import com.greatwall.callring.application.runtime.RuntimeService
import com.greatwall.callring.application.widget.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.widget.*
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SmallServiceCapabilitiesTest {
    private fun <T> ApiResult<T>.ok() = (this as ApiResult.Success).value
    private fun ApiResult<*>.error() = (this as ApiResult.Failure).error
    private fun status(access: AccessState = AccessState.GRANTED, phone: PhoneActivity = PhoneActivity.IDLE, audible: Audibility = Audibility.AUDIBLE) =
        RuntimeStatus(Capability.entries.associateWith { access }, audible, phone, PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE), true, 1)

    @Test fun widgetServiceOnlyUsesBindingPortAndPublishesActualEntityOnce() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val rows = linkedMapOf<Int, WidgetBinding>()
        var fail = false
        var installedReads = 0
        val store = object : WidgetBindingStore {
            override fun listBindings() = rows.values.toList()
            override fun getBinding(widgetId: Int) = rows[widgetId]
            override fun saveBinding(binding: WidgetBinding) {
                if (fail) throw BackendFailure(ApiError(ErrorCode.STORAGE_FAILURE, "fixture.write_failed"))
                rows[binding.widgetId] = binding
            }
            override fun removeBinding(widgetId: Int) { rows.remove(widgetId) }
        }
        val service = WidgetBindingsService(store, { installedReads++; WidgetInstallState(listOf(7), AccessState.GRANTED) }, coordinator, Dispatchers.Unconfined)
        service.saveBinding(7, "30001").ok()
        service.saveBinding(7, "30001").ok()
        service.removeBinding(8).ok()
        assertEquals(1L, coordinator.marker())
        assertEquals(0, installedReads)
        assertEquals(1L, coordinator.lastChange(setOf("widgets", "widget:7")))
        assertEquals(0L, coordinator.lastChange(setOf("widget:8")))
        assertEquals(setOf("7"), coordinator.invalidations.snapshots.value.domains.getValue(DataDomain.WIDGET_BINDINGS).entityRevisions.keys)
        fail = true
        assertEquals(ErrorCode.STORAGE_FAILURE, service.saveBinding(7, "30002").error().code)
        assertEquals("30001", service.getBinding(7).ok()!!.number)
        assertEquals(1L, coordinator.marker())
        assertEquals(listOf(7), service.listBindings().ok().installed.ids)
        assertEquals(1, installedReads)
    }

    @Test fun runtimeGuardPrecedesPermissionAndScheduleAndReadsNeverPublish() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val order = mutableListOf<String>()
        var granted = false
        val service = RuntimeService({ order += "permission"; status(if (granted) AccessState.GRANTED else AccessState.DENIED) }, { emptyList() },
            object : SyncScheduler { override fun requestSync(atEpochMillis: Long): SyncRequestReceipt {
                order += "schedule"
                assertEquals(42L, atEpochMillis)
                return SyncRequestReceipt(SyncRequestState.QUEUED, atEpochMillis)
            } }, Clock.fixed(Instant.ofEpochMilli(42), ZoneOffset.UTC), Dispatchers.Unconfined, coordinator)
        val stopped = CommandContext("stopped", coordinator.capture(), 1) { order += "guard"; false }
        assertEquals(ErrorCode.CONFLICT, service.bound(stopped).requestCallLogSync().error().code)
        assertEquals(listOf("guard"), order)
        order.clear()
        assertEquals(ErrorCode.PERMISSION_REQUIRED, service.requestCallLogSync().error().code)
        assertEquals(listOf("permission"), order)
        granted = true
        order.clear()
        service.requestCallLogSync().ok()
        assertEquals(listOf("permission", "schedule"), order)
        service.getStatus().ok()
        service.getDiagnostics().ok()
        assertEquals(0L, coordinator.marker())
    }

    private class Audio : AudioController {
        var current = RingtoneSettings(false, AudioSource.BUILT_IN, "fixture", 8)
        var prepares = 0
        var commits = 0
        var closes = 0
        var stops = 0
        var fail = false
        var duringPrepare: () -> Unit = {}
        override fun getSettings() = current
        override fun setEnabled(enabled: Boolean) = current.copy(enabled = enabled).also { current = it }
        override fun prepareAudio(source: DocumentRef, checkCancellation: () -> Unit): PreparedRingtoneImport {
            prepares++
            duringPrepare()
            checkCancellation()
            return object : PreparedRingtoneImport {
                override fun commit(checkCancellation: () -> Unit): RingtoneSettings {
                    checkCancellation()
                    if (fail) throw BackendFailure(ApiError(ErrorCode.IO_FAILURE, "fixture.failed"))
                    commits++
                    return current.copy(source = AudioSource.IMPORTED).also { current = it }
                }
                override fun close() { closes++ }
            }
        }
        override fun restoreBuiltIn() = current.copy(source = AudioSource.BUILT_IN).also { current = it }
        override fun startPreview() {}
        override fun stopPlayback() { stops++ }
    }

    @Test fun ringtoneImportPreparationIsOutsideFenceAndSameMetadataStillPublishes() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val audio = Audio()
        audio.duringPrepare = { assertFalse(Thread.holdsLock(coordinator.fence)) }
        val service = RingtoneService(audio, { status() }, coordinator, Dispatchers.Unconfined)
        service.importAudio(DocumentRef("fixture")).ok()
        service.importAudio(DocumentRef("fixture")).ok()
        assertEquals(2L, coordinator.marker())
        assertEquals(2L, coordinator.capture().values.getValue("ringtone"))
        assertEquals(0L, coordinator.capture().values.getValue("core"))
        assertEquals(setOf(DataDomain.RINGTONE), coordinator.invalidations.snapshots.value.domains.keys)
        audio.fail = true
        assertEquals(ErrorCode.IO_FAILURE, service.importAudio(DocumentRef("fixture")).error().code)
        assertEquals(2L, coordinator.marker())
        assertEquals(3, audio.closes)
        service.restoreBuiltIn().ok()
        service.restoreBuiltIn().ok()
        service.setEnabled(false).ok()
        assertEquals(3L, coordinator.marker())
    }

    @Test fun ringtoneCommitRechecksCommandAfterPreparationAndPreviewChecksRealRestrictions() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        val audio = Audio()
        var active = true
        val service = RingtoneService(audio, { status() }, coordinator, Dispatchers.Unconfined)
        val command = CommandContext("import", coordinator.capture(), 1) { active }
        audio.duringPrepare = { active = false }
        assertEquals(ErrorCode.CONFLICT, service.bound(command).importAudio(DocumentRef("fixture")).error().code)
        assertEquals(0, audio.commits)
        assertEquals(1, audio.closes)
        assertEquals(0L, coordinator.marker())
        val denied = RingtoneService(audio, { status(AccessState.DENIED) }, coordinator, Dispatchers.Unconfined)
        assertEquals(ErrorCode.PERMISSION_REQUIRED, denied.startPreview().error().code)
        val phone = RingtoneService(audio, { status(phone = PhoneActivity.RINGING) }, coordinator, Dispatchers.Unconfined)
        assertEquals("audio.phone_active", phone.startPreview().error().message)
        val inaudible = RingtoneService(audio, { status(audible = Audibility.SILENT) }, coordinator, Dispatchers.Unconfined)
        assertEquals("audio.system_inaudible", inaudible.startPreview().error().message)
        service.startPreview().ok()
        service.stopPlayback().ok()
        assertEquals(0L, coordinator.marker())
    }
}
