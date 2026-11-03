package com.greatwall.callring.backup

import com.greatwall.callring.application.backup.BackupService
import com.greatwall.callring.application.ports.backup.BackupArchivePort
import com.greatwall.callring.contracts.backup.*
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BackupServiceTest {
    private val summary = BackupSummary(1, 2, 3, 4, 0, 0, false, false)
    @Test fun clearsPasswordsAndDiscardDoesNotOverwriteReadyProgress() = runBlocking {
        val service = BackupService(port(), Dispatchers.Unconfined) { true }
        val secret = "password".toCharArray()
        assertTrue(service.preview(DocumentRef("content://backup"), secret) is ApiResult.Success)
        assertTrue(secret.all { it == 0.toChar() })
        service.discard("obsolete-token")
        assertEquals(BackupPhase.READY, service.progress.value.phase)
    }
    @Test fun concurrentHostReturnsBusyWithoutStartingAnotherArchiveOperation() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = BackupService(object : BackupArchivePort by port() {
            override fun export(destination: DocumentRef, password: CharArray, includeAudio: Boolean, checkCancellation: () -> Unit): BackupSummary {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return summary
            }
        }, Dispatchers.IO) { true }
        val first = async { service.export(DocumentRef("content://backup"), "password".toCharArray(), false) }
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        val secondSecret = "password".toCharArray()
        val second = service.preview(DocumentRef("content://backup"), secondSecret)
        release.countDown(); first.await()
        assertTrue(second is ApiResult.Failure)
        assertEquals("backup.busy", (second as ApiResult.Failure).error.message)
        assertTrue(secondSecret.all { it == 0.toChar() })
    }
    @Test fun cancellationAndClosedOwnerNeverReachStorageAndStillClearPassword() = runBlocking {
        val service = BackupService(port(), Dispatchers.Unconfined) { false }
        val secret = "password".toCharArray()
        try { service.preview(DocumentRef("content://backup"), secret); fail("Closed owner must cancel") } catch (_: CancellationException) {}
        assertTrue(secret.all { it == 0.toChar() })
        val cancelledSecret = "password".toCharArray()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            service.preview(DocumentRef("content://backup"), cancelledSecret)
        }
        job.join()
        assertTrue(cancelledSecret.all { it == 0.toChar() })
    }
    @Test fun closedOwnerRejectsLateExportButCommittedRestoreReceiptSurvivesReplacement() = runBlocking {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val service = BackupService(object : BackupArchivePort by port() {
            override fun export(destination: DocumentRef, password: CharArray, includeAudio: Boolean, checkCancellation: () -> Unit): BackupSummary { active.set(false); return summary }
        }, Dispatchers.Unconfined, active::get)
        try { service.export(DocumentRef("content://backup"), "password".toCharArray(), false); fail("Late success must cancel") } catch (_: CancellationException) {}
        assertEquals(BackupPhase.EXPORTING, service.progress.value.phase)
        active.set(true)
        val restore = BackupService(object : BackupArchivePort by port() {
            override fun restore(previewToken: String, checkCancellation: () -> Unit): RestoreReceipt { active.set(false); return RestoreReceipt(true) }
        }, Dispatchers.Unconfined, active::get)
        val result = restore.restore("token")
        assertTrue(result is ApiResult.Success && result.value.applicationRestartRequested)
        assertEquals(BackupPhase.RESTORING, restore.progress.value.phase)
    }
    private fun port() = object : BackupArchivePort {
        override fun export(destination: DocumentRef, password: CharArray, includeAudio: Boolean, checkCancellation: () -> Unit) = summary
        override fun preview(source: DocumentRef, password: CharArray, checkCancellation: () -> Unit) = BackupPreview("token", summary)
        override fun restore(previewToken: String, checkCancellation: () -> Unit) = RestoreReceipt(true)
        override fun discard(previewToken: String) {}
    }
}
