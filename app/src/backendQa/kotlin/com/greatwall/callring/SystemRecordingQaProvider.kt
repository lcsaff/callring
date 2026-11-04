package com.greatwall.callring

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic backendQa-only SAF tree. No user directory, microphone, or network is accessed. */
class SystemRecordingQaProvider : DocumentsProvider() {
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        row(this, mapOf(Root.COLUMN_ROOT_ID to "qa", Root.COLUMN_DOCUMENT_ID to "root",
            Root.COLUMN_TITLE to "Synthetic recordings", Root.COLUMN_FLAGS to 0, Root.COLUMN_MIME_TYPES to "audio/wav"))
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: COLUMNS).apply {
        if (documentId != "root" && documentId != "valid" && !documentId.startsWith("skip-")) throw FileNotFoundException(documentId)
        row(this, values(documentId))
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        check(parentDocumentId == "root")
        childQueries.incrementAndGet()
        return object : MatrixCursor(projection ?: COLUMNS) {
            override fun close() { val alreadyClosed = isClosed; super.close(); if (!alreadyClosed) closedCursors.incrementAndGet() }
        }.apply {
            row(this, values("valid"))
            repeat(2000) { row(this, values("skip-$it")) }
            rowsEmitted.addAndGet(2001)
        }
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        parentDocumentId == "root" && (documentId == "valid" || documentId.startsWith("skip-"))
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        check(mode == "r")
        opens.incrementAndGet()
        if(documentId != "valid") { skippedOpens.incrementAndGet(); throw FileNotFoundException(documentId) }
        return ParcelFileDescriptor.open(requireNotNull(audio), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    private fun values(id: String): Map<String,Any?> = mapOf(
        Document.COLUMN_DOCUMENT_ID to id,
        Document.COLUMN_DISPLAY_NAME to if(id == "root") "Synthetic recordings" else if(id == "valid") recordingName else "unparseable-$id.wav",
        Document.COLUMN_MIME_TYPE to if(id == "root") Document.MIME_TYPE_DIR else "audio/wav",
        Document.COLUMN_FLAGS to 0,
        Document.COLUMN_LAST_MODIFIED to modifiedAt,
        Document.COLUMN_SIZE to if(id == "valid") requireNotNull(audio).length() else 128L,
    )
    private fun row(cursor: MatrixCursor, values: Map<String,Any?>) = cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    companion object {
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_LAST_MODIFIED,Document.COLUMN_SIZE)
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_MIME_TYPES)
        @Volatile var audio: File? = null
        @Volatile var recordingName = ""
        @Volatile var modifiedAt = 0L
        val childQueries = AtomicInteger()
        val closedCursors = AtomicInteger()
        val rowsEmitted = AtomicInteger()
        val opens = AtomicInteger()
        val skippedOpens = AtomicInteger()
        fun reset() {
            audio = null; recordingName = ""; modifiedAt = 0
            childQueries.set(0); closedCursors.set(0); rowsEmitted.set(0); opens.set(0); skippedOpens.set(0)
        }
    }
}
