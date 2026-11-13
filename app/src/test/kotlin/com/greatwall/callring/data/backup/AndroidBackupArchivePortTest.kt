package com.greatwall.callring.data.backup

import android.app.Notification
import android.app.PendingIntent
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.data.recording.*
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AndroidBackupArchivePortTest {
    @Test fun resetOpaque() = runBlocking {
        Fixture().use { f ->
            val call=f.graph.recordings.registrations.registerIncoming("legacy-private","*",123,null,null)
            val old=f.graph.recordings.store.get(call.id)!!
            f.graph.recordings.store.compareAndSet(call.id,old.generation,old.copy(generation=old.generation+1,status=ArchiveStatus.COMPLETE,transcript="retained private transcript"))
            val reset=AndroidNumberProfileResetPort(f.context,f.graph,f.configuration,f.owner,
                {RestoreJournal(File(f.root,"reset"),f.context.dataDir,syncDirectory={})})
            val p=reset.preview {};reset.confirm(p.token,true){}
            f.newGraph().use { restored ->
                val backup=AndroidBackupArchivePort(f.context,restored,f.configuration,f.owner,f.cipher,
                    {RestoreJournal(File(f.root,"restore-opaque"),f.context.dataDir,syncDirectory={})})
                assertEquals(1L,backup.export(f.document,"password".toCharArray(),false){}.archives)
                val preview=backup.preview(f.document,"password".toCharArray()){}
                assertEquals(1L,preview.summary.archives)
            }
        }
    }
    @Test fun roundTrip() = runBlocking {
        for (includeAudio in listOf(false, true)) Fixture().use { fixture ->
            val graph = fixture.graph
            assertTrue(graph.backend.callers.saveProfile(SaveCallerProfile("31234", "original", "unit", null, false)) is ApiResult.Success)
            val call = graph.recordings.registrations.registerIncoming("session", "31234", 123, null, null)
            val audio = File(graph.recordings.directory, "owned.wav").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val old = graph.recordings.store.get(call.id)!!
            graph.recordings.store.compareAndSet(call.id, old.generation, old.copy(generation = old.generation + 1, status = ArchiveStatus.QUEUED, audioPath = audio.absolutePath, transcript = "retained transcript", manualAnalysis = true))
            fixture.context.getSharedPreferences(fixture.configuration.ringPreferencesName, Context.MODE_PRIVATE).edit().putBoolean("custom", true).putBoolean("enabled", true).commit()
            File(fixture.context.filesDir, "selected_audio").writeBytes(byteArrayOf(9, 8, 7))
            val originalIncarnation = graph.coordinator.capture().incarnation
            val exported = fixture.port.export(fixture.document, "password".toCharArray(), includeAudio) {}
            assertTrue(fixture.archive.length() > 40)
            assertArrayEquals("CRBACKUP".toByteArray(), fixture.archive.inputStream().use { input -> ByteArray(8).also { input.read(it) } })
            assertEquals(1L, exported.callers); assertEquals(1L, exported.archives)
            assertEquals(if (includeAudio) 1L else 0L, exported.audioFiles)
            assertTrue(exported.includesCustomRingtone)
            assertTrue(graph.backend.callers.saveProfile(SaveCallerProfile("39999", "to replace", "", null, false)) is ApiResult.Success)
            val preview = fixture.port.preview(fixture.document, "password".toCharArray()) {}
            assertEquals(exported, preview.summary)
            assertTrue(fixture.port.restore(preview.token) {}.applicationRestartRequested)
            assertTrue(fixture.ownerClosed)
            assertThrows(IllegalStateException::class.java) { graph.recordings.store.get(call.id) }
            val store = AndroidRecordingStore(fixture.context, fixture.configuration.databaseName, fixture.cipher, recordingVersion = { 0 })
            store.use {
                val restored = it.get(call.id)!!
                assertEquals("retained transcript", restored.transcript)
                assertEquals(ArchiveStatus.FAILED, restored.status)
                assertFalse(restored.manualAnalysis)
                assertEquals(Long.MAX_VALUE, restored.nextAttemptAt)
                if (includeAudio) assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(restored.audioPath!!).readBytes()) else { assertNull(restored.audioPath); assertEquals("recording.audio_not_restored", restored.error) }
                val settings = it.settings()
                assertFalse(settings.automaticAnalysis); assertNull(settings.systemFolder)
                assertEquals("", settings.analysis.apiKey); assertEquals("", settings.transcription.apiKey)
                val next = com.greatwall.callring.application.recording.RecordingRegistrationService(it, com.greatwall.callring.application.coordination.BusinessRevisionCoordinator()).registerIncoming("next", "31234", 124, null, null)
                assertEquals(call.id - 1, next.id)
            }
            SQLiteDatabase.openDatabase(fixture.context.getDatabasePath(fixture.configuration.databaseName).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
                it.rawQuery("SELECT number,name FROM callers", null).use { rows -> assertTrue(rows.moveToFirst()); assertEquals("31234", rows.getString(0)); assertEquals("original", rows.getString(1)); assertFalse(rows.moveToNext()) }
            }
            assertArrayEquals(byteArrayOf(9, 8, 7), File(fixture.context.filesDir, "selected_audio").readBytes())
            // A fresh normal owner has a distinct change incarnation; no AI/HTTP factory is touched.
            fixture.newGraph().use { assertNotEquals(originalIncarnation, it.coordinator.capture().incarnation) }
        }
    }

    @Test fun restoreSuggestions() = runBlocking {
        Fixture().use { fixture ->
            val graph = fixture.graph
            val first = graph.recordings.registrations.registerIncoming("confirmed", "31234", 123, null, null)
            val second = graph.recordings.registrations.registerIncoming("pending", "31235", 124, null, null)
            for (call in listOf(first, second)) {
                val before = graph.recordings.store.get(call.id)!!
                val version = before.generation + 1
                assertTrue(graph.recordings.store.compareAndSet(call.id, before.generation, before.copy(
                    generation = version, status = ArchiveStatus.COMPLETE, transcript = "I'm Ada",
                    analysis = CallAnalysis(name = "Ada", fieldEvidence = mapOf("name" to "I'm Ada")),
                    analysisIntent = ArchiveAnalysisIntent.MANUAL_TEXT,
                    manualAnalysisState = ManualAnalysisState.PENDING_CONFIRMATION, manualAnalysisResultVersion = version,
                )))
            }
            val api = graph.backend.recordings
            val saved = checkNotNull((api.getCallerSuggestions(first.id) as ApiResult.Success).value)
            val pending = checkNotNull((api.getCallerSuggestions(second.id) as ApiResult.Success).value)
            val requeued = graph.recordings.store.get(second.id)!!
            assertTrue(graph.recordings.store.compareAndSet(second.id, requeued.generation, requeued.copy(
                generation = requeued.generation + 1, status = ArchiveStatus.QUEUED,
            )))
            val selected = setOf(CallerSuggestionField.NAME)
            val previousReceipt = ConfirmCallerSuggestions(first.id, saved.token, selected)
            assertEquals(CallerSuggestionConfirmation.Applied, (api.confirmCallerSuggestions(previousReceipt) as ApiResult.Success).value)
            // Model the archive state that survives if the process exits just after the core commit.
            val archived = graph.recordings.store.get(first.id)!!
            assertTrue(graph.recordings.store.compareAndSet(first.id, archived.generation, archived.copy(
                generation = archived.generation + 1, manualAnalysisState = ManualAnalysisState.PENDING_CONFIRMATION,
            )))

            fixture.port.export(fixture.document, "password".toCharArray(), false) {}
            val preview = fixture.port.preview(fixture.document, "password".toCharArray()) {}
            fixture.port.restore(preview.token) {}
            fixture.newGraph().use { restored ->
                val recordings = restored.backend.recordings
                assertEquals(CallerSuggestionConfirmation.Conflict(null),
                    (recordings.confirmCallerSuggestions(previousReceipt) as ApiResult.Success).value)
                assertEquals(ManualAnalysisState.CONFIRMED,
                    (recordings.getArchive(first.id) as ApiResult.Success).value?.manualAnalysisState)
                assertEquals(ArchiveStatus.FAILED,
                    (recordings.getArchive(second.id) as ApiResult.Success).value?.status)
                val conflict = (recordings.confirmCallerSuggestions(
                    ConfirmCallerSuggestions(second.id, pending.token, selected)) as ApiResult.Success).value
                    as CallerSuggestionConfirmation.Conflict
                val latest = checkNotNull(conflict.latest)
                assertEquals(pending.resultVersion, latest.resultVersion)
                assertNotEquals(pending.token, latest.token)
                assertEquals(CallerSuggestionConfirmation.Applied,
                    (recordings.confirmCallerSuggestions(ConfirmCallerSuggestions(second.id, latest.token, selected)) as ApiResult.Success).value)
            }
        }
    }

    @Test fun badPreference() = runBlocking {
        Fixture().use { fixture ->
            fixture.graph.backend.callers.saveProfile(SaveCallerProfile("31234", "retained", "", null, false))
            fixture.port.export(fixture.document, "password".toCharArray(), false) {}
            assertTrue(fixture.archive.length() > 40)
            assertArrayEquals("CRBACKUP".toByteArray(), fixture.archive.inputStream().use { input -> ByteArray(8).also { input.read(it) } })
            val verified = fixture.port.preview(fixture.document, "password".toCharArray()) {}
            fixture.port.discard(verified.token)
            val decoded = File(fixture.root, "decoded").apply { mkdir() }
            fixture.archive.inputStream().use { EncryptedBackupFormat.read(it, decoded, "password".toCharArray()) }
            val prefsFile = File(decoded, "preferences.json")
            val preferences = JSONObject(prefsFile.readText())
            preferences.getJSONObject("ringtone").put("enabled", "malicious string")
            prefsFile.writeText(preferences.toString())
            fixture.archive.outputStream().use { EncryptedBackupFormat.write(decoded, it, "password".toCharArray()) }
            val rejected = assertThrows(BackupFormatException::class.java) { fixture.port.preview(fixture.document, "password".toCharArray()) {} }
            assertTrue(rejected.stackTrace.any { it.methodName == "validatePreferences" })
            assertFalse(fixture.ownerClosed)
            assertEquals(1L, SqliteBackupCopy.count(fixture.context.getDatabasePath(fixture.configuration.databaseName), "callers"))
        }
    }

    @Test fun stalePreview() = runBlocking {
        Fixture().use { fixture ->
            fixture.graph.backend.callers.saveProfile(SaveCallerProfile("31234", "retained", "", null, false))
            fixture.port.export(fixture.document, "password".toCharArray(), false) {}
            assertTrue(fixture.archive.length() > 40)
            assertArrayEquals("CRBACKUP".toByteArray(), fixture.archive.inputStream().use { input -> ByteArray(8).also { input.read(it) } })
            val preview = fixture.port.preview(fixture.document, "password".toCharArray()) {}
            fixture.graph.backend.callers.saveProfile(SaveCallerProfile("39999", "changed", "", null, false))
            assertThrows(com.greatwall.callring.application.ports.BackendFailure::class.java) { fixture.port.restore(preview.token) {} }
            assertFalse(fixture.ownerClosed)
            assertEquals(2L, SqliteBackupCopy.count(fixture.context.getDatabasePath(fixture.configuration.databaseName), "callers"))
            val second = fixture.port.preview(fixture.document, "password".toCharArray()) {}
            Shadows.shadowOf(fixture.context.getSystemService(android.telephony.TelephonyManager::class.java)).setCallState(android.telephony.TelephonyManager.CALL_STATE_RINGING)
            assertThrows(com.greatwall.callring.application.ports.BackendFailure::class.java) { fixture.port.restore(second.token) {} }
            assertFalse(fixture.ownerClosed)
        }
    }

    @Test fun badCoreEncoding() = runBlocking {
        for (kind in listOf("work", "rule")) Fixture().use { fixture ->
            fixture.graph.backend.callers.saveProfile(SaveCallerProfile("31234", "retained", "", null, false))
            fixture.port.export(fixture.document, "password".toCharArray(), false) {}
            val valid = fixture.port.preview(fixture.document, "password".toCharArray()) {}
            fixture.port.discard(valid.token)
            val decoded = File(fixture.root, "decoded").apply { mkdir() }
            fixture.archive.inputStream().use { EncryptedBackupFormat.read(it, decoded, "password".toCharArray()) }
            SQLiteDatabase.openDatabase(File(decoded, "core.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                if (kind == "work") db.execSQL("UPDATE config_versions SET snapshot='{}'")
                else db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES('number_rule',?)", arrayOf("{\"version\":2,\"rules\":42,\"revision\":0}"))
            }
            fixture.archive.outputStream().use { EncryptedBackupFormat.write(decoded, it, "password".toCharArray()) }
            val rejected = assertThrows(BackupFormatException::class.java) { fixture.port.preview(fixture.document, "password".toCharArray()) {} }
            assertTrue(rejected.stackTrace.any { it.className.endsWith("CoreBackupValidation") })
            assertFalse(fixture.ownerClosed)
            assertEquals(1L, SqliteBackupCopy.count(fixture.context.getDatabasePath(fixture.configuration.databaseName), "callers"))
        }
    }
    @Test fun sourcePortability() = runBlocking {
        Fixture().use { fixture ->
            val store = fixture.graph.recordings.store
            val parsed = com.greatwall.callring.domain.recording.ParsedSystemRecording("12345", 1000)
            val call = store.registerSystemSource("source", parsed, null) { CallArchive(it.id, it.number, it.startedAt, status = ArchiveStatus.COMPLETE, transcript = "already analyzed") }.call
            store.completeSystemSource("source")
            val deleted = store.registerSystemSource("deleted", parsed.copy(startedAtEpochMillis = 20000), null) { CallArchive(it.id,it.number,it.startedAt) }.call
            store.delete(deleted.id)
            val scan = store.beginSystemScan("settings", 20000, 30, "UTC")
            store.observeSystemFile(scan.id, com.greatwall.callring.application.ports.recording.ScannedSystemRecording("pending", RecordingFile("content://private/name", "private.wav", 1, 128)), parsed, 20000)
            fixture.port.export(fixture.document, "password".toCharArray(), false) {}
            val decoded = File(fixture.root,"decoded").apply { mkdir() }
            fixture.archive.inputStream().use { EncryptedBackupFormat.read(it,decoded,"password".toCharArray()) }
            SQLiteDatabase.openDatabase(File(decoded,"recording.db").path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                for(table in listOf("system_recording_inventory","system_recording_scan")) assertEquals(0L,android.database.DatabaseUtils.longForQuery(db,"SELECT count(*) FROM $table",null))
                assertEquals(2L,android.database.DatabaseUtils.longForQuery(db,"SELECT count(*) FROM system_recording_sources",null))
            }
            assertFalse(File(decoded,"recording.db").readBytes().toString(Charsets.ISO_8859_1).contains("content://private/name"))
            // Live inventory remains intact; only private backup stages are scrubbed.
            SQLiteDatabase.openDatabase(fixture.context.getDatabasePath(fixture.configuration.databaseName+"_recording.db").path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(1L,android.database.DatabaseUtils.longForQuery(db,"SELECT count(*) FROM system_recording_inventory",null))
            }
            val preferences = File(decoded,"preferences.json")
            val portable = JSONObject(preferences.readText()).apply { getJSONObject("recording").put("systemImportDays",30) }
            preferences.writeText(portable.toString())
            fixture.archive.outputStream().use { EncryptedBackupFormat.write(decoded,it,"password".toCharArray()) }
            val preview=fixture.port.preview(fixture.document,"password".toCharArray()) {}
            fixture.port.restore(preview.token) {}
            AndroidRecordingStore(fixture.context,fixture.configuration.databaseName,fixture.cipher,recordingVersion={0}).use {
                assertEquals(30,it.settings().systemImportDays)
                assertTrue(it.sourceCompleted("source")); assertTrue(it.isDeleted(deleted.id))
                val restored=it.get(call.id)!!
                assertEquals(ArchiveStatus.COMPLETE,restored.status); assertNull(restored.audioPath)
                assertEquals(Long.MAX_VALUE,restored.nextAttemptAt)
            }
        }
    }

    @Test fun badSystemSources() = runBlocking {
        for (mutation in listOf("archive_id=999", "source_key='content://private/path'", "number='not-a-number'", "completed=2")) Fixture().use { fixture ->
            fixture.graph.recordings.store.registerSystemSource("source", com.greatwall.callring.domain.recording.ParsedSystemRecording("12345",1000),null) { CallArchive(it.id,it.number,it.startedAt) }
            fixture.port.export(fixture.document,"password".toCharArray(),false) {}
            val decoded=File(fixture.root,"decoded").apply { mkdir() }
            fixture.archive.inputStream().use { EncryptedBackupFormat.read(it,decoded,"password".toCharArray()) }
            SQLiteDatabase.openDatabase(File(decoded,"recording.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("UPDATE system_recording_sources SET $mutation") }
            fixture.archive.outputStream().use { EncryptedBackupFormat.write(decoded,it,"password".toCharArray()) }
            assertThrows(BackupFormatException::class.java) { fixture.port.preview(fixture.document,"password".toCharArray()) {} }
            assertFalse(fixture.ownerClosed)
        }
    }

    @Test fun legacyRecordingPreview() = runBlocking {
        Fixture().use { fixture ->
            val call = fixture.graph.recordings.registrations.registerIncoming("legacy", "12345", 1000, null, null)
            fixture.port.export(fixture.document,"password".toCharArray(),false) {}
            val decoded=File(fixture.root,"legacy").apply { mkdir() }
            fixture.archive.inputStream().use { EncryptedBackupFormat.read(it,decoded,"password".toCharArray()) }
            SQLiteDatabase.openDatabase(File(decoded,"recording.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                listOf("system_recording_sources", "system_recording_inventory", "system_recording_scan").forEach { db.execSQL("DROP TABLE $it") }
                db.execSQL("DROP INDEX recording_events_time")
                com.greatwall.callring.data.stripDirectionForFixture(db)
                db.version = 5
            }
            val prefs=File(decoded,"preferences.json")
            prefs.writeText(JSONObject(prefs.readText()).apply { getJSONObject("recording").remove("systemImportDays") }.toString())
            fixture.archive.outputStream().use { EncryptedBackupFormat.write(decoded,it,"password".toCharArray()) }
            val original = fixture.archive.readBytes()
            val preview=fixture.port.preview(fixture.document,"password".toCharArray()) {}
            assertArrayEquals(original,fixture.archive.readBytes())
            assertEquals(1L,preview.summary.archives)
            fixture.port.restore(preview.token) {}
            AndroidRecordingStore(fixture.context,fixture.configuration.databaseName,fixture.cipher,recordingVersion={0}).use {
                assertEquals(7,it.settings().systemImportDays)
                assertNotNull(it.get(call.id))
                assertNull(it.sourceBinding("missing"))
            }
        }
    }

    private class Fixture : AutoCloseable {
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "b-${UUID.randomUUID().toString().take(6)}").apply { mkdir() }
        val archive = File(root, "backup.crbackup")
        val document = DocumentRef("content://backup-test/file")
        private val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.parseMode(mode))
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }.apply { attachInfo(base, ProviderInfo().apply { authority = "backup-test"; exported = false; applicationInfo = base.applicationInfo }) }
        private val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
        }
        val configuration = BackendConfiguration(AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM),
            databaseName = "b${UUID.randomUUID().toString().take(6)}.db", ringPreferencesName = "r${UUID.randomUUID().toString().take(6)}", appearancePreferencesName = "a${UUID.randomUUID().toString().take(6)}")
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        var ownerClosed = false
        val owner = BackupGraphOwner { old, mutation -> old.close(); ownerClosed = true; mutation(); true }
        val graph = newGraph()
        val port = AndroidBackupArchivePort(context, graph, configuration, owner, cipher,
            journalFactory = { RestoreJournal(File(root, "restore"), context.dataDir, syncDirectory = {}) })
        init {
            Shadows.shadowOf(base).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            Shadows.shadowOf(resolver).registerInputStreamSupplier(Uri.parse(document.uri)) { archive.inputStream() }
            Shadows.shadowOf(resolver).registerOutputStreamSupplier(Uri.parse(document.uri)) { archive.outputStream() }
        }
        fun newGraph() = BackendGraph(owner, context, configuration, object : UiNotificationEntryPoints {
            override fun home(requestCode: Int) = PendingIntent.getActivity(context, requestCode, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
            override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
        })
        override fun close() {
            graph.close(); provider.shutdown(); root.deleteRecursively()
            context.deleteDatabase(configuration.databaseName); context.deleteDatabase(configuration.databaseName + "_recording.db")
            listOf(configuration.ringPreferencesName, configuration.appearancePreferencesName, configuration.databaseName + "_recording").forEach(context::deleteSharedPreferences)
            File(context.filesDir, configuration.databaseName + "_recordings").deleteRecursively()
            File(context.noBackupFilesDir, "backup-${configuration.databaseName}").deleteRecursively()
            File(context.filesDir, "selected_audio").delete()
        }
    }
}
