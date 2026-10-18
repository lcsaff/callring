package com.greatwall.callring.platform.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.DatabaseUtils
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.greatwall.callring.application.RecordingStore
import com.greatwall.callring.application.RecordingCall
import com.greatwall.callring.application.api.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface RecordingCipher {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}

class KeystoreRecordingCipher(private val alias: String) : RecordingCipher {
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    override fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}

/** Separate database keeps large transcripts away from the Telecom snapshot transactions. */
class AndroidRecordingStore(context: Context, namespace: String,
    private val cipher: RecordingCipher = KeystoreRecordingCipher("callring.recording.$namespace"),
) : RecordingStore, AutoCloseable {
    private val preferences = context.getSharedPreferences(namespace + "_recording", Context.MODE_PRIVATE)
    private val helper = object : SQLiteOpenHelper(context, namespace + "_recording.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE archives (call_id INTEGER PRIMARY KEY, generation INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE deleted_archives (call_id INTEGER PRIMARY KEY)")
            createEvents(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) createEvents(db) }
        private fun createEvents(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE recording_events (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL UNIQUE, number TEXT NOT NULL, started INTEGER NOT NULL, account TEXT)")
            db.execSQL("CREATE TABLE recording_statistics_links (statistics_id INTEGER PRIMARY KEY, session_id TEXT NOT NULL)")
        }
    }
    /** Event identities are owned here, including when the statistics database contains no call. */
    @Synchronized fun registerIncoming(sessionId: String, number: String, at: Long, account: String?): RecordingCall {
        require(sessionId.isNotBlank() && number.isNotBlank())
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val row = ContentValues().apply { put("session_id", sessionId); put("number", number); put("started", at); put("account", account) }
            db.insertWithOnConflict("recording_events", null, row, SQLiteDatabase.CONFLICT_IGNORE)
            val call = db.rawQuery("SELECT id,number,started FROM recording_events WHERE session_id=?", arrayOf(sessionId)).use {
                check(it.moveToFirst()); RecordingCall(-it.getLong(0), it.getString(1), it.getLong(2), null)
            }
            require(call.number == number && call.startedAt == at) { "recording.session_conflict" }
            if (!isDeleted(call.id)) {
                val archive = CallArchive(call.id, number, at, generation = System.currentTimeMillis() * 1000)
                val initial = ContentValues().apply { put("call_id", call.id); put("generation", archive.generation); put("payload", RecordingJson.archive(archive).toString()) }
                db.insertWithOnConflict("archives", null, initial, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
            return call
        } finally { db.endTransaction() }
    }
    @Synchronized fun findRecording(id: Long): RecordingCall? {
        if (id >= 0 || id == Long.MIN_VALUE) return null
        return helper.readableDatabase.rawQuery("SELECT id,number,started FROM recording_events WHERE id=?", arrayOf((-id).toString())).use {
            if (it.moveToFirst()) RecordingCall(-it.getLong(0), it.getString(1), it.getLong(2), null) else null
        }
    }
    @Synchronized fun linkStatistics(statisticsId: Long, sessionId: String) {
        require(statisticsId > 0 && sessionId.isNotBlank())
        val row = ContentValues().apply { put("statistics_id", statisticsId); put("session_id", sessionId) }
        helper.writableDatabase.insertWithOnConflict("recording_statistics_links", null, row, SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun hasSession(sessionId: String): Boolean = helper.readableDatabase.rawQuery(
        "SELECT 1 FROM recording_events WHERE session_id=?", arrayOf(sessionId)).use { it.moveToFirst() }
    @Synchronized fun linkedRecording(statisticsId: Long): RecordingCall? = helper.readableDatabase.rawQuery(
        "SELECT e.id,e.number,e.started FROM recording_events e JOIN recording_statistics_links l ON e.session_id=l.session_id WHERE l.statistics_id=?",
        arrayOf(statisticsId.toString())).use { if (it.moveToFirst()) RecordingCall(-it.getLong(0), it.getString(1), it.getLong(2), null) else null }
    @Synchronized override fun isDeleted(callId: Long): Boolean = helper.readableDatabase.rawQuery(
        "SELECT 1 FROM deleted_archives WHERE call_id=?", arrayOf(callId.toString())).use { it.moveToFirst() }
    @Synchronized override fun settings(): RecordingSettings = preferences.getString("config", null)?.let {
        RecordingJson.settings(JSONObject(cipher.decrypt(it)))
    } ?: RecordingSettings()
    @Synchronized override fun saveSettings(value: RecordingSettings) {
        value.validate()
        check(preferences.edit().putString("config", cipher.encrypt(RecordingJson.settings(value).toString())).commit())
    }
    @Synchronized override fun get(callId: Long): CallArchive? = helper.readableDatabase.query("archives", arrayOf("payload"),
        "call_id=?", arrayOf(callId.toString()), null, null, null).use { if (it.moveToFirst()) RecordingJson.archive(JSONObject(it.getString(0))) else null }
    @Synchronized override fun all(): List<CallArchive> = helper.readableDatabase.query("archives", arrayOf("payload"),
        null, null, null, null, "call_id").use { cursor -> buildList { while (cursor.moveToNext()) add(RecordingJson.archive(JSONObject(cursor.getString(0)))) } }
    @Synchronized override fun compareAndSet(callId: Long, expectedGeneration: Long?, value: CallArchive): Boolean {
        require(callId == value.callId)
        val row = ContentValues().apply { put("call_id", callId); put("generation", value.generation); put("payload", RecordingJson.archive(value).toString()) }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val success = if (expectedGeneration == null) {
                db.execSQL("INSERT OR IGNORE INTO archives(call_id,generation,payload) VALUES (?,?,?)",
                    arrayOf<Any>(callId, value.generation, RecordingJson.archive(value).toString()))
                DatabaseUtils.longForQuery(db, "SELECT changes()", null) == 1L
            }
            else db.update("archives", row, "call_id=? AND generation=?", arrayOf(callId.toString(), expectedGeneration.toString())) == 1
            if (success) db.delete("deleted_archives", "call_id=?", arrayOf(callId.toString()))
            db.setTransactionSuccessful()
            return success
        } finally { db.endTransaction() }
    }
    @Synchronized override fun delete(callId: Long): CallArchive? {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val value = get(callId)
            db.delete("archives", "call_id=?", arrayOf(callId.toString()))
            db.execSQL("INSERT OR IGNORE INTO deleted_archives(call_id) VALUES (?)", arrayOf(callId))
            db.setTransactionSuccessful()
            return value
        } finally { db.endTransaction() }
    }
    @Synchronized override fun close() = helper.close()
}

