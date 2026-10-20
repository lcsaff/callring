package com.greatwall.callring

import com.greatwall.callring.application.ports.BackendStore
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.domain.BackendState

/** Test-only adaptation of deterministic memory fixtures. Production has no legacy fallback. */
internal class MemoryQuietTransactions(private val store: BackendStore) : QuietTransactions {
    private fun reader(state: BackendState) = object : QuietWriter {
        override fun quiet() = QuietConfiguration(state.quiet, state.lastQuietMode)
        override fun saveQuiet(value: QuietConfiguration): Boolean {
            val changed = quiet() != value
            state.quiet = value.settings; state.lastQuietMode = value.lastMode
            return changed
        }
    }
    override fun <T> readQuiet(block: (QuietReader) -> T): T = store.read { block(reader(it)) }
    override fun <T> writeQuiet(block: (QuietWriter) -> T): T = store.transaction { block(reader(it)) }
}
