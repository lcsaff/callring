package com.greatwall.callring.application.ports.store

import com.greatwall.callring.contracts.settings.AppearanceSettings

interface AppearanceReader { fun getAppearance(): AppearanceSettings }
/** Preference adapter persists and rolls back; the business service owns publication. */
interface AppearanceStore : AppearanceReader { fun saveAppearance(settings: AppearanceSettings) }
