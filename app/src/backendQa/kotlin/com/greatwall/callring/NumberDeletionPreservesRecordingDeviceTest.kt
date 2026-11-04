package com.greatwall.callring

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.test.InstrumentationTestCase
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import kotlinx.coroutines.runBlocking
import java.io.File

/** Isolated real SQLite/files test. No production package, real call, or model service. */
class NumberDeletionPreservesRecordingDeviceTest : InstrumentationTestCase() {
    fun testDeleteNumberPreservesRecordings(): Unit = runBlocking {
        val context=instrumentation.targetContext
        check(context.packageName=="com.greatwall.callring.backendqa")
        val namespace="numberdelete_${System.nanoTime()}"
        val configuration=BackendConfiguration(AppearanceSettings(ThemeSelection.PRESET,"default",null,ColorMode.FOLLOW_SYSTEM),
            databaseName=namespace,ringPreferencesName=namespace+"_ring",widgetPreferencesName=namespace+"_widgets",appearancePreferencesName=namespace+"_appearance")
        val notifications=object:UiNotificationEntryPoints {
            override fun home(requestCode:Int)=PendingIntent.getActivity(context,requestCode,Intent("fixture"),PendingIntent.FLAG_IMMUTABLE)
            override fun playback(preview:Boolean,stopAction:PendingIntent)=Notification()
        }
        fun graph()=BackendGraph(BackupGraphOwner { _,_->error("no restore") },context,configuration,notifications)
        val original=File(context.noBackupFilesDir,namespace+".original").apply { writeBytes(byteArrayOf(1,2,3)) }
        val directory=File(context.filesDir,namespace+"_recordings")
        val audio=File(directory,"fixture.wav")
        try {
            var id=0L
            var expected:CallArchive?=null
            graph().use { g ->
                g.api.settings.setStatisticsEnabled(true).requiredValue()
                g.api.callers.saveProfile(SaveCallerProfile("31234","fixture","unit",null)).requiredValue()
                val at=System.currentTimeMillis()
                id=g.backend.recordIncoming("31234",at,CallDecision.NORMAL_RINGING)
                assertTrue(id>0)
                directory.mkdirs(); original.copyTo(audio)
                expected=CallArchive(id,"31234",at,generation=2,status=ArchiveStatus.COMPLETE,source=RecordingSource.SYSTEM,
                    audioPath=audio.path,transcript="keep transcript",analysis=CallAnalysis(summary="keep summary"))
                assertTrue(g.recordings.store.compareAndSet(id,null,expected!!))
                val impact=g.api.numberDeletion.impact("31234").requiredValue()
                assertEquals(1,impact.callCount); assertEquals(1,impact.archiveCount)
                g.api.numberDeletion.delete(DeleteNumberCommand("31234",1,1,0)).requiredValue()
                assertFalse(g.api.callers.numberDataImpact("31234").requiredValue().profileExists)
                assertEquals(0,g.api.callers.numberDataImpact("31234").requiredValue().callCount)
                assertEquals(expected,g.api.recordings.getArchive(id).requiredValue())
                assertTrue(original.readBytes().contentEquals(audio.readBytes()))
            }
            graph().use { g ->
                assertEquals(expected,g.api.recordings.getArchive(id).requiredValue())
                assertFalse(g.recordings.store.isDeleted(id))
                assertEquals(1,g.api.recordings.queryArchives().requiredValue().items.size)
            }
        } finally {
            audio.delete(); directory.delete(); original.delete()
            context.deleteDatabase(namespace); context.deleteDatabase(namespace+"_recording.db")
        }
    }
}
