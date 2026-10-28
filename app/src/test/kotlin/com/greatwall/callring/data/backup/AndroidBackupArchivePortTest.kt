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
        }, object : IncomingCallUiSink { override fun show(request: IncomingCallUiRequest) {} })
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
