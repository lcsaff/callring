package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.conversation.execution.json
import com.greatwall.callring.application.conversation.operation.ToolRegistry
import com.greatwall.callring.application.conversation.query.AiSnapshotService
import com.greatwall.callring.application.conversation.query.SnapshotPart
import com.greatwall.callring.application.conversation.query.json
import com.greatwall.callring.application.conversation.query.safeValue
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.platform.ConversationRequest
import com.greatwall.callring.data.recording.AndroidRecordingStore
import com.greatwall.callring.data.recording.RecordingCipher
import com.greatwall.callring.platform.recording.SystemRecordingSource

import com.greatwall.callring.application.recording.AiSettingsService
import android.content.*
import android.database.*
import android.net.Uri
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33],manifest = Config.NONE)
class RecordingFolderViewTest {
    @Test fun actualRootMetadataDistinguishesEmptyDirectoriesAndKeepsResourcesPrivate() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var granted = true; var broken = false; var unsafeName = false
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "vnd.android.document/directory"
            override fun query(uri: Uri,projection: Array<out String>?,selection: String?,args: Array<out String>?,sort: String?): Cursor? {
                if(broken) return null
                return MatrixCursor(projection!!).apply {
                    if(!uri.toString().endsWith("/children")) addRow(arrayOf(if(unsafeName) "/private/full/path" else if(uri.lastPathSegment == "first") "First recordings" else "Second recordings"))
                }
            }
            override fun insert(uri: Uri,values: ContentValues?): Uri? = null
            override fun update(uri: Uri,values: ContentValues?,selection: String?,args: Array<out String>?) = 0
            override fun delete(uri: Uri,selection: String?,args: Array<out String>?) = 0
        }
        provider.attachInfo(context,android.content.pm.ProviderInfo().apply { authority = "folder-fixture"; exported = true })
        ShadowContentResolver.registerProviderInternal("folder-fixture",provider)
        val source = SystemRecordingSource(context.contentResolver) { granted }
        val coordinator = BusinessRevisionCoordinator()
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val store = AndroidRecordingStore(context,"folder_view",cipher, recordingVersion = { coordinator.capture().values.getValue("recording") })
        val service = AiSettingsService(store,coordinator,{ true },{ _,_ -> },folderView = source::describe, operationActive = { true })
        fun <T> ApiResult<T>.value() = (this as ApiResult.Success).value
        assertEquals(AccessState.NOT_REQUIRED,service.getRecordingView().value().folderAccess)
        store.persistSettings(RecordingSettings(systemFolder = "content://folder-fixture/tree/first"))
        val first = service.getRecordingView().value()
        assertEquals("First recordings",first.folderDisplayName)
        assertEquals(AccessState.GRANTED,first.folderAccess)
        assertTrue(source.list(store.settings().systemFolder!!).isEmpty())
        store.persistSettings(store.settings().copy(systemFolder = "content://folder-fixture/tree/second"))
        val second = service.getRecordingView().value()
        assertEquals("Second recordings",second.folderDisplayName)
        assertNotEquals(first.copy(revision = 0),second.copy(revision = 0))
        val parts = (1..21).associate { "D%02d".format(it) to SnapshotPart(if(it == 13) safeValue(second) else AiJson.Arr(emptyList())) }
        val snapshot = AiSnapshotService(coordinator,listOf({ _ -> parts }),{ emptyList() }).captureComplete().value()
        val request = CompatibleConversationClient().encode(AiConnection(url = "https://example.com",model = "fixture",apiKey = "sentinel"),ConversationRequest(snapshot.json(),emptyList(),ToolRegistry().definitions,4096))
        assertFalse(String(request).contains("content://")); assertFalse(safeValue(second).encode().contains("content://"))
        granted = false
        assertEquals(AccessState.DENIED,service.getRecordingView().value().folderAccess)
        granted = true; broken = true
        assertEquals(AccessState.UNKNOWN,service.getRecordingView().value().folderAccess)
        assertNull(service.getRecordingView().value().folderDisplayName)
        broken = false; unsafeName = true
        val safe = service.getRecordingView().value()
        assertNull(safe.folderDisplayName); assertFalse(safeValue(safe).encode().contains("/private/"))
    }
}
