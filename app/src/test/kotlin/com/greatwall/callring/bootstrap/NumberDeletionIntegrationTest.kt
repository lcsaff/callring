package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.conversation.operation.OperationId
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
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.conversation.execution.OperationScopePlanner
import com.greatwall.callring.application.callers.NumberDeletionService
import com.greatwall.callring.application.callers.NumberDeletionRecordingReads
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
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
    @Test fun deletingNumberPreservesArchiveAudioTranscriptAndAnalysis() = runBlocking {
        val graph = graph("delete_preserves_recording")
        try {
            graph.api.callers.saveProfile(SaveCallerProfile("31234","name","unit",null)).requiredValue()
            val call = graph.recordings.registrations.registerIncoming("session","31234",1,null,null)
            val old = graph.recordings.store.get(call.id)!!
            graph.recordings.directory.mkdirs()
            val audio = File(graph.recordings.directory,"keep.wav").apply { writeBytes(byteArrayOf(1,2,3)) }
            val archive = old.copy(generation=old.generation+1,audioPath=audio.path,status=ArchiveStatus.COMPLETE,
                transcript="keep transcript",analysis=CallAnalysis(summary="keep summary"),source=RecordingSource.SYSTEM)
            graph.recordings.store.compareAndSet(call.id,old.generation,archive)
            val version = graph.coordinator.capture().values.getValue("recording")
            val impact = graph.api.numberDeletion.impact("31234").requiredValue()
            graph.api.numberDeletion.delete(DeleteNumberCommand("31234",impact.callCount,impact.archiveCount,impact.widgetBindingCount)).requiredValue()
            assertFalse(graph.api.callers.numberDataImpact("31234").requiredValue().profileExists)
            assertEquals(archive,graph.recordings.store.get(call.id))
            assertArrayEquals(byteArrayOf(1,2,3),audio.readBytes())
            assertFalse(graph.recordings.store.isDeleted(call.id))
            assertEquals(version,graph.coordinator.capture().values.getValue("recording"))
        } finally { graph.close() }
    }
    @Test fun oldRecordingCannotRecreateDeletedProfileAfterStorageReopen() = runBlocking {
        val name="delete_before_enrichment"
        val graph=graph(name)
        graph.api.callers.saveProfile(SaveCallerProfile("31234","delete","unit",null)).requiredValue()
        graph.api.numberDeletion.delete(DeleteNumberCommand("31234",0,0,0)).requiredValue()
        graph.close()
        val context=RuntimeEnvironment.getApplication()
        com.greatwall.callring.data.database.SqliteCoreStorageResource(context,"$name.db").use { core ->
            val enrichment=com.greatwall.callring.application.callers.CallerEnrichmentService(core.sessions,
                com.greatwall.callring.application.coordination.BusinessRevisionCoordinator()) { true }
            enrichment.capture("31234",0L)
            assertNull(core.sessions.readCaller { it.caller("31234") })
            val cutoff=core.sessions.readCaller { it.callerDeletedAt("31234") }!!
            val fresh=enrichment.capture("31234",cutoff+1)
            assertNotNull(core.sessions.readCaller { it.caller("31234") })
            val old=enrichment.capture("31234",cutoff)
            assertNotEquals(fresh.epoch,old.epoch)
            enrichment.apply("31234",old,-1,1,mapOf("name" to ("old recording" to "old recording")))
            assertEquals("",core.sessions.readCaller { it.caller("31234") }!!.name)
        }
    }
    @Test fun impactDoesNotUseExplicitArchiveListing() = runBlocking {
        val graph = graph("deletion_metadata")
        try {
            graph.recordings.registrations.registerIncoming("session", "31234", 1, null, null)
            assertEquals(1, deletion(graph).impact("31234").requiredValue().archiveCount)
            val counts = graph.recordings.store.readCounters.snapshot()
            assertEquals(0L, counts["fullArchiveDecodes"] ?: 0L)
            assertEquals(0L, counts["listArchiveDecodes"] ?: 0L)
        } finally { graph.close() }
    }

    @Test fun deletionScopesProtectBindingsWithoutRecordingMutationScope() = runBlocking {
        val graph = graph("delete_scopes")
        try {
            val call = ConversationToolCall("c", "callers_deleteNumber", aiObject("number" to aiString("31234")))
            val scopes = OperationScopePlanner(graph.queries, graph.coordinator).prepare(call, OperationId.CALLERS_DELETE_NUMBER)
            assertTrue("preferences" in scopes.keys); assertFalse("recording" in scopes.keys)
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
            val scopes = OperationScopePlanner(graph.queries, graph.coordinator).prepare(call, OperationId.CALLERS_DELETE_NUMBER)
            val command = CommandContext("delete", graph.coordinator.capture(), 1,
                expectedScopes = scopes, continueOwnCommits = true) { true }
            var lists = 0
            val result = deletion(graph, command = command, bindingsRead = { number ->
                val snapshot = targetBindings(graph, number)
                if (++lists == 2) graph.api.widgets.saveBinding(1, "39999").requiredValue()
                snapshot
            })
                .delete(DeleteNumberCommand("31234", 0, 0, 1))
            assertTrue(result is ApiResult.Failure)
            assertEquals("39999", graph.api.widgets.getBinding(1).requiredValue()!!.number)
        } finally { graph.close() }
    }
    @Test fun replacementWithSameCountAfterMetadataReadIsNotDeleted() = runBlocking {
        val graph = graph("delete_replaced_generation")
        try {
            graph.api.callers.saveProfile(SaveCallerProfile("31234", "keep", "unit", null)).requiredValue()
            graph.recordings.store.compareAndSet(-1, null, CallArchive(-1, "31234", 1, generation = 2, status = ArchiveStatus.READY))
            var reads = 0
            val service = deletion(graph, bindingsRead = { number ->
                if (++reads == 1) assertTrue(graph.recordings.store.compareAndSet(-1, 2,
                    CallArchive(-1, "31234", 1, generation = 3, status = ArchiveStatus.COMPLETE)))
                targetBindings(graph, number)
            })
            service.delete(DeleteNumberCommand("31234", 0, 1, 0)).requiredValue()
            assertEquals(3L, graph.recordings.store.get(-1)!!.generation)
            assertFalse(graph.api.callers.numberDataImpact("31234").requiredValue().profileExists)
        } finally { graph.close() }
    }
    @Test fun oneThousandUnrelatedArchiveAndBindingReadRange() = accessRange(1000)
    @Test fun tenThousandUnrelatedArchiveAndBindingReadRange() = accessRange(10000)
    private fun accessRange(size: Int): Unit = runBlocking {
        val graph = graph("delete_range_$size")
        try {
            val context = RuntimeEnvironment.getApplication()
            graph.recordings.store.scopedReads()
            val dbPath = context.getDatabasePath(graph.configuration.databaseName + "_recording.db")
            android.database.sqlite.SQLiteDatabase.openDatabase(dbPath.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.beginTransaction()
                try {
                    val index = com.greatwall.callring.data.recording.ArchiveProjectionIndex(db)
                    for (id in 1L..size.toLong()) {
                        val row = CallArchive(id, "39999", 1, generation = 2, status = ArchiveStatus.COMPLETE)
                        db.execSQL("INSERT INTO archives VALUES(?,?,?)", arrayOf<Any>(id, 2, com.greatwall.callring.data.recording.RecordingJson.archive(row).toString()))
                        index.write(row)
                    }
                    val audio = File(graph.recordings.directory, "target.wav").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(73)) }
                    val row = CallArchive(-1, "3\u200B1[2]3/4", 10, generation = 2, status = ArchiveStatus.COMPLETE,
                        audioPath = audio.path, transcript = "unrelated text".repeat(100000))
                    db.execSQL("INSERT INTO archives VALUES(?,?,?)", arrayOf<Any>(-1, 2, com.greatwall.callring.data.recording.RecordingJson.archive(row).toString()))
                    index.write(row)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            val stored = context.getSharedPreferences(graph.configuration.widgetPreferencesName, 0)
            stored.edit().apply {
                for (id in 1..size) putString("number_$id", "39999")
                putString("number_${size + 1}", "3\u200B1[2]3/4")
                putString("number_${size + 2}", "١/٢/٣")
            }.commit()
            var enumerated = 0L
            var enumerations = 0L
            val measured = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader,
                arrayOf(android.content.SharedPreferences::class.java)) { _, method, arguments ->
                val value = method.invoke(stored, *(arguments ?: emptyArray()))
                if (method.name == "getAll") { enumerations++; enumerated += (value as Map<*, *>).size }
                value
            } as android.content.SharedPreferences
            val preferences = com.greatwall.callring.data.preferences.AndroidPreferences(stored, measured, graph.configuration.defaultAppearance)
            val widgets = com.greatwall.callring.application.widget.WidgetBindingsService(preferences,
                { error("installed widgets are not a deletion dependency") }, graph.coordinator, kotlinx.coroutines.Dispatchers.Unconfined, operationActive = { true })
            val service = NumberDeletionService(graph.backend.callers::numberDataImpact,
                { _, _, _ -> error("preview cannot write core") }, graph.recordings.service,
                widgets::bindingsForNumber, { _, _ -> error("preview cannot write widgets") }, graph.coordinator, active = { true })
            val impact = service.impact("31234").requiredValue()
            assertEquals(1, impact.archiveCount); assertEquals(73L, impact.audioBytes); assertEquals(1, impact.widgetBindingCount)
            assertEquals(1L, enumerations); assertEquals(size + 2L, enumerated)
            assertEquals(listOf(size + 2), preferences.bindingsForNumber("١٢٣").map { it.widgetId })
            val counts = graph.recordings.store.readCounters.snapshot()
            assertEquals(size + 1L, counts["warmNumberRows"])
            assertEquals(1L, counts["targetMetadataRows"])
            assertEquals(1L, counts["payloadMetadataRows"])
            assertEquals(0L, counts["fullArchiveDecodes"] ?: 0L)
            assertEquals(0L, counts["listArchiveDecodes"] ?: 0L)
            File("build/qa/layer-boundaries/t10b3-number-access-$size.json").apply {
                parentFile!!.mkdirs()
                writeText(org.json.JSONObject(counts + mapOf("storedBindingEntriesPerEnumeration" to (size + 2L),
                    "previewBindingEnumerations" to 1L, "installedWidgetReads" to 0L, "returnedBindings" to 1L)).toString(2))
            }
        } finally { graph.close() }
    }
    private fun targetBindings(graph: BackendGraph, number: String): ApiResult<List<WidgetBinding>> {
        val context = RuntimeEnvironment.getApplication()
        val preferences = com.greatwall.callring.data.preferences.AndroidPreferences(
            context.getSharedPreferences(graph.configuration.appearancePreferencesName, 0),
            context.getSharedPreferences(graph.configuration.widgetPreferencesName, 0), graph.configuration.defaultAppearance)
        return ApiResult.Success(preferences.bindingsForNumber(number))
    }
    private fun deletion(graph: BackendGraph, command: CommandContext? = null,
        bindingsRead: suspend (String) -> ApiResult<List<WidgetBinding>> = { targetBindings(graph, it) },
    ) = NumberDeletionService(graph.backend.callers::numberDataImpact,
        { number, count, cmd -> graph.backend.commandApi(cmd).callers.deleteNumberCore(number, count) },
        object : NumberDeletionRecordingReads {
            override suspend fun archives(number: String) = graph.recordings.service.numberArchives(number)
        }, bindingsRead,
        { binding, cmd -> graph.backend.commandApi(cmd).widgets.removeBinding(binding.widgetId) }, graph.coordinator, command, active = { true })
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
        return BackendGraph(com.greatwall.callring.bootstrap.BackupGraphOwner { _, _ -> throw AssertionError("Unexpected fixture restore") }, context, configuration, notifications)
    }

    @Test fun guardedDeletionPreservesAudioWhileRemovingCoreAndMultipleBindings() = runBlocking {
        val graph = graph("delete_composite")
        try {
            graph.api.callers.saveProfile(SaveCallerProfile("31234", "name", "unit", null)).requiredValue()
            for (id in 1..2) graph.api.widgets.saveBinding(id, "31234").requiredValue()
            val call = graph.recordings.registrations.registerIncoming("session", "31234", 1, null, null)
            val old = graph.recordings.store.get(call.id)!!
            graph.recordings.directory.mkdirs()
            val audio = File(graph.recordings.directory, "fixture.wav").apply { writeBytes(byteArrayOf(1, 2)) }
            assertTrue(graph.recordings.store.compareAndSet(call.id, old.generation,
                old.copy(generation = old.generation + 1, audioPath = audio.path, status = ArchiveStatus.READY)))
            val context = CommandContext("delete", graph.coordinator.capture(), 1) { true }
            graph.api.commandApi(context).numberDeletion.delete(DeleteNumberCommand("31234", 0, 1, 2)).requiredValue()
            assertFalse(graph.api.callers.numberDataImpact("31234").requiredValue().profileExists)
            assertTrue(graph.api.widgets.listBindings().requiredValue().stored.isEmpty())
            assertEquals(1,graph.api.recordings.queryArchives().requiredValue().items.size)
            assertTrue(audio.exists())
        } finally { graph.close() }
    }

    @Test fun archiveOnlyRemnantsDoNotRequireAProfile() = runBlocking {
        val graph = graph("delete_archive_only")
        try {
            graph.recordings.registrations.registerIncoming("session", "31234", 1, null, null)
            assertTrue(graph.api.numberDeletion.delete(DeleteNumberCommand("31234", 0, 1, 0)) is ApiResult.Failure)
            assertEquals(1,graph.api.recordings.queryArchives().requiredValue().items.size)
        } finally { graph.close() }
    }

    @Test fun failedAudioRemovalRetainsArchiveForRetry() = runBlocking {
        val graph = graph("delete_audio_failure")
        try {
            val call = graph.recordings.registrations.registerIncoming("session", "31234", 1, null, null)
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
