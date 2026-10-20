package com.greatwall.callring.platform.ai

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.application.conversation.DirectoryEnumerator
import com.greatwall.callring.application.conversation.DirectoryNode

class SystemRecordingSource(private val resolver: ContentResolver, private val readGranted: (Uri) -> Boolean = { uri -> resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } }) {
    fun scopedReads(folder:()->String?,version:()->Long,resources:com.greatwall.callring.application.conversation.ForegroundResourceRegistry):com.greatwall.callring.application.ports.ScopedReadPort =
        DirectoryScopedReadSource(folder,version,resources,{readGranted(Uri.parse(it))},{root,id->
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(root),id),arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_SIZE),null,null,null)
                ?:throw java.io.IOException("recording.directory_unavailable")
        },::inspect)
    fun inspect(folder:String,document:DocumentRef):RecordingFile? {
        val root=Uri.parse(folder);val uri=Uri.parse(document.uri)
        require(uri.authority==root.authority&&DocumentsContract.getTreeDocumentId(uri)==DocumentsContract.getTreeDocumentId(root))
        if(!readGranted(root))throw SecurityException("recording.permission_required")
        return resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_SIZE),null,null,null)?.use{c->if(!c.moveToFirst())null else RecordingFile(document.uri,c.getString(0),if(c.isNull(1))0 else c.getLong(1),if(c.isNull(2))null else c.getLong(2),!c.isNull(1))}
    }
    fun describe(folder: String?): RecordingFolderView {
        if(folder == null) return RecordingFolderView(null,AccessState.NOT_REQUIRED)
        return try {
            val root = Uri.parse(folder)
            if(root.scheme != "content" || !DocumentsContract.isTreeUri(root)) return RecordingFolderView(null,AccessState.UNKNOWN)
            if(!readGranted(root)) return RecordingFolderView(null,AccessState.DENIED)
            val document = DocumentsContract.buildDocumentUriUsingTree(root,DocumentsContract.getTreeDocumentId(root))
            val cursor = resolver.query(document,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)
                ?: return RecordingFolderView(null,AccessState.UNKNOWN)
            cursor.use { c ->
                if(!c.moveToFirst() || c.isNull(0)) RecordingFolderView(null,AccessState.UNKNOWN)
                else {
                    val name = c.getString(0)?.takeIf { it.isNotBlank() && it.length <= 512 && !it.contains('/') && !it.contains('\\') && !it.contains("://") }
                    RecordingFolderView(name,AccessState.GRANTED)
                }
            }
        } catch(_: SecurityException) { RecordingFolderView(null,AccessState.DENIED) }
        catch(_: Exception) { RecordingFolderView(null,AccessState.UNKNOWN) }
    }
    fun authorize(raw: String) {
        val uri = Uri.parse(raw)
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        check(resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) { "recording.permission_required" }
    }
    fun list(folder: String): List<RecordingFile> {
        val root = Uri.parse(folder)
        require(root.scheme == "content" && DocumentsContract.isTreeUri(root))
        val enumerator = DirectoryEnumerator { id ->
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, id)
            val cursor = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE), null, null, null)
                ?: throw java.io.IOException("recording.directory_unavailable")
            cursor.use { c -> buildList {
                while (c.moveToNext()) {
                    val child = requireNotNull(c.getString(0)); val name = requireNotNull(c.getString(1)); val mime = requireNotNull(c.getString(2))
                    add(DirectoryNode(child, name, mime, if (c.isNull(3)) null else c.getLong(3), if (c.isNull(4)) null else c.getLong(4), mime == DocumentsContract.Document.MIME_TYPE_DIR))
                    if (size > 500) throw java.io.IOException("recording.enumeration_limit")
                }
            } }
        }
        return enumerator.capture(DocumentsContract.getTreeDocumentId(root)).map {
            RecordingFile(DocumentsContract.buildDocumentUriUsingTree(root, it.id).toString(), it.name, it.modified ?: 0, it.size, it.modified != null)
        }.sortedByDescending { it.modifiedAt }
    }
}
