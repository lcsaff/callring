package com.greatwall.callring.application.ports.platform

import com.greatwall.callring.contracts.widget.WidgetInstallState
import com.greatwall.callring.contracts.runtime.RuntimeStatus
import com.greatwall.callring.contracts.runtime.DiagnosticEntry

interface RuntimeProbe {
    fun getInstalledWidgets(): WidgetInstallState
    fun getStatus(): RuntimeStatus

    fun getDiagnostics(): List<DiagnosticEntry>
}
