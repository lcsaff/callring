package com.greatwall.callring.ui.app

import com.greatwall.callring.ui.feature.settings.SettingsPage
import java.net.URLEncoder
import java.util.Locale

sealed class UiDestination(val route: String) {
    data object Home : UiDestination("home")
    data object Callers : UiDestination("callers")
    data object Settings : UiDestination("settings")
    data object Lookup : UiDestination("lookup")
    data class EditCaller(val number: String? = null) : UiDestination("edit?number=${number?.encoded().orEmpty()}")
    data class Report(val number: String) : UiDestination("report/${number.encoded()}")
    data class Setting(val page: SettingsPage) : UiDestination("settings/${page.name.lowercase(Locale.ROOT)}")

    companion object {
        fun fromExternal(target: String?, number: String?, page: String? = null): UiDestination = when (target) {
            "callers" -> Callers
            "settings" -> Settings
            "report" -> number?.takeIf { it.isNotBlank() }?.let(::Report) ?: Lookup
            "edit" -> EditCaller(number?.takeIf { it.isNotBlank() })
            "lookup" -> Lookup
            "setting" -> SettingsPage.entries.firstOrNull { it.name == page }?.let(::Setting) ?: Settings
            else -> Home
        }
    }
}

private fun String.encoded(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
