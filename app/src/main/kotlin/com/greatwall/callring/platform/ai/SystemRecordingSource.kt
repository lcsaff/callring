package com.greatwall.callring.platform.ai

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.greatwall.callring.application.api.RecordingFile

class SystemRecordingSource(private val resolver: ContentResolver) {
    fun authorize(raw: String) {
        val uri = Uri.parse(raw)
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun list(folder: String): List<RecordingFile> {
        val root = Uri.parse(folder)
        require(root.scheme == "content" && DocumentsContract.isTreeUri(root))
        val result = mutableListOf<RecordingFile>()
        var visited = 0
        fun walk(id: String, depth: Int) {
            if (depth > 4 || visited >= 500) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, id)
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                while (c.moveToNext() && visited++ < 500) {
                    val child = c.getString(0); val name = c.getString(1).orEmpty(); val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(child, depth + 1)
                    else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in setOf("m4a", "mp3", "wav", "amr", "ogg", "aac", "flac")) {
                        result += RecordingFile(DocumentsContract.buildDocumentUriUsingTree(root, child).toString(), name, c.getLong(3))
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(root), 0)
        return result.sortedByDescending { it.modifiedAt }
    }
}