internal object RecordingJson {
    private fun connection(v: AiConnection) = JSONObject().put("protocol", v.protocol.name).put("url", v.url).put("key", v.apiKey).put("model", v.model)
    private fun connection(v: JSONObject) = AiConnection(AiProtocol.valueOf(v.getString("protocol")), v.getString("url"), v.getString("key"), v.getString("model"))
    fun settings(v: RecordingSettings): JSONObject = JSONObject().put("recording", v.recordingEnabled).put("automatic", v.automaticAnalysis)
        .put("analysis", connection(v.analysis)).put("transcription", connection(v.transcription)).put("mode", v.captureMode.name).put("folder", v.systemFolder)
    fun settings(v: JSONObject) = RecordingSettings(v.getBoolean("recording"), v.getBoolean("automatic"), connection(v.getJSONObject("analysis")),
        connection(v.getJSONObject("transcription")), CaptureMode.valueOf(v.getString("mode")), v.nullableString("folder"))
    private fun analysis(v: CallAnalysis) = JSONObject().put("name", v.name).put("organization", v.organization).put("position", v.position)
        .put("purpose", v.purpose).put("evidence", v.evidence).put("identity", v.inferredIdentity).put("summary", v.summary).put("actions", JSONArray(v.actionItems))
    private fun analysis(v: JSONObject) = CallAnalysis(v.getString("name"), v.getString("organization"), v.getString("position"), v.getString("purpose"),
        v.getString("evidence"), v.getString("identity"), v.getString("summary"), v.getJSONArray("actions").let { a -> List(a.length()) { a.getString(it) } })
    fun archive(v: CallArchive): JSONObject = JSONObject().put("id", v.callId).put("number", v.number).put("started", v.startedAtEpochMillis)
        .put("generation", v.generation).put("status", v.status.name).put("source", v.source.name).put("audio", v.audioPath).put("quality", v.audioQuality)
        .put("duration", v.durationMillis).put("transcript", v.transcript).put("analysis", v.analysis?.let(::analysis)).put("error", v.error)
        .put("attempts", v.attempts).put("next", v.nextAttemptAt).put("analyzed", v.analyzedAt).put("analysisModel", v.analysisModel)
        .put("transcriptionModel", v.transcriptionModel).put("protocol", v.analysisProtocol?.name).put("manual", v.manualAnalysis)
    fun archive(v: JSONObject) = CallArchive(v.getLong("id"), v.getString("number"), v.getLong("started"), v.getLong("generation"),
        ArchiveStatus.valueOf(v.getString("status")), RecordingSource.valueOf(v.getString("source")), v.nullableString("audio"), v.getString("quality"),
        v.nullableLong("duration"), v.getString("transcript"), v.optJSONObject("analysis")?.let(::analysis), v.nullableString("error"), v.getInt("attempts"),
        v.getLong("next"), v.nullableLong("analyzed"), v.getString("analysisModel"), v.getString("transcriptionModel"), v.nullableString("protocol")?.let(AiProtocol::valueOf), v.optBoolean("manual", false))
    private fun JSONObject.nullableString(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.nullableLong(key: String) = if (isNull(key)) null else getLong(key)
}
