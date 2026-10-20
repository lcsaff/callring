package com.greatwall.callring.application.ports.store

import com.greatwall.callring.domain.BackendState
import com.greatwall.callring.domain.AuditRecord

/** Only complete export/capture paths receive this intentionally wide capability. */
interface FullSnapshotReader {
    fun <T> captureFull(block: (BackendState, List<AuditRecord>) -> T): T
}
