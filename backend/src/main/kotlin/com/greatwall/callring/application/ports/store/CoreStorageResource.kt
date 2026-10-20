package com.greatwall.callring.application.ports.store

import com.greatwall.callring.application.ports.ScopedReadPort

/** Adapter capabilities, assembled once; application services receive only narrow projections. */
interface CoreStorageResource : AutoCloseable {
    val fullSnapshot: FullSnapshotReader
    val sessions: CoreUnitOfWork
    val scoped: ScopedReadPort
    fun narrowAccessCounts(): Map<String, Long>
}
