package com.greatwall.callring.application.widget

import com.greatwall.callring.application.coordination.requireOperationActive

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.application.coordination.UseCaseRunner
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.domain.model.NumberIdentity
import kotlinx.coroutines.CoroutineDispatcher

class WidgetBindingsService(
    private val bindings: WidgetBindingStore,
    private val installed: () -> WidgetInstallState,
    private val coordinator: BusinessRevisionCoordinator,
    private val dispatcher: CoroutineDispatcher,
    private val command: CommandContext? = null,
    private val operationActive: () -> Boolean,
) : WidgetBindingsApi {
    private val runner = UseCaseRunner(dispatcher)
    fun bound(command: CommandContext) = WidgetBindingsService(bindings, installed, coordinator, dispatcher, command, operationActive = operationActive)
    private fun widgetId(id: Int) {
        if (id <= 0) throw BackendFailure(ApiError(ErrorCode.VALIDATION, "widget.id.invalid", mapOf("widgetId" to "widget.id.invalid")))
    }
    override suspend fun listBindings() = runner.io { requireOperationActive(operationActive);
        val live = installed()
        WidgetBindingsListing(java.util.Collections.unmodifiableList(ArrayList(bindings.listBindings())),
            live.copy(ids = java.util.Collections.unmodifiableList(ArrayList(live.ids))))
    }
    override suspend fun getBinding(widgetId: Int) = runner.io { requireOperationActive(operationActive); widgetId(widgetId); bindings.getBinding(widgetId) }
    override suspend fun saveBinding(widgetId: Int, number: String) = runner.write { requireOperationActive(operationActive);
        widgetId(widgetId)
        val identity = NumberIdentity.parse(number)
        if (!identity.validProfileNumber) throw BackendFailure(ApiError(ErrorCode.VALIDATION, "number.invalid", mapOf("number" to "number.invalid")))
        val binding = WidgetBinding(widgetId, identity.normalized)
        coordinator.guard(command) {
            requireOperationActive(operationActive)
            if (bindings.getBinding(widgetId) != binding) {
                bindings.saveBinding(binding)
                publish(widgetId)
            }
            coordinator.record(command)
        }
        binding
    }
    override suspend fun removeBinding(widgetId: Int) = runner.write { requireOperationActive(operationActive);
        widgetId(widgetId)
        coordinator.guard(command) {
            requireOperationActive(operationActive)
            if (bindings.getBinding(widgetId) != null) {
                bindings.removeBinding(widgetId)
                publish(widgetId)
            }
            coordinator.record(command)
        }
    }
    suspend fun bindingsForNumber(number: String) = runner.io { requireOperationActive(operationActive); bindings.bindingsForNumber(number) }
    suspend fun removeStoredBinding(expected: WidgetBinding) = runner.write { requireOperationActive(operationActive);
        coordinator.guard(command) {
            requireOperationActive(operationActive)
            if (bindings.getBinding(expected.widgetId) != expected)
                throw BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed", retryable = true))
            bindings.removeBinding(expected.widgetId)
            publish(expected.widgetId)
            coordinator.record(command)
        }
    }
    private fun publish(id: Int) = coordinator.publishResource("preferences", setOf("widgets", "widget:$id"),
        ChangeImpact(mapOf(DataDomain.WIDGET_BINDINGS to setOf(id.toString()))))
}
