package com.greatwall.callring.bootstrap

import com.greatwall.callring.contracts.settings.AppearanceSettings
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * UI owns the default preset identifier. Every fixture can select its own database, preferences and
 * files.
 */
data class BackendConfiguration(
    val defaultAppearance: AppearanceSettings,
    val databaseName: String = "callring.db",
    val ringPreferencesName: String = "ring_settings",
    val widgetPreferencesName: String = "caller_widgets",
    val appearancePreferencesName: String = "callring_appearance",
    val audioDirectory: File? = null,
    val clock: Clock = Clock.systemUTC(),
    val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    val numberMatcher: com.greatwall.callring.domain.calling.NumberMatcher = com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher,
    val sqlAccessProbe: com.greatwall.callring.data.database.SqlAccessProbe? = null,
) {
    init {
        require(
            databaseName.matches(Regex("[A-Za-z0-9_.-]+")) &&
                databaseName != "." &&
                databaseName != ".."
        )
        val names = listOf(ringPreferencesName, widgetPreferencesName, appearancePreferencesName)
        require(
            names.all { it.isNotBlank() && '/' !in it && '\\' !in it } &&
                names.toSet().size == names.size
        )
        require(defaultAppearance.presetId.isNotBlank())
    }
}
