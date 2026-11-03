package com.greatwall.callring.application.ports.backup

import com.greatwall.callring.contracts.backup.*
import com.greatwall.callring.contracts.common.DocumentRef

/** The adapter owns bounded streams and private staging; no file paths cross this port. */
interface BackupArchivePort {
    fun export(destination: DocumentRef, password: CharArray, includeAudio: Boolean, checkCancellation: () -> Unit): BackupSummary
    fun preview(source: DocumentRef, password: CharArray, checkCancellation: () -> Unit): BackupPreview
    fun restore(previewToken: String, checkCancellation: () -> Unit): RestoreReceipt
    fun discard(previewToken: String)
}
