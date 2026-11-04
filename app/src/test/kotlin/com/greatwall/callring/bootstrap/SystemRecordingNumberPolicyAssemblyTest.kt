package com.greatwall.callring.bootstrap

import android.app.Notification
import android.app.PendingIntent
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract.Document
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.recording.model.RecordingSettings
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.calling.*
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SystemRecordingNumberPolicyAssemblyTest {
    @Test fun discoveryUsesPersistedRulesAndTheConfiguredProductionMatcher() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val seen = mutableListOf<Pair<String,NumberRule>>()
        val matcher = NumberMatcher { number, rule -> seen += number to rule; false }
        val configuration = BackendConfiguration(AppearanceSettings(ThemeSelection.PRESET,"default",null,ColorMode.FOLLOW_SYSTEM),
            databaseName="number-policy.db",numberMatcher=matcher)
        val notifications = object : UiNotificationEntryPoints {
            override fun home(requestCode: Int) = PendingIntent.getActivity(context,requestCode,Intent("fixture"),PendingIntent.FLAG_IMMUTABLE)
            override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
        }
        val timestamp = System.currentTimeMillis() - 60_000
        val name = "31234_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp)) + ".wav"
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "audio/wav"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
                MatrixCursor(projection).apply { addRow(projection!!.map<String,Any> { when(it) {
                    Document.COLUMN_DOCUMENT_ID -> "audio"
                    Document.COLUMN_DISPLAY_NAME -> name
                    Document.COLUMN_MIME_TYPE -> "audio/wav"
                    Document.COLUMN_LAST_MODIFIED -> timestamp
                    Document.COLUMN_SIZE -> 128L
                    else -> error(it)
                } }.toTypedArray()) }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context,android.content.pm.ProviderInfo().apply { authority="number-policy"; exported=true; grantUriPermissions=true })
        ShadowContentResolver.registerProviderInternal("number-policy",provider)
        context.contentResolver.takePersistableUriPermission(Uri.parse("content://number-policy/tree/root"),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        BackendGraph(BackupGraphOwner { _, _ -> error("unused restore") },context,configuration,notifications).use { graph ->
            // Substitute only encryption-backed storage; retain production graph/discovery wiring.
            val cipher = object : com.greatwall.callring.data.recording.RecordingCipher {
                override fun encrypt(value: String) = value
                override fun decrypt(value: String) = value
            }
            val store = com.greatwall.callring.data.recording.AndroidRecordingStore(context,"policy-recording",cipher) { 0L }
            RecordingAssembly::class.java.getDeclaredField("storeDelegate").apply { isAccessible=true }.set(graph.recordings,lazy { store })
            val base = graph.backend.settings.getNumberRule().requiredValue()
            val empty = graph.backend.settings.saveNumberRule(SaveNumberRule(base,emptyList())).requiredValue()
            graph.recordings.store.persistSettings(RecordingSettings(recordingEnabled=true,systemFolder="content://number-policy/tree/root"))
            graph.recordings.service.discoverSystemRecordings()
            assertTrue(seen.isEmpty())
            graph.backend.settings.saveNumberRule(SaveNumberRule(empty,listOf(NumberRuleEntry("00",5),NumberRuleEntry("31",5)))).requiredValue()
            graph.recordings.service.discoverSystemRecordings()
            assertEquals(listOf("31234" to NumberRule("00",5),"31234" to NumberRule("31",5)),seen)
            assertTrue(graph.recordings.store.systemSourcePage(null,64).isEmpty())
            assertTrue(graph.recordings.service.queryArchives().requiredValue().items.isEmpty())
        }
    }
}
