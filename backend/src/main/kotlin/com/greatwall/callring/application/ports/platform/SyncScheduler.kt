package com.greatwall.callring.application.ports.platform

import com.greatwall.callring.contracts.runtime.SyncRequestReceipt

interface SyncScheduler {
    fun requestSync(atEpochMillis: Long): SyncRequestReceipt
}
