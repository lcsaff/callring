package com.greatwall.callring.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.media.AudioManager
import android.telephony.TelephonyManager
import android.util.Xml
import com.greatwall.callring.CallCaptureService
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.backup.BackupArchivePort
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.contracts.backup.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.ArchiveStatus
import com.greatwall.callring.data.recording.KeystoreRecordingCipher
import com.greatwall.callring.data.recording.RecordingJson
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.security.DigestInputStream
import java.util.UUID

/** Private portable staging and Android resource installation. External document IO never holds a
 * graph, coordinator or SQLite transaction lock. Only a verified staged package may reach install. */
internal class AndroidBackupArchivePort(
    private val context: Context,
    private val graph: BackendGraph,
    private val configuration: BackendConfiguration,
    private val owner: BackupGraphOwner,
    private val recordingCipher: com.greatwall.callring.data.recording.RecordingCipher = KeystoreRecordingCipher("callring.recording.${configuration.databaseName}"),
    private val journalFactory: () -> RestoreJournal = { RestoreCoordinator.journal(context, configuration.databaseName) },
) : BackupArchivePort {
    private data class Preview(val stage: File, val revision: Long, val sourceDigest: ByteArray, val fingerprint: ByteArray, val summary: BackupSummary, val expiresAt: Long)
    private val previews = mutableMapOf<String, Preview>()
    private val serial = Any()
    private val documents = com.greatwall.callring.platform.document.AndroidDocumentStore(context.contentResolver)
    private val root get() = File(context.noBackupFilesDir, "backup-${configuration.databaseName}")
    private val sourceCore get() = context.getDatabasePath(configuration.databaseName)
    private val sourceRecording get() = context.getDatabasePath(configuration.databaseName + "_recording.db")
    private val audioDirectory get() = File(context.filesDir, configuration.databaseName + "_recordings")
    private val appearance get() = context.getSharedPreferences(configuration.appearancePreferencesName, Context.MODE_PRIVATE)
    private val ring get() = context.getSharedPreferences(configuration.ringPreferencesName, Context.MODE_PRIVATE)
    private val recordingPreferences get() = context.getSharedPreferences(configuration.databaseName + "_recording", Context.MODE_PRIVATE)

    override fun export(destination: DocumentRef, password: CharArray, includeAudio: Boolean, checkCancellation: () -> Unit): BackupSummary = synchronized(serial) {
        checkCancellation()
        requirePassword(password)
        checkIdle()
        // Materialize shipped schemas through their real owners before taking the version sample.

        val stage = newStage()
        var installedLease: java.io.Closeable? = null
        try {
            installedLease = graph.acquireBackupReadLease()
            graph.prepareBackupStorage()
            val revision = graph.coordinator.guard { graph.coordinator.marker() }
            val check = { checkCancellation(); checkIdle(); requireSpace(stage, 16L * 1024 * 1024) }
            check()
            SqliteBackupCopy.copy(sourceCore, File(stage, "core.db"), check = check)
            SqliteBackupCopy.copy(sourceRecording, File(stage, "recording.db"), check = check)
            val settings = graph.recordings.store.settings().let { it.copy(
                automaticAnalysis = false, systemFolder = null,
                analysis = it.analysis.copy(apiKey = ""), transcription = it.transcription.copy(apiKey = ""),
            ) }
            val preferences = JSONObject().put("appearance", preferenceJson(appearance, appearanceKeys))
                .put("ringtone", preferenceJson(ring, ringtoneKeys)).put("recording", RecordingJson.settings(settings))
            File(stage, "preferences.json").writeText(preferences.toString())
            val audio = transformArchives(File(stage, "recording.db"), stage, includeAudio, exporting = true, check = check)
            val custom = ring.getBoolean("custom", false)
            if (custom) {
                val selected = File(configuration.audioDirectory ?: context.filesDir, "selected_audio")
                ensure(selected.isFile)
                copyFile(selected, File(stage, "ringtone"), check)
            }
            val created = configuration.clock.millis()
            File(stage, "manifest.json").writeText(JSONObject().put("version", 1).put("created", created).put("audio", includeAudio).toString())
            graph.coordinator.guard { check(); if (graph.coordinator.marker() != revision) conflict() }
            val summary = summary(stage, created, includeAudio, custom, audio)
            installedLease.close(); installedLease = null
            // Changes/calls after preparation do not invalidate this immutable captured generation.
            documents.write(destination) { output ->
                EncryptedBackupFormat.write(stage, output, password, checkCancellation = checkCancellation)
            }
            summary
        } finally { installedLease?.close(); stage.deleteRecursively() }
    }

    override fun preview(source: DocumentRef, password: CharArray, checkCancellation: () -> Unit): BackupPreview = synchronized(serial) {
        checkCancellation()
        requirePassword(password)

        // At most one plaintext preview is retained. Tokens expire and are never persisted.
        previews.values.forEach { it.stage.deleteRecursively() }; previews.clear()
        val stage = newStage()
        var installedLease: java.io.Closeable? = null
        try {
            installedLease = graph.acquireBackupReadLease()
            graph.prepareBackupStorage()
            val digest = MessageDigest.getInstance("SHA-256")
            documents.read(source) { stream ->
                EncryptedBackupFormat.read(DigestInputStream(stream, digest), stage, password, checkCancellation = { checkCancellation(); requireSpace(stage, 16L * 1024 * 1024) })
            }
            checkCancellation()
            val manifest = readSmallJson(File(stage, "manifest.json"))
            ensure(manifest.getInt("version") == 1)
            val created = manifest.getLong("created")
            val includeAudio = manifest.getBoolean("audio")
            SqliteBackupCopy.validate(File(stage, "core.db"), sourceCore)
            SqliteBackupCopy.validate(File(stage, "recording.db"), sourceRecording)
            CoreBackupValidation.validate(File(stage, "core.db"), checkCancellation)
            validateReferences(stage)
            val prefs = readSmallJson(File(stage, "preferences.json"))
            validatePreferences(prefs)
            val custom = prefs.getJSONObject("ringtone").optBoolean("custom", false)
            ensure(custom == File(stage, "ringtone").isFile)
            val audio = transformArchives(File(stage, "recording.db"), stage, includeAudio, exporting = false, check = checkCancellation)
            validateEntries(stage, includeAudio)
            val result = summary(stage, created, includeAudio, custom, audio)
            val token = UUID.randomUUID().toString()
            val revision = graph.coordinator.guard { checkCancellation(); graph.coordinator.marker() }
            // Source digest is authenticated by the archive; fingerprint also binds the transformed
            // private staging generation that will actually be installed.
            val sourceDigest = digest.digest()
            previews[token] = Preview(stage, revision, sourceDigest, fingerprint(stage, checkCancellation), result, configuration.clock.millis() + 15 * 60_000)
            BackupPreview(token, result)
        } catch (failure: Throwable) { stage.deleteRecursively(); throw failure }
        finally { installedLease?.close() }
    }

    override fun restore(previewToken: String, checkCancellation: () -> Unit): RestoreReceipt = synchronized(serial) {
        val preview = previews.remove(previewToken) ?: conflict()
        try {
            checkCancellation(); checkIdle()
            if (configuration.clock.millis() > preview.expiresAt) conflict()
            ensure(MessageDigest.isEqual(preview.fingerprint, fingerprint(preview.stage, checkCancellation)))
            graph.coordinator.guard { if (graph.coordinator.marker() != preview.revision) conflict() }
            val install = File(root, "install-${UUID.randomUUID()}").apply { ensure(mkdirs()) }
            try {
                val names = prepareInstallation(preview.stage, install, checkCancellation)
                val needed = install.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                requireSpace(install, Math.addExact(needed, 16L * 1024 * 1024))
                checkCancellation(); checkIdle()
                // After this guarded admission, owner.close seals/cancels/drains all old resources.
                // Cancellation is no longer consulted inside the journal switch: failure is rolled
                // back, and process death is recovered before any new owner opens storage.
                val restarted = owner.replace(graph) {
                    checkIdle()
                    graph.coordinator.guard { if (graph.coordinator.marker() != preview.revision) conflict() }
                    val journal = journalFactory()
                    try {
                        journal.replace(install, names) { refreshPreferenceCaches(install) }
                    } catch (failure: Exception) {
                        journal.recover()
                        refreshPreferenceCaches(context.dataDir)
                        throw failure
                    }
                }
                RestoreReceipt(restarted)
            } finally { install.deleteRecursively() }
        } finally { preview.stage.deleteRecursively() }
    }

    override fun discard(previewToken: String) { synchronized(serial) { previews.remove(previewToken)?.stage?.deleteRecursively() } }

    private fun prepareInstallation(stage: File, install: File, check: () -> Unit): List<String> {
        val core = "databases/${configuration.databaseName}"
        val recording = "databases/${configuration.databaseName}_recording.db"
        copyFile(File(stage, "core.db"), File(install, core), check)
        copyFile(File(stage, "recording.db"), File(install, recording), check)
        val audioName = "files/${configuration.databaseName}_recordings"
        val sourceAudio = File(stage, "recordings")
        if (sourceAudio.exists()) sourceAudio.walkTopDown().filter { it.isFile }.forEach { copyFile(it, File(install, "$audioName/${it.name}"), check) }
        transformInstalledAudio(File(install, recording), check)
        val ringtone = "files/selected_audio"
        if (File(stage, "ringtone").exists()) copyFile(File(stage, "ringtone"), File(install, ringtone), check)
        val prefs = readSmallJson(File(stage, "preferences.json"))
        val appearanceName = "shared_prefs/${configuration.appearancePreferencesName}.xml"
        val ringName = "shared_prefs/${configuration.ringPreferencesName}.xml"
        val recordingName = "shared_prefs/${configuration.databaseName}_recording.xml"
        val widgetName = "shared_prefs/${configuration.widgetPreferencesName}.xml"
        writePreferencesXml(File(install, widgetName), emptyMap<String, Any>())
        writePreferencesXml(File(install, appearanceName), jsonPreferences(prefs.getJSONObject("appearance")))
        val ringValues = ring.all.filterKeys { it !in ringtoneKeys }.toMutableMap().apply { putAll(jsonPreferences(prefs.getJSONObject("ringtone"))) }
        writePreferencesXml(File(install, ringName), ringValues)
        val portableSettings = RecordingJson.settings(prefs.getJSONObject("recording"))
        val encrypted = recordingCipher.encrypt(RecordingJson.settings(portableSettings).toString())
        writePreferencesXml(File(install, recordingName), mapOf("config" to encrypted))
        return listOf(core, recording, audioName, ringtone, appearanceName, ringName, recordingName, widgetName, widgetName + ".bak",
            core + "-wal", core + "-shm", core + "-journal", recording + "-wal", recording + "-shm", recording + "-journal",
            appearanceName + ".bak", ringName + ".bak", recordingName + ".bak")
    }

    private fun transformInstalledAudio(file: File, check: () -> Unit) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            eachArchive(db, check) { id, fields ->
                val path = ArchiveBackupFields.string(fields["audio"])
                if (path != null) {
                    ensure(path.startsWith("recordings/") && path.count { it == '/' } == 1)
                    val installed = File(audioDirectory, path.substringAfter('/')).absolutePath
                    ArchiveBackupFields.replace(db, id, fields, mapOf("audio" to JSONObject.quote(installed)))
                }
            }
            // Disposable indexes are rebuilt from authoritative payloads by their real consumers.
            db.execSQL("UPDATE archive_list_state SET ready=0")
        }
    }

    private fun transformArchives(file: File, stage: File, includeAudio: Boolean, exporting: Boolean, check: () -> Unit): Pair<Long, Long> {
        var files = 0L; var bytes = 0L
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TEMP TABLE backup_audio_references(path TEXT PRIMARY KEY)")
            eachArchive(db, check) { id, fields ->
                ensure(fields.getValue("id").scalar?.toLongOrNull() == id)
                ensure(fields.getValue("generation").scalar?.toLongOrNull() != null)
                ensure(ArchiveBackupFields.string(fields["number"])?.isNotBlank() == true)
                ensure(fields.getValue("started").scalar?.toLongOrNull() != null)
                com.greatwall.callring.contracts.recording.RecordingSource.valueOf(ArchiveBackupFields.string(fields["source"]) ?: throw BackupFormatException())
                ArchiveBackupFields.string(fields["protocol"])?.let { com.greatwall.callring.contracts.ai.AiProtocol.valueOf(it) }
                ensure(fields.getValue("attempts").scalar?.toIntOrNull() != null)
                ensure(fields.getValue("next").scalar?.toLongOrNull() != null)
                listOf("duration", "analyzed").forEach { key -> val raw = fields.getValue(key).scalar; ensure(raw == "null" || raw?.toLongOrNull() != null) }
                val status = ArchiveStatus.valueOf(ArchiveBackupFields.string(fields["status"]) ?: throw BackupFormatException())
                val path = ArchiveBackupFields.string(fields["audio"])
                var portable: String? = null
                if (path != null && includeAudio) {
                    if (exporting) {
                        val source = File(path)
                        ensure(source.canonicalFile.parentFile == audioDirectory.canonicalFile && source.isFile)
                        portable = "recordings/${source.name}"
                        EncryptedBackupFormat.validateName(portable)
                        val target = File(stage, portable)
                        if (!target.exists()) { copyFile(source, target, check); files++; bytes = Math.addExact(bytes, source.length()) }
                    } else {
                        EncryptedBackupFormat.validateName(path)
                        ensure(path.startsWith("recordings/") && path.count { it == '/' } == 1)
                        val target = File(stage, path)
                        ensure(target.isFile)
                        portable = path; files++; bytes = Math.addExact(bytes, target.length())
                    }
                } else if (!exporting) ensure(path == null)
                if (portable != null) db.execSQL("INSERT OR IGNORE INTO backup_audio_references VALUES(?)", arrayOf(portable))
                val pending = status in setOf(ArchiveStatus.QUEUED, ArchiveStatus.TRANSCRIBING, ArchiveStatus.ANALYZING, ArchiveStatus.WAITING_RECORDING)
                val updates = linkedMapOf("audio" to (portable?.let(JSONObject::quote) ?: "null"), "next" to Long.MAX_VALUE.toString())
                if ("manual" in fields) updates["manual"] = "false"
                if (pending) { updates["status"] = JSONObject.quote(ArchiveStatus.FAILED.name); updates["error"] = JSONObject.quote("recording.restore_retry_required") }
                if (exporting && path != null && !includeAudio) updates["error"] = JSONObject.quote("recording.audio_not_restored")
                ArchiveBackupFields.replace(db, id, fields, updates)
            }
            files = 0; bytes = 0
            val audioRoot = File(stage, "recordings")
            if (audioRoot.exists()) audioRoot.walkTopDown().filter { it.isFile }.forEach { audio ->
                check(); val relative = audio.relativeTo(stage).invariantSeparatorsPath
                db.rawQuery("SELECT 1 FROM backup_audio_references WHERE path=?", arrayOf(relative)).use { ensure(it.moveToFirst()) }
                files++; bytes = Math.addExact(bytes, audio.length())
            }
            listOf("archive_projection", "archive_text_fields", "archive_text_chunks", "archive_index_progress", "archive_raw_chunks", "archive_parse_progress").forEach { db.execSQL("DELETE FROM $it") }
            db.execSQL("UPDATE archive_list_state SET ready=0")
            db.rawQuery("SELECT 1 FROM archives a JOIN deleted_archives d ON d.call_id=a.call_id LIMIT 1", null).use { ensure(!it.moveToFirst()) }
        }
        return files to bytes
    }

    private fun eachArchive(db: SQLiteDatabase, check: () -> Unit, consume: (Long, Map<String, ArchiveBackupFields.Field>) -> Unit) {
        var last = Long.MIN_VALUE
        while (true) {
            check()
            val id = db.rawQuery("SELECT call_id FROM archives WHERE call_id>? ORDER BY call_id LIMIT 1", arrayOf(last.toString())).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return
            consume(id, ArchiveBackupFields.fields(db, id, check)); last = id
        }
    }

    private fun validateReferences(stage: File) {
        SQLiteDatabase.openDatabase(File(stage, "recording.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ATTACH DATABASE ? AS backup_core", arrayOf(File(stage, "core.db").absolutePath))
            try {
                val checks = listOf(
                    "SELECT 1 FROM archives WHERE call_id=0 OR call_id=-9223372036854775808 LIMIT 1",
                    "SELECT 1 FROM archives a WHERE a.call_id<0 AND NOT EXISTS(SELECT 1 FROM recording_events e WHERE e.id=-a.call_id) LIMIT 1",
                    "SELECT 1 FROM archives a WHERE a.call_id>0 AND NOT EXISTS(SELECT 1 FROM backup_core.calls c WHERE c.id=a.call_id) LIMIT 1",
                    "SELECT 1 FROM recording_statistics_links WHERE statistics_id<=0 OR length(session_id)=0 LIMIT 1",
                    "SELECT 1 FROM recording_events WHERE id<=0 LIMIT 1",
                )
                checks.forEach { sql -> db.rawQuery(sql, null).use { ensure(!it.moveToFirst()) } }
            } finally { db.execSQL("DETACH DATABASE backup_core") }
        }
    }
    private fun validateEntries(stage: File, audio: Boolean) {
        stage.walkTopDown().filter { it.isFile }.forEach {
            val name = it.relativeTo(stage).invariantSeparatorsPath
            ensure(name in setOf("core.db", "recording.db", "preferences.json", "manifest.json", "ringtone") || (audio && name.startsWith("recordings/") && name.count { c -> c == '/' } == 1))
        }
    }
    private fun validatePreferences(prefs: JSONObject) {
        ensure(prefs.keys().asSequence().toSet() == setOf("appearance", "ringtone", "recording"))
        ensure(prefs.getJSONObject("appearance").keys().asSequence().toSet().all { it in appearanceKeys })
        ensure(prefs.getJSONObject("ringtone").keys().asSequence().toSet().all { it in ringtoneKeys })
        val appearance = prefs.getJSONObject("appearance")
        appearance.keys().asSequence().forEach { key ->
            val value = appearance.get(key)
            if (key == "custom_seed") ensure(value is Int && value in 0..0xffffff)
            else ensure(value is String && (key != "preset_id" || value.isNotBlank()))
        }
        val ringtone = prefs.getJSONObject("ringtone")
        ringtone.keys().asSequence().forEach { key ->
            val value = ringtone.get(key)
            ensure(if (key in setOf("enabled", "custom")) value is Boolean else value is String)
        }
        val settings = RecordingJson.settings(prefs.getJSONObject("recording"))
        ensure(!settings.automaticAnalysis && settings.systemFolder == null && settings.analysis.apiKey.isEmpty() && settings.transcription.apiKey.isEmpty())
        settings.copy(analysis = settings.analysis.copy(url = "", model = ""), transcription = settings.transcription.copy(url = "", model = "")).validate()
        jsonPreferences(prefs.getJSONObject("appearance")); jsonPreferences(prefs.getJSONObject("ringtone"))
    }
    private fun summary(stage: File, created: Long, audio: Boolean, custom: Boolean, totals: Pair<Long, Long>) = BackupSummary(created,
        SqliteBackupCopy.count(File(stage, "core.db"), "callers"), SqliteBackupCopy.count(File(stage, "core.db"), "calls"),
        SqliteBackupCopy.count(File(stage, "recording.db"), "archives"), totals.first, totals.second, audio, custom)

    private fun newStage(): File {
        ensure(root.isDirectory || root.mkdirs())
        requireSpace(root, 16L * 1024 * 1024)
        return File(root, UUID.randomUUID().toString()).apply { ensure(mkdir()) }
    }
    private fun requirePassword(password: CharArray) { if (password.size < 8) throw BackendFailure(ApiError(ErrorCode.VALIDATION, "backup.password_too_short")) }
    private fun checkIdle() {
        val granted = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED, "backup.phone_state_required"))
        @Suppress("DEPRECATION") val state = context.getSystemService(TelephonyManager::class.java).callState
        val mode = context.getSystemService(AudioManager::class.java).mode
        if (state != TelephonyManager.CALL_STATE_IDLE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || com.greatwall.callring.entrypoint.recording.CaptureServiceActivity.busy())
            throw BackendFailure(ApiError(ErrorCode.CONFLICT, "backup.call_active", retryable = true))
    }
    private fun requireSpace(directory: File, needed: Long) { if (directory.usableSpace < needed) throw BackendFailure(ApiError(ErrorCode.STORAGE_FAILURE, "backup.insufficient_space")) }
    private fun copyFile(source: File, target: File, check: () -> Unit) {
        ensure(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        requireSpace(target.parentFile!!, Math.addExact(source.length(), 16L * 1024 * 1024))
        FileOutputStream(target).use { output -> source.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { check(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) } }; output.fd.sync() }
    }
    private fun fingerprint(stage: File, check: () -> Unit): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        stage.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(stage).invariantSeparatorsPath }.forEach { file ->
            check(); digest.update(file.relativeTo(stage).invariantSeparatorsPath.toByteArray())
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { check(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        }
        return digest.digest()
    }
    private fun readSmallJson(file: File): JSONObject { ensure(file.isFile && file.length() <= 1024 * 1024); return JSONObject(file.readText()) }
    private fun preferenceJson(preferences: SharedPreferences, keys: Set<String>) = JSONObject().apply { preferences.all.filterKeys { it in keys }.forEach { (key, value) -> put(key, value) } }
    private fun jsonPreferences(json: JSONObject): Map<String, Any> = json.keys().asSequence().toSet().associateWith { key -> json.get(key).also { ensure(it is String || it is Int || it is Long || it is Boolean) } }
    private fun writePreferencesXml(file: File, values: Map<String, *>) {
        ensure(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        FileOutputStream(file).use { output ->
            val xml = Xml.newSerializer(); xml.setOutput(output, "UTF-8"); xml.startDocument("UTF-8", true); xml.startTag(null, "map")
            values.forEach { (key, value) -> when (value) {
                is String -> { xml.startTag(null, "string"); xml.attribute(null, "name", key); xml.text(value); xml.endTag(null, "string") }
                is Boolean, is Int, is Long -> { val tag = when (value) { is Boolean -> "boolean"; is Int -> "int"; else -> "long" }; xml.startTag(null, tag); xml.attribute(null, "name", key); xml.attribute(null, "value", value.toString()); xml.endTag(null, tag) }
                else -> throw BackupFormatException()
            } }
            xml.endTag(null, "map"); xml.endDocument(); xml.flush(); output.fd.sync()
        }
    }
    private fun refreshPreferenceCaches(base: File) {
        listOf(configuration.appearancePreferencesName, configuration.ringPreferencesName, configuration.widgetPreferencesName, configuration.databaseName + "_recording").forEach { name ->
            val file = File(base, "shared_prefs/$name.xml")
            val values = mutableMapOf<String, Any>()
            if (file.isFile) file.inputStream().use { input ->
                val xml = Xml.newPullParser(); xml.setInput(input, "UTF-8")
                while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name != "map") {
                        val key = xml.getAttributeValue(null, "name")
                        val value = xml.getAttributeValue(null, "value")
                        values[key] = when (xml.name) { "string" -> xml.nextText(); "boolean" -> value.toBooleanStrict(); "int" -> value.toInt(); "long" -> value.toLong(); else -> throw BackupFormatException() }
                    }
                }
            }
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.forEach { (key, value) -> when (value) { is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value); is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value) } }
            ensure(editor.commit())
        }
    }
    private fun conflict(): Nothing = throw BackendFailure(ApiError(ErrorCode.CONFLICT, "backup.preview_changed", retryable = true))
    private fun ensure(value: Boolean) { if (!value) throw BackupFormatException() }
    companion object {
        private val appearanceKeys = setOf("selection", "preset_id", "custom_seed", "color_mode")
        private val ringtoneKeys = setOf("enabled", "custom", "audio_display_name", "built_in_ringtone")
    }
}
