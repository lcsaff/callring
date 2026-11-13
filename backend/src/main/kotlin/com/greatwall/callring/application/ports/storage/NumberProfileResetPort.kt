package com.greatwall.callring.application.ports.storage

import com.greatwall.callring.contracts.storage.*

/** App-owned staged replacement; never grants write access to device call/recording sources. */
interface NumberProfileResetPort {
    fun preview(checkCancellation: () -> Unit): NumberProfileResetPreview
    fun confirm(token: String, keepRecordings: Boolean, checkCancellation: () -> Unit): NumberProfileResetReceipt
    fun discard(token: String)
}
