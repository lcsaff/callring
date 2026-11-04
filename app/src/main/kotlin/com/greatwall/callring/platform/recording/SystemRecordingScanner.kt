package com.greatwall.callring.platform.recording

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.greatwall.callring.application.ports.recording.ScannedSystemRecording
import com.greatwall.callring.application.ports.recording.SystemRecordingScanPort
import com.greatwall.callring.contracts.recording.RecordingFile
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** SAF has no portable offset cursor. Restart streams again and the durable ledger deduplicates. */
class SystemRecordingScanner(private val resolver: ContentResolver,
    private val readGranted: (Uri) -> Boolean = { root -> resolver.persistedUriPermissions.any { it.uri == root && it.isReadPermission } },
) : SystemRecordingScanPort {
    private val signals = ConcurrentHashMap.newKeySet<CancellationSignal>()
    override fun cancelScan() { signals.forEach { it.cancel() } }
    override fun inspect(folder: String, document: com.greatwall.callring.contracts.common.DocumentRef, cancelled: () -> Boolean): RecordingFile? {
        fun active() { if(cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("recording.cancelled") }
        active()
        val signal = CancellationSignal()
        signals += signal
        try {
            active()
            return SystemRecordingSource(resolver,readGranted).inspect(folder,document,signal).also { active() }
        } catch(_: android.os.OperationCanceledException) { throw CancellationException("recording.cancelled") }
        finally { signals -= signal }
    }

    override fun scan(folder: String, cancelled: () -> Boolean, visit: (ScannedSystemRecording) -> Unit) {
        val root = Uri.parse(folder)
        require(root.scheme == "content" && DocumentsContract.isTreeUri(root))
        fun active() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("recording.cancelled")
            if (!readGranted(root)) throw SecurityException("recording.permission_required")
        }
        val ancestors = hashSetOf<String>()
        fun walk(id: String, depth: Int) {
            active()
            if (depth > 32 || !ancestors.add(id)) throw IOException("recording.directory_incomplete")
            val signal = CancellationSignal()
            signals += signal
            try {
                active()
                resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(root, id), COLUMNS, null, null, null, signal)
                    ?.use { cursor ->
                        while (true) {
                            active()
                            if (!cursor.moveToNext()) break
                            if (cursor.isNull(0) || cursor.isNull(1) || cursor.isNull(2)) throw IOException("recording.directory_incomplete")
                            val child = cursor.getString(0)
                            if (cursor.getString(2) == Document.MIME_TYPE_DIR) walk(child, depth + 1)
                            else {
                                active()
                                visit(ScannedSystemRecording(sourceKey(requireNotNull(root.authority), child),
                                    RecordingFile(DocumentsContract.buildDocumentUriUsingTree(root, child).toString(), cursor.getString(1),
                                        if (cursor.isNull(3)) 0 else cursor.getLong(3), if (cursor.isNull(4)) null else cursor.getLong(4), !cursor.isNull(3))))
                            }
                        }
                    } ?: throw IOException("recording.directory_unavailable")
            } catch (_: android.os.OperationCanceledException) { throw CancellationException("recording.cancelled") }
            finally { signals -= signal; ancestors -= id }
        }
        walk(DocumentsContract.getTreeDocumentId(root), 0)
        active()
    }

    companion object {
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        fun sourceKey(authority: String, documentId: String): String = MessageDigest.getInstance("SHA-256")
            .digest((authority.length.toString() + ":" + authority + documentId).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
