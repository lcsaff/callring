package com.greatwall.callring.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CallLog
import com.greatwall.callring.platform.calling.AndroidCallLogSource
import com.greatwall.callring.platform.recording.SystemRecordingScanner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AndroidCallLogObservationTest {
    @Test fun outgoingNumbersUseTheSameNormalizedIdentityAndCloseCursor() {
        val f = Fixture()
        f.number = "+86 (138) 0013-8000"
        assertTrue(f.source.hasCloserOutgoingCall("8613800138000", 20_000, 10_000, CancellationSignal()))
        assertTrue(f.last!!.isClosed)
        assertEquals(listOf("10000", "30000"), f.args!!.toList())
        assertTrue(f.selection!!.contains("${CallLog.Calls.TYPE}=2"))
        f.number = "different"
        assertFalse(f.source.hasCloserOutgoingCall("8613800138000", 20_000, 10_000, CancellationSignal()))
        assertTrue(f.last!!.isClosed)
    }
    @Test fun unavailableOrDeniedObservationIsNeverTreatedAsNoOutgoingCall() {
        val f = Fixture()
        f.unavailable = true
        assertThrows(IOException::class.java) { f.source.hasCloserOutgoingCall("31234",20_000,10_000,CancellationSignal()) }
        f.unavailable = false; f.denied = true
        assertThrows(SecurityException::class.java) { f.source.hasCloserOutgoingCall("31234",20_000,10_000,CancellationSignal()) }
    }
    @Test fun scannerCancellationAlsoUnblocksOwnedCallLogObservation() {
        val f = Fixture()
        f.blocked = true
        val scanner = SystemRecordingScanner(RuntimeEnvironment.getApplication().contentResolver) { true }
        val failure = AtomicReference<Throwable>()
        val thread = Thread {
            try { scanner.withQueryCancellation({ false }) { f.source.hasCloserOutgoingCall("31234",20_000,10_000,it) } }
            catch (error: Throwable) { failure.set(error) }
        }.apply { start() }
        try {
            assertTrue(f.entered.await(3,TimeUnit.SECONDS))
            scanner.cancelScan(); thread.join(2_000)
            assertFalse(thread.isAlive)
            assertTrue(failure.get() is CancellationException)
        } finally { f.release.countDown(); thread.join(3_000) }
    }
    @Test fun sourceIncludesOutgoingFactsWithExplicitDirection() {
        val f = Fixture()
        f.historyRow = arrayOf("out-1", "31234", 1000L, 8L, 2, null, null)
        val facts = f.source.read(0, 2000)
        assertEquals(1, facts.size)
        assertEquals(com.greatwall.callring.domain.model.CallDirection.OUTGOING, facts.single().direction)
        assertTrue(f.selection!!.contains("IN (1,2,3,5,6,7)"))
    }
    private class Fixture {
        var number = "31234"
        var historyRow: Array<Any?>? = null
        var unavailable = false
        var denied = false
        var blocked = false
        var last: Cursor? = null
        var args: Array<out String>? = null
        var selection: String? = null
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = AndroidCallLogSource(RuntimeEnvironment.getApplication().contentResolver)
        init {
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri) = "vnd.android.cursor.dir/calls"
                override fun query(uri: Uri, projection: Array<out String>?, selected: String?, values: Array<out String>?, sort: String?, signal: CancellationSignal?): Cursor? {
                    if(blocked) {
                        signal?.setOnCancelListener { release.countDown() }
                        entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); signal?.throwIfCanceled()
                    }
                    return query(uri,projection,selected,values,sort)
                }
                override fun query(uri: Uri, projection: Array<out String>?, selected: String?, values: Array<out String>?, sort: String?): Cursor? {
                    if(denied) throw SecurityException("fixture")
                    if(unavailable) return null
                    selection = selected; args = values
                    return MatrixCursor(projection).apply { addRow(historyRow ?: arrayOf(number)) }.also { last = it }
                }
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            }
            provider.attachInfo(RuntimeEnvironment.getApplication(), android.content.pm.ProviderInfo().apply { authority = "call_log" })
            ShadowContentResolver.registerProviderInternal("call_log",provider)
        }
    }
}
