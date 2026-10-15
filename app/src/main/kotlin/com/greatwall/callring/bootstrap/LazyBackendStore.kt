package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.BackendState
import java.io.Closeable

/**
 * Constructor is side-effect free; first application IO operation opens SQLite on its IO
 * dispatcher.
 */
internal class LazyBackendStore(private val factory: () -> BackendStore) : BackendStore, Closeable {
    private var instance: BackendStore? = null
    private var closed = false

    @Synchronized
    private fun get(): BackendStore {
        if (closed) throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "backend.closed"))
        return instance ?: factory().also { instance = it }
    }

    @Synchronized override fun <T> read(block: (BackendState) -> T): T = get().read(block)

    @Synchronized
    override fun <T> transaction(block: (BackendState) -> T): T = get().transaction(block)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        (instance as? AutoCloseable)?.close()
        instance = null
    }
}
