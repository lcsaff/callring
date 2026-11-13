package com.greatwall.callring.data.backup

import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyManager
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.storage.NumberProfileResetPort
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.storage.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Private application files only. No device provider or document mutation capabilities. */
internal class AndroidNumberProfileResetPort(
    private val context:Context, private val graph:BackendGraph, private val configuration:BackendConfiguration,
    private val owner:BackupGraphOwner,
    private val journalFactory:()->RestoreJournal={RestoreCoordinator.journal(context,configuration.databaseName)},
) : NumberProfileResetPort {
    private class Ticket(val stage:File,val revision:Long,val widgets:Map<String,Any>,val preview:NumberProfileResetPreview,val fingerprint:ByteArray) {
        var choice:Boolean?=null
        var receipt:NumberProfileResetReceipt?=null
    }
    private val serial=Any()
    private val tickets=linkedMapOf<String,Ticket>()
    private val core get()=context.getDatabasePath(configuration.databaseName)
    private val recording get()=context.getDatabasePath(configuration.databaseName+"_recording.db")
    private val widgets get()=context.getSharedPreferences(configuration.widgetPreferencesName,Context.MODE_PRIVATE)
    private val audio get()=File(context.filesDir,configuration.databaseName+"_recordings")
    private val root get()=File(context.noBackupFilesDir,"backup-${configuration.databaseName}")

    override fun preview(checkCancellation:()->Unit):NumberProfileResetPreview=synchronized(serial) {
        checkCancellation();checkIdle()
        tickets.values.filter { it.receipt==null }.forEach { it.stage.deleteRecursively() };tickets.clear()
        check(root.isDirectory||root.mkdirs())
        val stage=File(root,"reset-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            graph.acquireBackupReadLease().use {
                graph.prepareBackupStorage()
                val revision=graph.coordinator.guard { graph.coordinator.marker() }
                val check={checkCancellation();requireSpace(stage,16L*1024*1024)}
                SqliteBackupCopy.copy(core,File(stage,"core.db"),check=check)
                SqliteBackupCopy.copy(recording,File(stage,"recording.db"),check=check)
                val values=WidgetPreferenceSnapshot.capture(widgets)
                val token=UUID.randomUUID().toString()
                val preview=NumberProfileResetPreview(token,SqliteBackupCopy.count(File(stage,"core.db"),"callers"),
                    SqliteBackupCopy.count(File(stage,"core.db"),"calls"),values.keys.count { it.startsWith("number_") }.toLong(),
                    SqliteBackupCopy.count(File(stage,"recording.db"),"archives"),audioBytes(File(stage,"recording.db"),check),
                    Math.addExact(configuration.clock.millis(),5*60_000L))
                graph.coordinator.guard { check();if(graph.coordinator.marker()!=revision) conflict() }
                tickets[token]=Ticket(stage,revision,values,preview,fingerprint(stage,check))
                preview
            }
        } catch(failure:Throwable) { stage.deleteRecursively();throw failure }
    }

    override fun confirm(token:String,keepRecordings:Boolean,checkCancellation:()->Unit):NumberProfileResetReceipt=synchronized(serial) {
        val ticket=tickets[token]?:conflict()
        if(ticket.choice!=null&&ticket.choice!=keepRecordings) conflict()
        ticket.receipt?.let { return@synchronized it }
        ticket.choice=keepRecordings
        checkCancellation();checkIdle()
        if(configuration.clock.millis()>=ticket.preview.expiresAtEpochMillis)conflict()
        if(!MessageDigest.isEqual(ticket.fingerprint,fingerprint(ticket.stage,checkCancellation)))conflict()
        graph.coordinator.guard { if(graph.coordinator.marker()!=ticket.revision)conflict() }
        if(WidgetPreferenceSnapshot.capture(widgets)!=ticket.widgets)conflict()
        val install=File(root,"reset-install-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val coreName="databases/${configuration.databaseName}"
            val recordingName="databases/${configuration.databaseName}_recording.db"
            val widgetName="shared_prefs/${configuration.widgetPreferencesName}.xml"
            val newCore=File(install,coreName);val newRecording=File(install,recordingName)
            copy(File(ticket.stage,"core.db"),newCore,checkCancellation)
            copy(File(ticket.stage,"recording.db"),newRecording,checkCancellation)
            NumberProfileResetStaging.transform(newCore,newRecording,keepRecordings,checkCancellation)
            SqliteBackupCopy.validate(newCore,core);SqliteBackupCopy.validate(newRecording,recording)
            val remaining=ticket.widgets.filterKeys { !it.startsWith("number_") }
            WidgetPreferenceSnapshot.write(File(install,widgetName),remaining)
            val names=mutableListOf(coreName,recordingName,widgetName,widgetName+".bak")
            for(name in listOf(coreName,recordingName))names+=listOf(name+"-wal",name+"-shm",name+"-journal")
            if(!keepRecordings)names+="files/${configuration.databaseName}_recordings"
            val journalBytes=listOf(core,recording).sumOf { it.length() }+(if(keepRecordings)0 else audio.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            requireSpace(install,Math.addExact(journalBytes,16L*1024*1024))
            checkCancellation();checkIdle()
            val restarted=owner.replace(graph) {
                // Owner permanently sealed and drained; cancellation cannot interrupt the switch.
                checkIdle()
                graph.coordinator.guard { if(graph.coordinator.marker()!=ticket.revision)conflict() }
                if(WidgetPreferenceSnapshot.capture(widgets)!=ticket.widgets)conflict()
                val journal=journalFactory()
                try { journal.replace(install,names) { WidgetPreferenceSnapshot.refresh(widgets,remaining) } }
                catch(failure:Exception) {
                    // Application retries interrupted rollback before constructing its next graph.
                    // Restore memory even when this first recovery fails partway through, so that
                    // a later successful file recovery cannot reopen with the switched cache.
                    try { journal.recover() }
                    finally { WidgetPreferenceSnapshot.refresh(widgets,ticket.widgets) }
                    throw failure
                }
            }
            ticket.preview.let { NumberProfileResetReceipt(it.profileCount,it.callCount,it.widgetBindingCount,it.archiveCount,it.audioBytes,keepRecordings,restarted) }.also { ticket.receipt=it }
        } finally {install.deleteRecursively();if(ticket.receipt!=null)ticket.stage.deleteRecursively()}
    }
    override fun discard(token:String) { synchronized(serial) { tickets[token]?.takeIf { it.choice==null }?.let { tickets.remove(token);it.stage.deleteRecursively() } } }
    private fun checkIdle() {
        if(context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED,"backup.phone_state_required"))
        @Suppress("DEPRECATION") val state=context.getSystemService(TelephonyManager::class.java).callState
        val mode=context.getSystemService(AudioManager::class.java).mode
        if(state!=TelephonyManager.CALL_STATE_IDLE||mode==AudioManager.MODE_IN_CALL||mode==AudioManager.MODE_IN_COMMUNICATION||com.greatwall.callring.entrypoint.recording.CaptureServiceActivity.busy())
            throw BackendFailure(ApiError(ErrorCode.CONFLICT,"backup.call_active",retryable=true))
    }
    private fun audioBytes(dbFile:File,check:()->Unit):Long? {
        var total=0L
        android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TEMP TABLE reset_audio(path TEXT PRIMARY KEY)")
            db.rawQuery("SELECT call_id FROM archives ORDER BY call_id",null).use { rows -> while(rows.moveToNext()) {
                check();val path=ArchiveBackupFields.string(ArchiveBackupFields.fields(db,rows.getLong(0),check)["audio"])?:continue
                val file=File(path);kotlin.check(file.canonicalFile.parentFile==audio.canonicalFile)
                db.execSQL("INSERT OR IGNORE INTO reset_audio VALUES(?)",arrayOf(path))
            } }
            db.rawQuery("SELECT path FROM reset_audio",null).use { rows -> while(rows.moveToNext()) {
                check();val file=File(rows.getString(0));if(!file.isFile)return null;total=Math.addExact(total,file.length())
            } }
        }
        return total
    }
    private fun copy(source:File,target:File,check:()->Unit) {
        kotlin.check(target.parentFile!!.isDirectory||target.parentFile!!.mkdirs())
        java.io.FileOutputStream(target).use { out -> source.inputStream().use { input -> val buffer=ByteArray(64*1024);while(true) { check();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n) } };out.fd.sync() }
    }
    private fun fingerprint(stage:File,check:()->Unit):ByteArray {
        val digest=MessageDigest.getInstance("SHA-256")
        stage.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(stage).invariantSeparatorsPath }.forEach { file ->
            check();digest.update(file.relativeTo(stage).invariantSeparatorsPath.toByteArray())
            file.inputStream().use { input -> val buffer=ByteArray(64*1024);while(true){check();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
        }
        return digest.digest()
    }
    private fun requireSpace(directory:File,needed:Long) { if(directory.usableSpace<needed)throw BackendFailure(ApiError(ErrorCode.STORAGE_FAILURE,"backup.insufficient_space")) }
    private fun conflict():Nothing=throw BackendFailure(ApiError(ErrorCode.CONFLICT,"storage.reset_preview_changed",retryable=true))
}
