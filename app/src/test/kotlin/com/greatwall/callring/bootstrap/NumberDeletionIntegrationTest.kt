package com.greatwall.callring.bootstrap

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.application.conversation.OperationScopePlanner
import com.greatwall.callring.platform.callers.NumberDeletionService
import com.greatwall.callring.platform.UiNotificationEntryPoints
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NumberDeletionIntegrationTest {
    @Test fun archiveFailureKeepsOriginalNewestFirstOrderAndStopsLaterSteps() = runBlocking {
        val graph = graph("delete_archive_order")
        try {
            graph.api.callers.saveProfile(SaveCallerProfile("31234", "keep", "unit", null)).requiredValue()
            graph.api.widgets.saveBinding(1, "31234").requiredValue()
            for ((id, started) in listOf(-1L to 300L, -2L to 300L, -3L to 100L)) {
                graph.recordings.store.compareAndSet(id, null, CallArchive(id, "31234", started, generation = 2, status = ArchiveStatus.READY))
            }
            val visited = mutableListOf<Long>()
            val recordings = object : RecordingApi by graph.recordings {
                override suspend fun delete(callId: Long): ApiResult<Unit> {
                    visited += callId
                    if (callId == -1L) return ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "fixture.failure"))
                    return graph.recordings.delete(callId)
                }
            }
            val result = NumberDeletionService(graph.backend.callers, recordings, graph.backend.widgets, graph.recordings::numberArchives)
                .delete(DeleteNumberCommand("31234", 0, 3, 1))
            assertTrue(result is ApiResult.Failure)
            assertEquals(listOf(-2L, -1L), visited)
            assertNull(graph.recordings.store.get(-2))
            assertNotNull(graph.recordings.store.get(-3))
            assertNotNull(graph.recordings.store.get(-1))
            assertEquals("keep", graph.api.callers.getEditor("31234").requiredValue().draft.name)
            assertNotNull(graph.api.widgets.getBinding(1).requiredValue())
        } finally { graph.close() }
    }

    @Test fun impactDoesNotUseExplicitArchiveListing() = runBlocking {
        val graph = graph("deletion_metadata")
        try {
            graph.recordings.store.registerIncoming("session", "31234", 1, null)
            var lists = 0
            val recordings = object : RecordingApi by graph.recordings {
                override suspend fun listArchives(): ApiResult<List<CallArchive>> {
                    lists++
                    return graph.recordings.listArchives()
                }
            }
            assertEquals(1, NumberDeletionService(graph.backend.callers, recordings, graph.backend.widgets, graph.recordings::numberArchives).impact("31234").requiredValue().archiveCount)
            assertEquals(0, lists)
        } finally { graph.close() }
    }

    @Test fun foreignProfileEditAfterArchiveCleanupMustBePreserved() = runBlocking {
        val graph = graph("delete_profile_edited")
        try {
            graph.api.callers.saveProfile(SaveCallerProfile("31234", "original", "unit", null)).requiredValue()
            graph.recordings.store.registerIncoming("session", "31234", 1, null)
            val call = ConversationToolCall("c", "callers_deleteNumber", aiObject("number" to aiString("31234")))
            val scopes = OperationScopePlanner(graph.queries, graph.coordinator).prepare(call, 81)
            val command = CommandContext("delete", graph.coordinator.capture(), 1,
                expectedScopes = scopes, continueOwnCommits = true) { true }
            val bound = graph.api.commandApi(command)
            val recordings = object : RecordingApi by bound.recordings {
                override suspend fun delete(callId: Long): ApiResult<Unit> {
                    val result = bound.recordings.delete(callId)
                    graph.api.callers.saveProfile(SaveCallerProfile("31234", "edited elsewhere", "unit", null)).requiredValue()
                    return result
                }
            }
            val result = NumberDeletionService(bound.callers, recordings, bound.widgets, graph.recordings::numberArchives)
                .delete(DeleteNumberCommand("31234", 0, 1, 0))
            assertTrue(result is ApiResult.Failure)
            assertEquals("edited elsewhere", graph.api.callers.getEditor("31234").requiredValue().draft.name)
        } finally { graph.close() }
    }
    @Test fun deletionScopesProtectArchiveAndBindingCollectionMembership() = runBlocking {
        val graph = graph("delete_scopes")
        try {
            val call = ConversationToolCall("c", "callers_deleteNumber", aiObject("number" to aiString("31234")))
            val scopes = OperationScopePlanner(graph.queries, graph.coordinator).prepare(call, 81)
            assertTrue(scopes.keys.containsAll(setOf("recording", "preferences")))
            graph.api.widgets.saveBinding(1, "31234").requiredValue()
            val command = CommandContext("delete", graph.coordinator.capture(), 1, expectedScopes = scopes) { true }
            assertTrue(graph.api.commandApi(command).numberDeletion.delete(DeleteNumberCommand("31234", 0, 0, 1)) is ApiResult.Failure)
            assertEquals("31234", graph.api.widgets.getBinding(1).requiredValue()!!.number)
        } finally { graph.close() }
    }

    @Test fun foreignWidgetReassignmentAfterCoreDeletionMustBePreserved() = runBlocking {
        val graph = graph("delete_reassigned")
        try {
            graph.api.callers.saveProfile(SaveCallerProfile("31234", "name", "unit", null)).requiredValue()
            graph.api.widgets.saveBinding(1, "31234").requiredValue()
            val call = ConversationToolCall("c", "callers_deleteNumber", aiObject("number" to aiString("31234")))
            val scopes = OperationScopePlanner(graph.queries, graph.coordinator).prepare(call, 81)
            val command = CommandContext("delete", graph.coordinator.capture(), 1,
                expectedScopes = scopes, continueOwnCommits = true) { true }
            val bound = graph.api.commandApi(command)
            var lists = 0
            val widgets = object : WidgetBindingsApi by bound.widgets {
                override suspend fun listBindings(): ApiResult<WidgetBindingsListing> {
                    val snapshot = bound.widgets.listBindings()
                    if (++lists == 2) graph.api.widgets.saveBinding(1, "39999").requiredValue()
                    return snapshot
                }
            }
            val result = NumberDeletionService(bound.callers, bound.recordings, widgets, graph.recordings::numberArchives)
                .delete(DeleteNumberCommand("31234", 0, 0, 1))
            assertTrue(result is ApiResult.Failure)
            assertEquals("39999", graph.api.widgets.getBinding(1).requiredValue()!!.number)
        } finally { graph.close() }
    }
    private fun graph(name: String): BackendGraph {
        val context = RuntimeEnvironment.getApplication()
        val configuration = BackendConfiguration(
            AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM),
            databaseName = "$name.db", ringPreferencesName = "${name}_ring",
            widgetPreferencesName = "${name}_widgets", appearancePreferencesName = "${name}_appearance")
        val notifications = object : UiNotificationEntryPoints {
            override fun home(requestCode: Int) = PendingIntent.getActivity(context, requestCode, Intent("fixture"), PendingIntent.FLAG_IMMUTABLE)
            override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
        }
        return BackendGraph(context, configuration, notifications, object : IncomingCallUiSink {
            override fun show(request: IncomingCallUiRequest) = Unit
        })
    }

    @Test fun guardedDeletionRemovesArchivesAudioCoreAndMultipleBindings() = runBlocking {
        val graph = graph("delete_composite")
        try {
            graph.api.callers.saveProfile(SaveCallerProfile("31234", "name", "unit", null)).requiredValue()
            for (id in 1..2) graph.api.widgets.saveBinding(id, "31234").requiredValue()
            val call = graph.recordings.store.registerIncoming("session", "31234", 1, null)
            val old = graph.recordings.store.get(call.id)!!
            graph.recordings.directory.mkdirs()
            val audio = File(graph.recordings.directory, "fixture.wav").apply { writeBytes(byteArrayOf(1, 2)) }
            assertTrue(graph.recordings.store.compareAndSet(call.id, old.generation,
                old.copy(generation = old.generation + 1, audioPath = audio.path, status = ArchiveStatus.READY)))
            val context = CommandContext("delete", graph.coordinator.capture(), 1) { true }
            graph.api.commandApi(context).numberDeletion.delete(DeleteNumberCommand("31234", 0, 1, 2)).requiredValue()
            assertFalse(graph.api.callers.numberDataImpact("31234").requiredValue().profileExists)
            assertTrue(graph.api.widgets.listBindings().requiredValue().stored.isEmpty())
            assertTrue(graph.api.recordings.listArchives().requiredValue().isEmpty())
            assertFalse(audio.exists())
        } finally { graph.close() }
    }

    @Test fun archiveOnlyRemnantsDoNotRequireAProfile() = runBlocking {
        val graph = graph("delete_archive_only")
        try {
            graph.recordings.store.registerIncoming("session", "31234", 1, null)
            graph.api.numberDeletion.delete(DeleteNumberCommand("31234", 0, 1, 0)).requiredValue()
            assertTrue(graph.api.recordings.listArchives().requiredValue().isEmpty())
        } finally { graph.close() }
    }

    @Test fun failedAudioRemovalRetainsArchiveForRetry() = runBlocking {
        val graph = graph("delete_audio_failure")
        try {
            val call = graph.recordings.store.registerIncoming("session", "31234", 1, null)
            val old = graph.recordings.store.get(call.id)!!
            val audio = File(graph.recordings.directory, "blocked.wav").apply { mkdirs() }
            val child = File(audio, "obstruction").apply { writeText("fixture") }
            assertTrue(graph.recordings.store.compareAndSet(call.id, old.generation,
                old.copy(generation = old.generation + 1, audioPath = audio.path, status = ArchiveStatus.READY)))
            assertTrue(graph.api.recordings.delete(call.id) is ApiResult.Failure)
            assertNotNull(graph.recordings.store.get(call.id))
            assertTrue(child.delete())
            graph.api.recordings.delete(call.id).requiredValue()
            assertFalse(audio.exists())
            assertNull(graph.recordings.store.get(call.id))
        } finally { graph.close() }
    }
}
