package com.greatwall.callring.application

internal fun BackendContext.widgetId(id: Int) {
    requireField(id > 0, "widgetId", "widget.id.invalid")
}
