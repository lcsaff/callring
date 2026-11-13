package com.greatwall.callring.data.recording

import com.greatwall.callring.application.conversation.query.safeValue
import com.greatwall.callring.application.ports.recording.RecordingArchiveMutations
import com.greatwall.callring.application.ports.recording.RecordingArchiveState
import com.greatwall.callring.application.callers.CallHistoryRecordingTarget
import com.greatwall.callring.application.ports.recording.RecordingIdentityReads
import com.greatwall.callring.application.ports.recording.RecordingRegistration
import com.greatwall.callring.application.ports.recording.RecordingRegistrationStore
import com.greatwall.callring.application.ports.recording.RecordingSettingsStore
import com.greatwall.callring.application.ports.recording.RecordingWorkflowReads
import com.greatwall.callring.application.ports.recording.SystemRecordingImportStore
import com.greatwall.callring.application.ports.recording.RetainedRecordingLinks
import com.greatwall.callring.application.ports.recording.RetainedRecordingOrigin
import com.greatwall.callring.application.ports.recording.ScannedSystemRecording
import com.greatwall.callring.domain.recording.ParsedSystemRecording
import com.greatwall.callring.data.query.RecordingScopedReadAdapter

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.DatabaseUtils
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.greatwall.callring.application.*
import com.greatwall.callring.application.recording.*
import com.greatwall.callring.data.preferences.updatePreferences
import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.application.recording.policy.*
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
    private val recordingVersion: () -> Long,
) : RecordingIdentityReads, RecordingArchiveState, RecordingArchiveMutations, RecordingWorkflowReads, RecordingSettingsStore, RecordingRegistrationStore, SystemRecordingImportStore, RetainedRecordingLinks, AutoCloseable {
    val readCounters = RecordingReadCounters()
    private val preferences = context.getSharedPreferences(namespace + "_recording", Context.MODE_PRIVATE)
    private val listIncarnation = java.util.UUID.randomUUID().toString()
    private val helper = object : SQLiteOpenHelper(context, namespace + "_recording.db", null, 8) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE archives (call_id INTEGER PRIMARY KEY, generation INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE deleted_archives (call_id INTEGER PRIMARY KEY)")
            createEvents(db)
            ArchiveProjectionIndex.create(db)
            RecordingArchivePages.create(db, initiallyReady = true)
            SystemRecordingImports.create(db)
            RetainedRecordingHistory.create(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { RecordingSchema.upgrade(db, oldVersion) }
        private fun createEvents(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE recording_events (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL UNIQUE, number TEXT NOT NULL, started INTEGER NOT NULL, account TEXT,direction TEXT NOT NULL DEFAULT 'INCOMING' CHECK(direction IN('INCOMING','OUTGOING','UNKNOWN')))")
            db.execSQL("CREATE TABLE recording_statistics_links (statistics_id INTEGER PRIMARY KEY, session_id TEXT NOT NULL)")
        }
    }
    init { helper.setWriteAheadLoggingEnabled(true) }
    @Volatile private var closed = false
    private fun ensureOpen() { check(!closed) { "recording.closed" } }
    private val writableDatabase: SQLiteDatabase get() { ensureOpen(); return helper.writableDatabase }
    private val readableDatabase: SQLiteDatabase get() { ensureOpen(); return helper.readableDatabase }
    @Synchronized fun scopedReads():com.greatwall.callring.application.ports.query.ScopedReadPort = RecordingScopedReadAdapter(writableDatabase,recordingVersion)
    /** Event identities are owned here, including when the statistics database contains no call. */
    @Synchronized override fun insertIncoming(sessionId: String, number: String, at: Long, account: String?, initial: (RecordingCall) -> CallArchive): RecordingRegistration {
        require(sessionId.isNotBlank() && number.isNotBlank())
        val db = writableDatabase
        db.beginTransaction()
        try {
            val row = ContentValues().apply { put("session_id", sessionId); put("number", number); put("started", at); put("account", account) }
            val eventInserted = db.insertWithOnConflict("recording_events", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            val call = db.rawQuery("SELECT id,number,started,direction FROM recording_events WHERE session_id=?", arrayOf(sessionId)).use {
                check(it.moveToFirst()); RecordingCall(-it.getLong(0), it.getString(1), it.getLong(2), null, com.greatwall.callring.domain.model.CallDirection.valueOf(it.getString(3)))
            }
            require(call.number == number && call.startedAt == at) { "recording.session_conflict" }
            var archiveInserted = false
            if (!isDeleted(call.id)) {
                val archive = initial(call)
                require(archive.callId == call.id)
                db.execSQL("INSERT OR IGNORE INTO archives(call_id,generation,payload) VALUES (?,?,?)",
                    arrayOf<Any>(call.id, archive.generation, RecordingJson.archive(archive).toString()))
                if (DatabaseUtils.longForQuery(db, "SELECT changes()", null) == 1L) {
                    ArchiveProjectionIndex(db).write(archive)
                    archiveInserted = true
                } else {
                    check(db.rawQuery("SELECT 1 FROM archives WHERE call_id=?", arrayOf(call.id.toString())).use { it.moveToFirst() }) {
                        "recording.initial_archive_not_inserted"
                    }
                }
            }
            db.setTransactionSuccessful()
            return RecordingRegistration(call, eventInserted, archiveInserted)
        } finally { db.endTransaction() }
    }
    @Synchronized override fun findRecording(id: Long): RecordingCall? {
        if (id >= 0 || id == Long.MIN_VALUE) return null
        return readableDatabase.rawQuery("SELECT id,number,started,direction FROM recording_events WHERE id=?", arrayOf((-id).toString())).use {
            if (it.moveToFirst()) RecordingCall(-it.getLong(0), it.getString(1), it.getLong(2), null, com.greatwall.callring.domain.model.CallDirection.valueOf(it.getString(3))) else null
        }
    }
    @Synchronized override fun insertStatisticsLink(statisticsId: Long, sessionId: String): Boolean {
        require(statisticsId > 0 && sessionId.isNotBlank())
        val row = ContentValues().apply { put("statistics_id", statisticsId); put("session_id", sessionId) }
        return writableDatabase.insertWithOnConflict("recording_statistics_links", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    @Synchronized override fun sessionRecording(sessionId: String): RecordingCall? = readableDatabase.rawQuery(
        "SELECT id,number,started,direction FROM recording_events WHERE session_id=?", arrayOf(sessionId)).use {
            if (it.moveToFirst()) RecordingCall(-it.getLong(0), it.getString(1), it.getLong(2), null, com.greatwall.callring.domain.model.CallDirection.valueOf(it.getString(3))) else null
        }
    @Synchronized override fun linkedRecording(statisticsId: Long): RecordingCall? = readableDatabase.rawQuery(
        "SELECT e.id,e.number,e.started,e.direction FROM recording_events e JOIN recording_statistics_links l ON e.session_id=l.session_id WHERE l.statistics_id=?",
        arrayOf(statisticsId.toString())).use { if (it.moveToFirst()) RecordingCall(-it.getLong(0), it.getString(1), it.getLong(2), null, com.greatwall.callring.domain.model.CallDirection.valueOf(it.getString(3))) else null }
    @Synchronized override fun isDeleted(callId: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM deleted_archives WHERE call_id=?", arrayOf(callId.toString())).use { it.moveToFirst() }
    override fun settings(): RecordingSettings = synchronized(preferences) { ensureOpen(); preferences.getString("config", null)?.let {
        RecordingJson.settings(JSONObject(cipher.decrypt(it)))
    } ?: RecordingSettings() }
    @Synchronized override fun hasRetainedHistory(): Boolean=readableDatabase.rawQuery("SELECT 1 FROM recording_retained_events LIMIT 1",null).use {it.moveToFirst()}
    @Synchronized override fun retainedOrigin(logId: String): RetainedRecordingOrigin? = readableDatabase.rawQuery(
        "SELECT event_id,number,call_started,direction FROM recording_retained_origins WHERE log_id=?",arrayOf(logId)).use { c ->
        if(c.moveToFirst())RetainedRecordingOrigin(c.getLong(0),c.getString(1),c.getLong(2),com.greatwall.callring.domain.model.CallDirection.valueOf(c.getString(3)))else null
    }
    @Synchronized override fun retainedCandidates(number: String, at: Long, direction: com.greatwall.callring.domain.model.CallDirection): List<RecordingCall> {
        val range=com.greatwall.callring.domain.recording.SystemRecordingAssociation.recordingStarts(at)
        return readableDatabase.rawQuery("SELECT e.id,e.number,e.started,e.direction FROM recording_retained_events r JOIN recording_events e ON e.id=r.event_id WHERE r.number=? AND r.started BETWEEN ? AND ? AND e.direction IN(?,'UNKNOWN') AND (EXISTS(SELECT 1 FROM archives a WHERE a.call_id=-e.id) OR EXISTS(SELECT 1 FROM deleted_archives d WHERE d.call_id=-e.id)) ORDER BY ABS(e.started-?) LIMIT 3",arrayOf(number.filter(Char::isDigit),range.first.toString(),range.last.toString(),direction.name,at.toString())).use { c -> buildList {while(c.moveToNext())add(RecordingCall(-c.getLong(0),c.getString(1),c.getLong(2),null,com.greatwall.callring.domain.model.CallDirection.valueOf(c.getString(3))))} }
    }
    @Synchronized override fun linkRetained(statisticsId: Long,eventId: Long,direction: com.greatwall.callring.domain.model.CallDirection): Boolean {
        require(statisticsId>0&&eventId>0)
        val db=writableDatabase;db.beginTransaction()
        try {
            val session=db.rawQuery("SELECT e.session_id FROM recording_events e JOIN recording_retained_events r ON r.event_id=e.id WHERE e.id=? AND (EXISTS(SELECT 1 FROM archives a WHERE a.call_id=-e.id) OR EXISTS(SELECT 1 FROM deleted_archives d WHERE d.call_id=-e.id))",arrayOf(eventId.toString())).use { c -> if(c.moveToFirst())c.getString(0)else null }?:return false
            if(generation(statisticsId)!=null||isDeleted(statisticsId))return false
            val inserted=insertStatisticsLink(statisticsId,session)
            if(inserted&&findRecording(-eventId)?.direction==com.greatwall.callring.domain.model.CallDirection.UNKNOWN&&direction!=com.greatwall.callring.domain.model.CallDirection.UNKNOWN) {
                db.execSQL("UPDATE recording_events SET direction=? WHERE id=?",arrayOf<Any>(direction.name,eventId))
                db.execSQL("UPDATE system_recording_sources SET direction=? WHERE archive_id=?",arrayOf<Any>(direction.name,-eventId))
                if(generation(-eventId)!=null) {
                    val fields=com.greatwall.callring.data.backup.ArchiveBackupFields.fields(db,-eventId,{})
                    val generation=Math.addExact(fields.getValue("generation").scalar!!.toLong(),1)
                    com.greatwall.callring.data.backup.ArchiveBackupFields.replace(db,-eventId,fields,mapOf("direction" to JSONObject.quote(direction.name),"generation" to generation.toString()))
                    db.execSQL("UPDATE archives SET generation=? WHERE call_id=?",arrayOf(generation,-eventId))
                    ArchiveProjectionIndex(db).remove(-eventId)
                    db.execSQL("UPDATE archive_list_state SET ready=0")
                }
            }
            db.setTransactionSuccessful();return inserted
        } finally {db.endTransaction()}
    }
    override fun persistSettings(value: RecordingSettings) = synchronized(preferences) {
        ensureOpen()
        updatePreferences(preferences, mapOf("config" to cipher.encrypt(RecordingJson.settings(value).toString())))
    }
    @Synchronized override fun get(callId: Long): CallArchive? = readableDatabase.query("archives", arrayOf("payload"),
        "call_id=?", arrayOf(callId.toString()), null, null, null).use { if (it.moveToFirst()) RecordingJson.archive(JSONObject(it.getString(0))).also { readCounters.add("fullArchiveDecodes") } else null }
    @Synchronized internal fun readCallHistory(
        targets: List<CallHistoryRecordingTarget>,
        cancelled: () -> Boolean,
    ) = RecordingArchivePages(readableDatabase, listIncarnation, readCounters, cancelled).callHistory(targets)

    @Synchronized internal fun advanceCallHistoryProjection(
        target: LegacyCallHistoryArchive,
        cancelled: () -> Boolean,
    ): Boolean? {
        if (cancelled()) throw java.util.concurrent.CancellationException("recording.cancelled")
        if (generation(target.id) != target.generation) return null
        return ArchiveProjectionIndex(writableDatabase, cancelled) {
            readCounters.add("historyRepairSourceBytes", it.toLong())
        }.advance(target.id, target.generation, 64 * 1024).complete
    }

    @Synchronized override fun queryArchives(query: ArchiveListQuery, cancelled: () -> Boolean): ArchiveListPage =
        RecordingArchivePages(writableDatabase, listIncarnation, readCounters, cancelled).query(query)
    @Synchronized override fun archiveStamp(): String = listIncarnation + ":" + DatabaseUtils.longForQuery(readableDatabase, "SELECT revision FROM archive_list_state WHERE id=1", null)
    @Synchronized override fun ownedAudio(callId: Long, cancelled: () -> Boolean) =
        RecordingMetadataReads(readableDatabase, readCounters, cancelled).ownedAudio(callId)
    @Synchronized override fun metadata(callId: Long, cancelled: () -> Boolean) =
        RecordingMetadataReads(readableDatabase, readCounters, cancelled).metadata(callId)
    @Synchronized override fun generation(callId: Long): Long? = readableDatabase.rawQuery(
        "SELECT generation FROM archives WHERE call_id=?", arrayOf(callId.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }
    @Synchronized override fun earliestPendingAt(cancelled: () -> Boolean) =
        RecordingMetadataReads(readableDatabase, readCounters, cancelled).earliestPendingAt()
    @Synchronized override fun dueCandidates(at: Long, afterId: Long?, limit: Int, cancelled: () -> Boolean) =
        RecordingMetadataReads(readableDatabase, readCounters, cancelled).dueCandidates(at, afterId, limit)
    @Synchronized override fun beginSystemScan(signature: String, at: Long, zone: String) = SystemRecordingImports(writableDatabase).begin(signature, at, zone)
    @Synchronized override fun prepareSystemMatches(cancelled: () -> Boolean): Boolean =
        !RecordingArchivePages(writableDatabase,listIncarnation,readCounters,cancelled).query(ArchiveListQuery(limit=1)).preparing
    @Synchronized override fun observeSystemFile(scanId: Long, entry: ScannedSystemRecording, parsed: ParsedSystemRecording, at: Long) = SystemRecordingImports(writableDatabase).observe(scanId, entry, parsed, at)
    @Synchronized override fun finishSystemScan(scanId: Long) = SystemRecordingImports(writableDatabase).finish(scanId)
    @Synchronized override fun systemCandidates(scanId: Long, afterKey: String?, limit: Int) = SystemRecordingImports(readableDatabase).candidates(scanId, afterKey, limit)
    @Synchronized override fun hasConflictingSystemSource(scanId: Long, sourceKey: String, parsed: ParsedSystemRecording, target: RecordingCall) = SystemRecordingImports(readableDatabase).conflicting(scanId, sourceKey, parsed, target)
    @Synchronized override fun hasConflictingSystemLink(sourceKey: String, source: RecordingCall, target: RecordingCall) = SystemRecordingImports(readableDatabase).conflictingLink(sourceKey,source,target)
    @Synchronized override fun releaseUnimportedSystemSource(sourceKey: String, callId: Long): Boolean {
        if (isDeleted(callId) || metadata(callId) { false }?.hasAudio == true) return false
        return SystemRecordingImports(writableDatabase).releaseUnimported(sourceKey,callId)
    }
    @Synchronized override fun sourceBinding(sourceKey: String): RecordingCall? {
        val bound = SystemRecordingImports(readableDatabase).binding(sourceKey) ?: return null
        return (if (bound.id < 0) findRecording(bound.id) else metadata(bound.id) { false }?.let { RecordingCall(it.callId,it.number,it.startedAtEpochMillis,it.durationMillis?.div(1000)) } ?: bound)?.copy(direction = bound.direction)
    }
    @Synchronized override fun sourceCompleted(sourceKey: String) = SystemRecordingImports(readableDatabase).completed(sourceKey)
    @Synchronized override fun completeSystemSource(sourceKey: String) = SystemRecordingImports(writableDatabase).complete(sourceKey)
    @Synchronized override fun matchingRecordingEvents(parsed: ParsedSystemRecording) = SystemRecordingImports(readableDatabase).matching(parsed)
    @Synchronized override fun systemRetry(at: Long?) = SystemRecordingImports(writableDatabase).retry(at)
    @Synchronized override fun systemSourcePage(afterKey: String?, limit: Int) = SystemRecordingImports(readableDatabase).sourcePage(afterKey,limit)
    @Synchronized override fun linkSystemStatistics(sourceKey: String, target: RecordingCall): Boolean {
        val call = sourceBinding(sourceKey) ?: return false
        if (isDeleted(call.id) || isDeleted(target.id)) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            var changed = false
            if (call.id < 0) {
                if (generation(target.id) != null) return false
                val session = db.rawQuery("SELECT session_id FROM recording_events WHERE id=?", arrayOf((-call.id).toString())).use { if(it.moveToFirst()) it.getString(0) else null } ?: return false
                val linked = linkedRecording(target.id)
                if (linked != null && linked.id != call.id) return false
                changed = insertStatisticsLink(target.id, session)
            } else if (call.id != target.id) return false
            if (call.direction != target.direction) {
                db.execSQL("UPDATE system_recording_sources SET direction=? WHERE source_key=?", arrayOf(target.direction.name,sourceKey))
                if (call.id < 0) db.execSQL("UPDATE recording_events SET direction=? WHERE id=?", arrayOf<Any>(target.direction.name,-call.id))
                get(call.id)?.let { archive ->
                    val direction = com.greatwall.callring.contracts.callers.CallDirection.valueOf(target.direction.name)
                    val automatic = !archive.manualAnalysis && archive.analysisIntent in setOf(ArchiveAnalysisIntent.AUTOMATIC,ArchiveAnalysisIntent.LEGACY_UNCLASSIFIED)
                    val status = when {
                        automatic && direction != com.greatwall.callring.contracts.callers.CallDirection.INCOMING -> if (archive.audioPath != null) ArchiveStatus.READY else ArchiveStatus.WAITING_RECORDING
                        automatic && !archive.resetPaused && call.direction == com.greatwall.callring.domain.model.CallDirection.UNKNOWN && archive.audioPath != null && archive.status == ArchiveStatus.READY && archive.nextAttemptAt != Long.MAX_VALUE && settings().automaticAnalysis -> ArchiveStatus.QUEUED
                        else -> archive.status
                    }
                    check(compareAndSet(call.id,archive.generation,archive.copy(generation=archive.generation+1,direction=direction,status=status,nextAttemptAt=archive.nextAttemptAt.takeIf { it == Long.MAX_VALUE } ?: 0)))
                }
                changed = true
            }
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }
    @Synchronized override fun registerSystemSource(sourceKey: String, parsed: ParsedSystemRecording, existing: RecordingCall, initial: (RecordingCall) -> CallArchive): RecordingRegistration {
        val db = writableDatabase
        db.beginTransaction()
        try {
            sourceBinding(sourceKey)?.let { bound ->
                require(com.greatwall.callring.domain.model.sameRecordingNumber(bound.number, parsed.number) && com.greatwall.callring.domain.recording.SystemRecordingAssociation.matches(bound.startedAt, parsed.startedAtEpochMillis)) { "recording.source_changed" }
                db.setTransactionSuccessful()
                return RecordingRegistration(bound,false,false)
            }
            val inserted = !isDeleted(existing.id) && generation(existing.id) == null && compareAndSet(existing.id,null,initial(existing))
            val result = RecordingRegistration(existing,false,inserted)
            SystemRecordingImports(db).bind(sourceKey,result.call)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
    @Synchronized override fun numberMetadata(number: String, cancelled: () -> Boolean) =
        RecordingMetadataReads(readableDatabase, readCounters, cancelled).numberMetadata(number)
    @Synchronized fun captureRecordingText(): Map<String, AiJson> {
        val db = readableDatabase
        db.beginTransactionNonExclusive()
        try {
            com.greatwall.callring.data.database.checkSnapshotSize(db)
            fun rows(sql: String): AiJson = db.rawQuery(sql, null).use { c -> AiJson.Arr(buildList {
                while (c.moveToNext()) add(AiJson.Obj((0 until c.columnCount).associate { i -> c.getColumnName(i) to when (c.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> AiJson.Null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> aiNumber(c.getLong(i))
                    android.database.Cursor.FIELD_TYPE_STRING -> aiString(c.getString(i))
                    else -> throw IllegalArgumentException("snapshot.recording_invalid")
                } }))
            }) }
            val events = aiObject("events" to rows("SELECT id,session_id,number,started,account FROM recording_events ORDER BY id"), "links" to rows("SELECT * FROM recording_statistics_links ORDER BY statistics_id"), "deleted" to rows("SELECT * FROM deleted_archives ORDER BY call_id"))
            val archives = db.rawQuery("SELECT payload FROM archives ORDER BY call_id", null).use { c -> AiJson.Arr(buildList {
                while (c.moveToNext()) { val text = c.getString(0); com.greatwall.callring.data.codec.StrictJson.validate(text); add(com.greatwall.callring.application.conversation.query.safeValue(RecordingJson.archive(JSONObject(text)))) }
            }) }
            db.setTransactionSuccessful()
            return mapOf("D15" to events, "D16" to archives)
        } finally { db.endTransaction() }
    }
    @Synchronized override fun compareAndSet(callId: Long, expectedGeneration: Long?, value: CallArchive): Boolean {
        require(callId == value.callId)
        val row = ContentValues().apply { put("call_id", callId); put("generation", value.generation); put("payload", RecordingJson.archive(value).toString()) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val success = if (expectedGeneration == null) {
                db.execSQL("INSERT OR IGNORE INTO archives(call_id,generation,payload) VALUES (?,?,?)",
                    arrayOf<Any>(callId, value.generation, RecordingJson.archive(value).toString()))
                DatabaseUtils.longForQuery(db, "SELECT changes()", null) == 1L
            }
            else db.update("archives", row, "call_id=? AND generation=?", arrayOf(callId.toString(), expectedGeneration.toString())) == 1
            if (success) {db.delete("deleted_archives", "call_id=?", arrayOf(callId.toString()));ArchiveProjectionIndex(db).write(value)}
            db.setTransactionSuccessful()
            return success
        } finally { db.endTransaction() }
    }
    @Synchronized override fun delete(callId: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val value = db.delete("archives", "call_id=?", arrayOf(callId.toString())) > 0
            ArchiveProjectionIndex(db).remove(callId)
            db.execSQL("INSERT OR IGNORE INTO deleted_archives(call_id) VALUES (?)", arrayOf(callId))
            db.setTransactionSuccessful()
            return value
        } finally { db.endTransaction() }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        helper.close()
    }
}

internal object RecordingJson {
    private fun connection(v: AiConnection) = JSONObject().put("protocol", v.protocol.name).put("url", v.url).put("key", v.apiKey).put("model", v.model).put("thinkingMode", v.thinkingMode.name)
    private fun connection(v: JSONObject) = AiConnection(AiProtocol.valueOf(v.getString("protocol")), v.getString("url"), v.getString("key"), v.getString("model"), ThinkingMode.valueOf(v.optString("thinkingMode", ThinkingMode.DISABLED.name)))
    fun settings(v: RecordingSettings): JSONObject = JSONObject().put("recording", v.recordingEnabled).put("automatic", v.automaticAnalysis)
        .put("analysis", connection(v.analysis)).put("mode", v.captureMode.name).put("folder", v.systemFolder)
        .put("conversation", JSONObject().put("version", 1).put("context", v.conversationBudget.context).put("output", v.conversationBudget.output).put("compatibility",v.conversationCompatibility.normalized().name))
    fun settings(v: JSONObject) = RecordingSettings(v.getBoolean("recording"), v.getBoolean("automatic"), connection(v.getJSONObject("analysis")),
        CaptureMode.valueOf(v.getString("mode")), v.nullableString("folder"),
        v.optJSONObject("conversation")?.let { require(it.getInt("version") == 1); ConversationBudget(it.getLong("context"), it.getLong("output")).also { b -> b.validate() } } ?: ConversationBudget(),
        v.optJSONObject("conversation")?.optString("compatibility", "AUTO")?.let(ConversationCompatibility::valueOf)?.normalized() ?: ConversationCompatibility.AUTO)
    private fun analysis(v: CallAnalysis) = JSONObject().put("name", v.name).put("organization", v.organization).put("position", v.position)
        .put("purpose", v.purpose).put("evidence", v.evidence).put("identity", v.inferredIdentity).put("summary", v.summary).put("actions", JSONArray(v.actionItems)).put("department", v.department).put("region", v.region).put("fieldEvidence", JSONObject(v.fieldEvidence))
    private fun analysis(v: JSONObject) = CallAnalysis(v.getString("name"), v.getString("organization"), v.getString("position"), v.getString("purpose"),
        v.getString("evidence"), v.getString("identity"), v.getString("summary"), v.getJSONArray("actions").let { a -> List(a.length()) { a.getString(it) } }, v.optString("department", ""), v.optString("region", ""),
        v.optJSONObject("fieldEvidence")?.let { fields -> fields.keys().asSequence().associateWith { fields.getString(it) } }.orEmpty())
    fun archive(v: CallArchive): JSONObject = JSONObject().put("id", v.callId).put("number", v.number).put("started", v.startedAtEpochMillis)
        .put("generation", v.generation).put("status", v.status.name).put("source", v.source.name).put("audio", v.audioPath).put("quality", v.audioQuality)
        .put("duration", v.durationMillis).put("transcript", v.transcript).put("analysis", v.analysis?.let(::analysis)).put("error", v.error)
        .put("attempts", v.attempts).put("next", v.nextAttemptAt).put("analyzed", v.analyzedAt).put("analysisModel", v.analysisModel)
        .put("transcriptionModel", v.transcriptionModel).put("protocol", v.analysisProtocol?.name).put("manual", v.manualAnalysis).put("enrichmentBaseline", v.enrichmentBaseline?.let { JSONObject().put("epoch", it.epoch).put("revisions", JSONObject(it.revisions)).put("eligible", it.eligible) }).put("enrichmentSourceGeneration", v.enrichmentSourceGeneration)
        .put("analysisIntent", v.analysisIntent.name).put("manualAnalysisState", v.manualAnalysisState?.name)
        .put("manualAnalysisResultVersion", v.manualAnalysisResultVersion).put("direction", v.direction.name).put("resetPaused", v.resetPaused)
    fun archive(v: JSONObject): CallArchive {
        require(!v.has("resetPaused")||v.get("resetPaused") is Boolean) {"recording.invalid_reset_pause"}
        val archive = CallArchive(v.getLong("id"), v.getString("number"), v.getLong("started"), v.getLong("generation"),
        ArchiveStatus.valueOf(v.getString("status")), RecordingSource.valueOf(v.getString("source")), v.nullableString("audio"), v.getString("quality"),
        v.nullableLong("duration"), v.getString("transcript"), v.optJSONObject("analysis")?.let(::analysis), v.nullableString("error"), v.getInt("attempts"),
        v.getLong("next"), v.nullableLong("analyzed"), v.getString("analysisModel"), v.getString("transcriptionModel"), v.nullableString("protocol")?.let(AiProtocol::valueOf), v.optBoolean("manual", false),
        v.optJSONObject("enrichmentBaseline")?.let { base -> val revisions = base.getJSONObject("revisions"); CallerIdentityBaseline(base.getString("epoch"), revisions.keys().asSequence().associateWith { revisions.getLong(it) }, base.optBoolean("eligible", true)) },
        v.nullableLong("enrichmentSourceGeneration"),
        analysisIntent = v.nullableString("analysisIntent")?.let(ArchiveAnalysisIntent::valueOf) ?: ArchiveAnalysisIntent.AUTOMATIC,
        manualAnalysisState = v.nullableString("manualAnalysisState")?.let(ManualAnalysisState::valueOf),
        manualAnalysisResultVersion = v.nullableLong("manualAnalysisResultVersion"),
        direction = com.greatwall.callring.contracts.callers.CallDirection.valueOf(v.optString("direction", "INCOMING")), resetPaused = v.optBoolean("resetPaused", false))
        return if (v.has("analysisIntent")) archive else RecordingSchema.decodeLegacyAnalysis(archive)
    }
    private fun JSONObject.nullableString(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.nullableLong(key: String) = if (isNull(key)) null else getLong(key)
}
