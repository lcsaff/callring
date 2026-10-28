package com.greatwall.callring.platform.storage

import com.greatwall.callring.bootstrap.RecordingAssembly
import com.greatwall.callring.application.storage.StorageManagementService
import com.greatwall.callring.data.recording.AndroidRecordingStore
import com.greatwall.callring.data.recording.RecordingCipher
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.storage.*
import com.greatwall.callring.contracts.common.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],manifest=Config.NONE)
class StorageCleanupIntegrationTest {
    @Test fun previewAndFormalDeletionNeverDecodeBodyAndKeepExternalSourceAndStatisticsIdentity() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val namespace="storage-cleanup"
        val store=AndroidRecordingStore(context,namespace,object:RecordingCipher {
            override fun encrypt(value:String)=value
            override fun decrypt(value:String)=value
        },recordingVersion={0})
        val runtime=RecordingAssembly(context,namespace,findCall={error("statistics must not be read")},activeCallId={null},schedule={},storeFactory={store},operationActive={true})
        runtime.directory.mkdirs()
        val original=File(context.cacheDir,"external-original.wav").apply { writeBytes(ByteArray(100)) }
        val file=File(runtime.directory,"owned.wav").apply { writeBytes(ByteArray(50)) }
        val missing=File(runtime.directory,"missing.wav")
        for ((id,audio) in listOf(1L to file,2L to missing)) {
            store.compareAndSet(id,null,CallArchive(id,"31234",id,generation=1,status=ArchiveStatus.COMPLETE,audioPath=audio.absolutePath,
                transcript="large".repeat(100000),analysis=CallAnalysis(summary="keep body detail only")))
        }
        val api=StorageManagementService(runtime.service,AndroidStorageInventory(context,runtime.directory),operationActive={true})
        val preview=(api.preview(ArchiveCleanupSelection(0,10,setOf(ArchiveStatus.COMPLETE))) as ApiResult.Success).value
        assertEquals(50L,preview.audioBytes)
        val receipt=(api.confirm(preview.token) as ApiResult.Success).value
        assertTrue(receipt.items.all { it.status==CleanupItemStatus.COMMITTED })
        assertFalse(file.exists()); assertTrue(original.exists())
        assertTrue(store.isDeleted(1)); assertTrue(store.isDeleted(2))
        assertEquals(0L,store.readCounters.snapshot()["fullArchiveDecodes"] ?: 0)
        assertTrue((store.readCounters.snapshot()["sourceBytes"] ?: 0) < 128*1024)
        runtime.close()
    }
}
