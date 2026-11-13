package com.greatwall.callring.data.backup

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.contracts.callers.CallHistoryQuery
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.model.SystemCallRecord
import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import kotlinx.coroutines.runBlocking
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
@Config(sdk=[33],manifest=Config.NONE)
class NumberProfileResetTest {
    @Test fun keepAndDiscard()=runBlocking {
        for(keep in listOf(true,false))Fixture().use { f ->
            f.seed()
            val before=f.graph
            val preview=f.port.preview {}
            assertEquals(1L,preview.profileCount);assertEquals(2L,preview.callCount);assertEquals(1L,preview.archiveCount)
            assertEquals(4L,preview.audioBytes)
            val receipt=f.port.confirm(preview.token,keep){}
            assertEquals(keep,receipt.keepRecordings);assertFalse(before.acceptsOperations())
            assertTrue(f.graph.api.callers.queryCallHistory().requiredValue().entries.isEmpty())
            val prefs=f.context.getSharedPreferences(f.config.widgetPreferencesName,Context.MODE_PRIVATE)
            assertFalse(prefs.contains("number_7"));assertEquals(1.25f,prefs.getFloat("extension_float",0f))
            assertEquals(setOf("one","two"),prefs.getStringSet("extension_set",emptySet()))
            f.graph.backend.prepareNumberRule().requiredValue()
            f.graph.backend.importSystemCalls(f.records)
            assertTrue(f.graph.backend.importSystemCalls(f.records).isEmpty())
            val history=f.graph.api.callers.queryCallHistory(CallHistoryQuery(number="31234")).requiredValue()
            assertTrue(history.entries.all { it.call.id>f.oldMax })
            assertEquals(2,history.entries.size)
            assertEquals(keep,f.audio.exists())
            if(keep) {
                val linked=history.entries.single { it.call.startedAtEpochMillis==10000L }.recording.archiveId!!
                assertTrue(linked<0)
                val archive=f.graph.recordings.store.get(linked)!!
                assertEquals("retained transcript",archive.transcript);assertEquals("summary",archive.analysis!!.summary)
                assertTrue(archive.resetPaused);assertEquals(ArchiveStatus.COMPLETE,archive.status)
                assertNull(f.graph.recordings.workflow.nextRunAt())
                assertArrayEquals(byteArrayOf(1,2,3,4),File(archive.audioPath!!).readBytes())
            } else assertTrue(f.graph.recordings.store.retainedCandidates("31234",10000,com.greatwall.callring.domain.model.CallDirection.INCOMING).isEmpty())
            assertEquals(receipt,f.port.confirm(preview.token,keep){})
            assertThrows(com.greatwall.callring.application.ports.BackendFailure::class.java) { f.port.confirm(preview.token,!keep){} }
        }
    }
    @Test fun respectsDeleted()=runBlocking {
        for(keep in listOf(true,false))Fixture().use { f ->
            f.seed();f.graph.recordings.store.delete(f.oldMax)
            val p=f.port.preview {};f.port.confirm(p.token,keep){}
            f.graph.backend.prepareNumberRule().requiredValue();f.graph.backend.importSystemCalls(f.records)
            val newId=f.graph.api.callers.queryCallHistory().requiredValue().entries.single { it.call.startedAtEpochMillis==20000L }.call.id
            val canonical=f.graph.recordings.store.linkedRecording(newId)!!
            assertTrue(f.graph.recordings.store.isDeleted(canonical.id))
            assertNull(f.graph.recordings.store.get(canonical.id))
        }
    }
    @Test fun widgetConflict()=runBlocking {
        Fixture().use { f ->
            f.seed();val p=f.port.preview {}
            f.context.getSharedPreferences(f.config.widgetPreferencesName,Context.MODE_PRIVATE).edit().putFloat("extension_float",9f).commit()
            assertThrows(com.greatwall.callring.application.ports.BackendFailure::class.java){f.port.confirm(p.token,true){}}
            assertTrue(f.graph.acceptsOperations());assertTrue(f.audio.exists())
        }
    }
    @Test fun sourceLess()=runBlocking {
        Fixture().use { f ->
            f.seed()
            val registered=f.graph.recordings.registrations.registerIncoming("manual","31234",30000,null,null)
            val old=f.graph.recordings.store.get(registered.id)!!
            f.graph.recordings.store.compareAndSet(registered.id,old.generation,old.copy(generation=old.generation+1,status=ArchiveStatus.ANALYZING,audioPath=f.audio.path,transcript="already paid transcript"))
            val p=f.port.preview {};f.port.confirm(p.token,true){}
            val extra=SystemCallRecord("manual-history","31234",30000,5,CallResult.ANSWERED)
            f.graph.backend.prepareNumberRule().requiredValue();f.graph.backend.importSystemCalls(f.records+extra)
            val core=f.graph.api.callers.queryCallHistory().requiredValue().entries.single { it.call.startedAtEpochMillis==30000L }.call
            assertEquals(registered.id,f.graph.recordings.store.linkedRecording(core.id)!!.id)
            val kept=f.graph.recordings.store.get(registered.id)!!
            assertEquals("already paid transcript",kept.transcript);assertTrue(kept.resetPaused)
            assertEquals(Long.MAX_VALUE,kept.nextAttemptAt)
            f.graph.recordings.workflow.processPending()
            assertEquals(kept,f.graph.recordings.store.get(registered.id))
        }
    }
    @Test fun directionCrowding()=runBlocking {
        Fixture().use {f ->
            f.seed()
            repeat(3) {i ->
                val call=f.graph.recordings.registrations.registerIncoming("crowd$i","31234",100010L+i,null,null)
                val archive=f.graph.recordings.store.get(call.id)!!
                f.graph.recordings.store.compareAndSet(call.id,archive.generation,archive.copy(generation=archive.generation+1,direction=com.greatwall.callring.contracts.callers.CallDirection.OUTGOING))
            }
            val correct=f.graph.recordings.registrations.registerIncoming("correct","31234",100020,null,null)
            SQLiteDatabase.openDatabase(f.context.getDatabasePath(f.namespace+"_recording.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {it.execSQL("UPDATE recording_events SET direction='OUTGOING' WHERE session_id LIKE 'crowd%'")}
            val preview=f.port.preview {};f.port.confirm(preview.token,true){}
            val outgoing=(0..2).map {i -> SystemCallRecord("out$i","31234",100000L+i,5,CallResult.ANSWERED,direction=com.greatwall.callring.domain.model.CallDirection.OUTGOING)}
            val incoming=SystemCallRecord("correct-in","31234",100010,5,CallResult.ANSWERED)
            f.graph.backend.prepareNumberRule().requiredValue();f.graph.backend.importSystemCalls(outgoing+incoming)
            val core=f.graph.api.callers.queryCallHistory().requiredValue().entries.single {it.call.startedAtEpochMillis==100010L}.call
            assertEquals(correct.id,f.graph.recordings.store.linkedRecording(core.id)?.id)
        }
    }
    @Test fun unknownDirection()=runBlocking {
        Fixture().use {f ->
            f.seed()
            val call=f.graph.recordings.registrations.registerIncoming("unknown","31234",40000,null,null)
            val archive=f.graph.recordings.store.get(call.id)!!
            f.graph.recordings.store.compareAndSet(call.id,archive.generation,archive.copy(generation=archive.generation+1,status=ArchiveStatus.READY,audioPath=f.audio.path,direction=com.greatwall.callring.contracts.callers.CallDirection.UNKNOWN))
            SQLiteDatabase.openDatabase(f.context.getDatabasePath(f.namespace+"_recording.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {it.execSQL("UPDATE recording_events SET direction='UNKNOWN' WHERE session_id='unknown'")}
            val p=f.port.preview {};f.port.confirm(p.token,true){}
            f.graph.backend.prepareNumberRule().requiredValue()
            f.graph.backend.importSystemCalls(listOf(SystemCallRecord("known-out","31234",40000,5,CallResult.ANSWERED,direction=com.greatwall.callring.domain.model.CallDirection.OUTGOING)))
            f.reopen()
            val saved=f.graph.recordings.store.get(call.id)!!
            assertEquals(com.greatwall.callring.contracts.callers.CallDirection.OUTGOING,saved.direction)
            assertTrue(saved.resetPaused);assertEquals(ArchiveStatus.READY,saved.status);assertEquals(Long.MAX_VALUE,saved.nextAttemptAt)
            var page=f.graph.api.recordings.queryArchives().requiredValue()
            repeat(50){if(page.preparing)page=f.graph.api.recordings.queryArchives().requiredValue()}
            assertFalse(page.preparing);assertTrue(f.graph.recordings.store.metadata(call.id){false}!!.resetPaused)
            SQLiteDatabase.openDatabase(f.context.getDatabasePath(f.namespace+"_recording.db").path,null,SQLiteDatabase.OPEN_READONLY).use {db ->
                db.rawQuery("SELECT metadata,reset_paused FROM archive_projection WHERE call_id=?",arrayOf(call.id.toString())).use {c ->assertTrue(c.moveToFirst());assertFalse(c.getString(0).contains("resetPaused"));assertEquals(1,c.getInt(1))}
            }
            assertFalse(f.graph.recordings.store.captureRecordingText().getValue("D16").encode().contains("resetPaused"))
        }
    }
    @Test fun journalRollback()=runBlocking {
        for(failAt in listOf(1,3,13))Fixture().use {f ->
            f.seed();val p=f.port.preview {}
            var count=0;f.checkpoint={if(++count==failAt)throw java.io.IOException("interrupted switch")}
            assertThrows(java.io.IOException::class.java){f.port.confirm(p.token,false){}}
            assertEquals(2,f.graph.api.callers.queryCallHistory().requiredValue().entries.size)
            assertTrue(f.audio.exists());assertTrue(f.context.getSharedPreferences(f.config.widgetPreferencesName,Context.MODE_PRIVATE).contains("number_7"))
            assertEquals("retained transcript",f.graph.recordings.store.get(1)!!.transcript)
        }
    }
    @Test fun rollbackCacheRetry()=runBlocking {
        Fixture().use {f ->
            f.seed();val p=f.port.preview {}
            var commitFailed=false;var rollbackFailed=false
            f.syncDirectory={directory ->
                val state=File(directory,"state")
                if(directory==File(f.work,"journal")&&state.isFile&&!commitFailed) {
                    val phase=java.io.DataInputStream(state.inputStream()).use {it.readUTF();it.readUTF()}
                    if(phase=="COMMITTED"){commitFailed=true;throw java.io.IOException("commit marker sync interrupted")}
                }
            }
            f.checkpoint={if(commitFailed&&!rollbackFailed){rollbackFailed=true;throw java.io.IOException("first rollback interrupted")}}
            assertThrows(java.io.IOException::class.java){f.port.confirm(p.token,false){}}
            assertTrue(commitFailed);assertTrue(rollbackFailed)
            assertEquals(2,f.graph.api.callers.queryCallHistory().requiredValue().entries.size)
            assertTrue(f.audio.exists())
            val widgets=f.context.getSharedPreferences(f.config.widgetPreferencesName,Context.MODE_PRIVATE)
            assertTrue("The reopened owner must see the recovered widget binding in memory",widgets.contains("number_7"))
            assertEquals(setOf("one","two"),widgets.getStringSet("extension_set",emptySet()))
        }
    }
    private class Fixture:AutoCloseable {
        val context=RuntimeEnvironment.getApplication()
        val namespace="z${UUID.randomUUID().toString().take(6)}"
        val config=BackendConfiguration(AppearanceSettings(ThemeSelection.PRESET,"default",null,ColorMode.FOLLOW_SYSTEM),databaseName=namespace,
            widgetPreferencesName=namespace+"_widget",ringPreferencesName=namespace+"_ring",appearancePreferencesName=namespace+"_appearance")
        val work=File(context.cacheDir,namespace).apply { mkdir() }
        lateinit var graph:BackendGraph
        private val owner:BackupGraphOwner=BackupGraphOwner { old,mutation -> old.close();try {mutation()}catch(failure:Exception){recover();throw failure}finally {graph=open()};true }
        private fun open():BackendGraph=BackendGraph(owner,context,config,object:UiNotificationEntryPoints {
            override fun home(requestCode:Int)=PendingIntent.getActivity(context,requestCode,Intent("test"),PendingIntent.FLAG_IMMUTABLE)
            override fun playback(preview:Boolean,stopAction:PendingIntent)=Notification()
        })
        val records=listOf(SystemCallRecord("one","31234",10000,5,CallResult.ANSWERED),SystemCallRecord("two","31234",20000,5,CallResult.ANSWERED))
        val audio=File(context.filesDir,namespace+"_recordings/owned.wav")
        var oldMax=0L
        lateinit var port:AndroidNumberProfileResetPort
        var checkpoint:()->Unit={}
        var syncDirectory:(File)->Unit={}
        private fun journal()=RestoreJournal(File(work,"journal"),context.dataDir,checkpoint={checkpoint()},syncDirectory={syncDirectory(it)})
        private fun recover(){journal().recover()}
        fun reopen(){graph.close();graph=open()}
        init {Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE);graph=open()}
        suspend fun seed() {
            graph.backend.prepareNumberRule().requiredValue();graph.backend.importSystemCalls(records)
            val calls=graph.api.callers.queryCallHistory().requiredValue().entries.map {it.call}
            oldMax=calls.maxOf {it.id}
            audio.parentFile!!.mkdirs();audio.writeBytes(byteArrayOf(1,2,3,4))
            val first=calls.minBy {it.id}
            graph.recordings.store.compareAndSet(first.id,null,CallArchive(first.id,"31234",10000,generation=2,status=ArchiveStatus.COMPLETE,
                source=RecordingSource.MICROPHONE,audioPath=audio.path,transcript="retained transcript",analysis=CallAnalysis(summary="summary")))
            context.getSharedPreferences(config.widgetPreferencesName,Context.MODE_PRIVATE).edit().putString("number_7","31234").putFloat("extension_float",1.25f).putStringSet("extension_set",setOf("one","two")).commit()
            port=AndroidNumberProfileResetPort(context,graph,config,owner,{journal()})
        }
        override fun close(){graph.close();context.deleteDatabase(namespace);context.deleteDatabase(namespace+"_recording.db");audio.parentFile!!.deleteRecursively();work.deleteRecursively();File(context.noBackupFilesDir,"backup-$namespace").deleteRecursively()}
    }
}
