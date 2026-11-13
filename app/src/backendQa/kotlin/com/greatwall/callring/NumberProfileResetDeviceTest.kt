package com.greatwall.callring

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.test.InstrumentationTestCase
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.contracts.callers.CallHistoryQuery
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.model.CallDirection
import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.domain.model.SystemCallRecord
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import kotlinx.coroutines.runBlocking
import java.io.File

/** Isolated real SQLite/files/owner replacement. Never opens a device source for writing. */
class NumberProfileResetDeviceTest : InstrumentationTestCase() {
    fun testKeepRecordingsAndResynchronize(): Unit = runBlocking { verifyReset(true) }
    fun testDiscardRecordingsAndResynchronize(): Unit = runBlocking { verifyReset(false) }

    private suspend fun verifyReset(keep: Boolean) {
        val context = instrumentation.targetContext
        check(context.packageName == "com.greatwall.callring.backendqa")
        val namespace = "resetqa_${System.nanoTime()}"
        val config = BackendConfiguration(
            AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM),
            databaseName = namespace, ringPreferencesName = namespace + "_ring",
            widgetPreferencesName = namespace + "_widgets", appearancePreferencesName = namespace + "_appearance")
        val notifications = object : UiNotificationEntryPoints {
            override fun home(requestCode: Int) = PendingIntent.getActivity(context, requestCode,
                Intent("qa.number.reset"), PendingIntent.FLAG_IMMUTABLE)
            override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
        }
        lateinit var graph: BackendGraph
        lateinit var owner: BackupGraphOwner
        fun open() = BackendGraph(owner, context, config, notifications)
        owner = BackupGraphOwner { expected, mutation ->
            check(graph === expected)
            expected.close()
            try { mutation() }
            catch (failure: Exception) {
                RestoreCoordinator.recover(context, namespace)
                graph = open()
                throw failure
            }
            graph = open()
            true
        }
        graph = open()
        val originalBytes = byteArrayOf(3, 1, 4, 1, 5, 9)
        val original = File(context.noBackupFilesDir, namespace + "_source").apply { writeBytes(originalBytes) }
        val directory = File(context.filesDir, namespace + "_recordings")
        val audio = File(directory, "retained.wav")
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE)
        try {
            graph.backend.prepareNumberRule().requiredValue()
            graph.api.settings.setStatisticsEnabled(true).requiredValue()
            val at = System.currentTimeMillis()
            val records = listOf(
                SystemCallRecord("qa-in", "31234", at, 10, CallResult.ANSWERED),
                SystemCallRecord("qa-out", "31234", at + 10000, 20, CallResult.ANSWERED,
                    direction = CallDirection.OUTGOING))
            graph.backend.importSystemCalls(records)
            val incoming = graph.api.callers.queryCallHistory().requiredValue().entries.single {
                it.call.direction == com.greatwall.callring.contracts.callers.CallDirection.INCOMING
            }.call
            directory.mkdirs(); original.copyTo(audio)
            assertTrue(graph.recordings.store.compareAndSet(incoming.id, null,
                CallArchive(incoming.id, "31234", at, generation = 2, status = ArchiveStatus.COMPLETE,
                    source = RecordingSource.SYSTEM, audioPath = audio.absolutePath,
                    transcript = "preserved transcript", analysis = CallAnalysis(summary = "preserved analysis"))))
            context.getSharedPreferences(config.widgetPreferencesName, Context.MODE_PRIVATE)
                .edit().putString("number_7", "31234").putBoolean("unrelated", true).commit()
            val previousOwner = graph
            val preview = graph.api.storage.previewNumberProfiles().requiredValue()
            assertEquals(1L, preview.profileCount)
            assertEquals(2L, preview.callCount)
            assertEquals(1L, preview.widgetBindingCount)
            assertEquals(1L, preview.archiveCount)
            val receipt = graph.api.storage.confirmNumberProfiles(preview.token, keep).requiredValue()
            assertEquals(keep, receipt.keepRecordings)
            assertTrue(receipt.applicationRestartRequested)
            assertFalse(previousOwner.acceptsOperations())
            graph.backend.prepareNumberRule().requiredValue()
            assertTrue(graph.api.callers.queryCallHistory().requiredValue().entries.isEmpty())
            assertFalse(graph.api.callers.numberDataImpact("31234").requiredValue().profileExists)
            val widgetPrefs = context.getSharedPreferences(config.widgetPreferencesName, Context.MODE_PRIVATE)
            assertFalse(widgetPrefs.contains("number_7"))
            assertTrue(widgetPrefs.getBoolean("unrelated", false))
            graph.backend.importSystemCalls(records)
            assertTrue(graph.backend.importSystemCalls(records).isEmpty())
            val history = graph.api.callers.queryCallHistory(CallHistoryQuery(number = "31234")).requiredValue()
            assertEquals(2, history.entries.size)
            assertFalse(history.entries.single {
                it.call.direction == com.greatwall.callring.contracts.callers.CallDirection.OUTGOING
            }.call.counted)
            var page = graph.api.recordings.queryArchives().requiredValue()
            repeat(100) { if (page.preparing) page = graph.api.recordings.queryArchives().requiredValue() }
            assertFalse(page.preparing)
            if (keep) {
                val kept = graph.api.recordings.getArchive(page.items.single().callId).requiredValue()!!
                assertEquals("preserved transcript", kept.transcript)
                assertEquals("preserved analysis", kept.analysis!!.summary)
                assertTrue(originalBytes.contentEquals(File(kept.audioPath!!).readBytes()))
                val linked = graph.api.callers.queryCallHistory(CallHistoryQuery(number = "31234")).requiredValue().entries.single {
                    it.call.direction == com.greatwall.callring.contracts.callers.CallDirection.INCOMING
                }.recording
                assertEquals(kept.callId, linked.archiveId)
                assertTrue(linked.hasAudio)
            } else {
                assertTrue(page.items.isEmpty())
                assertFalse(audio.exists())
            }
            assertTrue("Source file must remain byte-identical", originalBytes.contentEquals(original.readBytes()))
        } finally {
            graph.close()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            RestoreCoordinator.recover(context, namespace)
            context.deleteDatabase(namespace)
            context.deleteDatabase(namespace + "_recording.db")
            check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
            directory.deleteRecursively()
            original.delete()
        }
    }
}
