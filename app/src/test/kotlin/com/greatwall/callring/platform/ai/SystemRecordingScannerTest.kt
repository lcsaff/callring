package com.greatwall.callring.platform.ai

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract.Document
import com.greatwall.callring.platform.recording.SystemRecordingScanner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SystemRecordingScannerTest {
    @Test fun largeDirectoryIsStreamedWithoutOpeningAudioAndClosesCursor() {
        val fixture = Fixture()
        val seen = mutableSetOf<String>()
        fixture.scanner.scan(fixture.folder, { false }) { item -> seen += item.sourceKey }
        assertEquals(2001, seen.size)
        assertTrue(fixture.last!!.isClosed)
        assertEquals(1, fixture.queries)
    }
    @Test fun cancellationClosesCursorAndStopsCallbacks() {
        val fixture = Fixture()
        var count = 0
        assertThrows(CancellationException::class.java) {
            fixture.scanner.scan(fixture.folder, { count >= 65 }) { count++ }
        }
        assertEquals(65, count); assertTrue(fixture.last!!.isClosed)
    }
    @Test fun permissionLossPreventsEnumeration() {
        val fixture = Fixture()
        fixture.granted = false
        assertThrows(SecurityException::class.java) { fixture.scanner.scan(fixture.folder, { false }) {} }
        assertEquals(0, fixture.queries)
    }
    @Test fun sourceIdentityDoesNotDependOnDisplayNameOrTreeGrant() {
        assertEquals(SystemRecordingScanner.sourceKey("provider", "root/audio"), SystemRecordingScanner.sourceKey("provider", "root/audio"))
        assertNotEquals(SystemRecordingScanner.sourceKey("provider", "root/audio"), SystemRecordingScanner.sourceKey("other", "root/audio"))
    }
    @Test fun cancellingOwnedInspectionUnblocksProviderQuery() {
        val fixture = Fixture()
        fixture.blockInspection = true
        val failure = AtomicReference<Throwable>()
        val thread = Thread {
            try { fixture.scanner.inspect(fixture.folder,com.greatwall.callring.contracts.common.DocumentRef("content://scan-fixture/tree/root/document/audio")) { false } }
            catch(t: Throwable) { failure.set(t) }
        }.apply { start() }
        try {
            assertTrue(fixture.entered.await(3,TimeUnit.SECONDS))
            fixture.scanner.cancelScan()
            thread.join(2000)
            assertFalse(thread.isAlive)
            assertTrue(failure.get() is CancellationException)
        } finally { fixture.release.countDown(); thread.join(3000) }
    }
    private class Fixture {
        val context = RuntimeEnvironment.getApplication()
        var last: Cursor? = null
        var queries = 0
        var granted = true
        var blockInspection = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val folder = "content://scan-fixture/tree/root"
        val scanner = SystemRecordingScanner(context.contentResolver) { granted }
        init {
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri) = "audio/wav"
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?, cancellationSignal: android.os.CancellationSignal?): Cursor {
                    if(blockInspection) {
                        cancellationSignal?.setOnCancelListener { release.countDown() }
                        entered.countDown()
                        check(release.await(5,TimeUnit.SECONDS))
                        cancellationSignal?.throwIfCanceled()
                    }
                    return query(uri,projection,selection,args,sort)
                }
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
                    queries++
                    return MatrixCursor(projection).apply {
                        repeat(2001) { index -> addRow(projection!!.map<String, Any> { column -> when(column) {
                            Document.COLUMN_DOCUMENT_ID -> "audio-$index"
                            Document.COLUMN_DISPLAY_NAME -> "12345_20261104_120000.wav"
                            Document.COLUMN_MIME_TYPE -> "audio/wav"
                            Document.COLUMN_LAST_MODIFIED -> 1L
                            Document.COLUMN_SIZE -> 128L
                            else -> error(column)
                        } }.toTypedArray()) }
                    }.also { last = it }
                }
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            }
            provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "scan-fixture" })
            ShadowContentResolver.registerProviderInternal("scan-fixture", provider)
        }
    }
}
