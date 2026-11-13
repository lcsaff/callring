package com.greatwall.callring

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import android.test.InstrumentationTestCase
import com.greatwall.callring.application.callers.CallerEnrichmentService
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.ports.recording.*
import com.greatwall.callring.application.recording.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.domain.calling.*
import com.greatwall.callring.platform.calling.AndroidCallLogSource
import com.greatwall.callring.platform.recording.SystemRecordingScanner
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/** Opt-in real metadata proof. Stores only in a fresh backendqa namespace, never the main app. */
class RealIncomingRecordingDeviceTest : InstrumentationTestCase() {
    fun testRealIncomingHistoryAndRecordings(): Unit = runBlocking {
        val context = instrumentation.targetContext
        check(context.packageName == "com.greatwall.callring.backendqa")
        val optionsFile = File(context.getExternalFilesDir(null), "real-recording-options.json")
        check(optionsFile.isFile) { "qa.explicit_recording_options_required" }
        val options = JSONObject(optionsFile.readText())
        val tree = Uri.parse(options.getString("tree"))
        val proofLogs = options.getJSONArray("proofLogIds").let { values -> (0 until values.length()).map { values.getString(it) }.toSet() }
        check(proofLogs.isNotEmpty() && proofLogs.size <= 3)
        val alreadyGranted = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        check(alreadyGranted) { "qa.explicit_recording_tree_grant_required" }
        val namespace = "realrecording_${System.nanoTime()}"
        var modelCalls = 0
        val config = BackendConfiguration(AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM),
            databaseName = namespace, ringPreferencesName = namespace + "_ring", widgetPreferencesName = namespace + "_widgets", appearancePreferencesName = namespace + "_appearance")
        val notifications = object : UiNotificationEntryPoints {
            override fun home(requestCode: Int) = PendingIntent.getActivity(context, requestCode, Intent("qa.fixture"), PendingIntent.FLAG_IMMUTABLE)
            override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
        }
        val client = object : AiClient {
            override fun operation(cancelled: () -> Boolean) = this
            override fun cancel() = Unit
            override fun test(connection: AiConnection) { modelCalls++; error("qa.no_model_calls") }
            override fun analyze(connection: AiConnection, transcript: String): CallAnalysis { modelCalls++; error("qa.no_model_calls") }
        }
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_CALL_LOG)
            val calls = AndroidCallLogSource(context.contentResolver).read(0, System.currentTimeMillis() + 1)
            check(calls.isNotEmpty())
            BackendGraph(BackupGraphOwner { _, _ -> error("qa.no_restore") }, context, config, notifications).use { graph ->
                graph.backend.prepareNumberRule().requiredValue()
                graph.backend.importSystemCalls(calls)
                graph.backend.importSystemCalls(calls)
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(namespace).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*),SUM(counted) FROM calls WHERE hidden=0", null).use { rows ->
                    check(rows.moveToFirst()); assertEquals(calls.size, rows.getInt(0)); assertEquals(0, rows.getInt(1))
                }
            }
            SqliteCoreStorageResource(context, namespace).use { core ->
                val proofCalls = core.sessions.readCalling { it.providerCalls(proofLogs).map { row -> row.id }.toSet() }
                assertEquals(proofLogs.size, proofCalls.size)
                val coordinator = BusinessRevisionCoordinator()
                val rules = NumberRuleSnapshot(emptyList(), 0)
                RecordingAssembly(context, namespace, { findRecordingCall(core.sessions, it) }, { null }, {},
                    coordinator = coordinator, operationActive = { true }, clientFactory = { client },
                    enrichment = CallerEnrichmentService(core.sessions, coordinator) { true },
                    findCalls = { number, at -> findSystemRecordingCalls(core.sessions, number, at) },
                    numberRules = { rules }, numberMatcher = PrefixLengthNumberMatcher).use { runtime ->
                    runtime.store.persistSettings(RecordingSettings(recordingEnabled = true, automaticAnalysis = false, systemFolder = tree.toString()))
                    val selected = linkedSetOf<Long>()
                    val copied = linkedSetOf<Long>()
                    val scanner = SystemRecordingScanner(context.contentResolver)
                    SystemRecordingDiscovery(runtime.store, runtime.store, runtime.store::settings, { 0L }, scanner,
                        coordinator, { true }, { null }, { number, at -> findSystemRecordingCalls(core.sessions, number, at) },
                        { rules }, PrefixLengthNumberMatcher,
                        outgoingCallCloser = { number,recordedAt,incomingAt,cancelled -> scanner.withQueryCancellation(cancelled) { signal ->
                            AndroidCallLogSource(context.contentResolver).hasCloserOutgoingCall(number,recordedAt,incomingAt,signal)
                        } },
                        importAudio = { call, candidate, validate, copyActive, inspect ->
                            check(selected.add(call.id)) { "qa.duplicate_call_selection" }
                            if (call.id in proofCalls) {
                                runtime.service.importDiscovered(call, candidate, validate, copyActive, inspect)
                                copied += call.id
                            } else {
                                // Inventory proof only for the remaining calls: do not copy the
                                // whole private directory merely to verify metadata association.
                                validate(); check(inspect() == candidate.entry.file)
                            }
                        }).use { discovery ->
                        assertNotNull(discovery.discover { false })
                        assertTrue(selected.isEmpty())
                        SystemClock.sleep(10_100)
                        discovery.discover { false }
                        assertTrue("matched=${selected.size}", selected.size > 100)
                        assertEquals(proofCalls, copied)
                        val excluded = options.optJSONArray("excludedSourceKeys")
                        if (excluded != null) for (i in 0 until excluded.length())
                            assertNull(runtime.store.sourceBinding(excluded.getString(i)))
                        val count = selected.size
                        discovery.discover { false }
                        assertEquals(count, selected.size)
                        for (id in copied) {
                            val archive = requireNotNull(runtime.store.get(id))
                            assertEquals(ArchiveStatus.READY, archive.status)
                            assertTrue(requireNotNull(archive.durationMillis) > 0)
                            assertTrue(File(requireNotNull(archive.audioPath)).isFile)
                        }
                        assertEquals(0, modelCalls)
                        val proof = JSONObject().put("incomingCalls", calls.size).put("historyRows", calls.size)
                            .put("metadataMatches", count).put("realAudioCopiesDecoded", copied.size).put("modelCalls", modelCalls)
                            .put("importDays", 1).put("numberRuleCount", 0).put("productionDataChanged", false)
                            .put("outgoingSourcesRejected", excluded?.length() ?: 0)
                        File(context.getExternalFilesDir(null), "real-recording-proof.json").writeText(proof.toString(2))
                        android.util.Log.i("RealRecordingQa", proof.toString())
                    }
                }
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            if (options.optBoolean("releaseGrant", false) && context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
                context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val audioDirectory = File(context.filesDir, namespace + "_recordings")
            check(audioDirectory.canonicalFile.parentFile == context.filesDir.canonicalFile && audioDirectory.name.startsWith("realrecording_"))
            audioDirectory.listFiles()?.forEach { check(it.isFile); it.delete() }; audioDirectory.delete()
            context.deleteDatabase(namespace); context.deleteDatabase(namespace + "_recording.db")
            for (name in listOf(namespace + "_recording", config.ringPreferencesName, config.widgetPreferencesName, config.appearancePreferencesName)) context.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("callring.recording.$namespace") }
            optionsFile.delete()
        }
    }
}
