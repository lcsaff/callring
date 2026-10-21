package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.application.ports.query.SourceRow
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.widget.*

class WidgetQueryProjection(
    private val stored: () -> Sequence<WidgetBinding>,
    private val installed: () -> WidgetInstallState,
) {
    fun rows(spec: QuerySpec): Sequence<SourceRow> {
        require(spec.projection in setOf("default", "bindings", "installed"))
        val bound = if (spec.projection == "installed") emptySequence() else stored().map { binding ->
            SourceRow("binding:${binding.widgetId}", aiObject("kind" to aiString("stored_binding"),
                "widgetId" to aiNumber(binding.widgetId.toLong()), "number" to aiString(binding.number)))
        }
        val installation = if (spec.projection == "bindings") emptySequence() else installed().let { state ->
            state.ids.asSequence().map { id -> SourceRow("installed:$id", aiObject("kind" to aiString("installed"),
                "widgetId" to aiNumber(id.toLong()), "access" to aiString(state.access.name))) } +
                sequenceOf(SourceRow("installation-observation", aiObject("kind" to aiString("installation_observation"),
                    "access" to aiString(state.access.name))))
        }
        return bound + installation
    }
}
