package com.greatwall.callring.application.ports.store

import com.greatwall.callring.application.ports.BackendStore
import com.greatwall.callring.application.ports.ScopedReadPort

/** Adapter capabilities, assembled once; application services receive only narrow projections. */
interface CoreStorageResource : AutoCloseable {
    val legacy: BackendStore
    val fullSnapshot: FullSnapshotReader
    val sessions: CoreUnitOfWork
    val scoped: ScopedReadPort
    fun stateLoadCount(): Long
    fun narrowAccessCounts(): Map<String, Long> = emptyMap()
}
