package com.greatwall.callring.application.ports.platform

import com.greatwall.callring.contracts.common.DocumentRef
import com.greatwall.callring.contracts.settings.RingtoneSettings

/**
 * Import/restore concurrency must use invocation generations to invalidate stale imports. Import
 * must validate <=20 MiB, playable duration, then atomically replace; failures preserve the
 * previous selection. No private paths in settings.
 */
interface PreparedRingtoneImport : AutoCloseable {
    /** Final generation/cancellation validation and atomic file/metadata replacement. */
    fun commit(checkCancellation: () -> Unit): RingtoneSettings
    override fun close()
}

interface AudioController {
    fun getSettings(): RingtoneSettings
    fun setEnabled(enabled: Boolean): RingtoneSettings
    fun prepareAudio(source: DocumentRef, checkCancellation: () -> Unit): PreparedRingtoneImport
    fun restoreBuiltIn(): RingtoneSettings
    fun startPreview()
    fun stopPlayback()
}
