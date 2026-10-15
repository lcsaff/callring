package com.greatwall.callring.ui.app

import org.junit.Assert.*
import org.junit.Test

class UiDestinationTest {
    @Test fun plusNumberIsEncodedAsOneNavigationArgument() {
        assertEquals("report/%2B86100", UiDestination.Report("+86100").route)
        assertEquals("edit?number=%2B86100", UiDestination.EditCaller("+86100").route)
    }
    @Test fun missingExternalNumberOpensLookupInsteadOfBrokenReport() {
        assertEquals(UiDestination.Lookup, UiDestination.fromExternal("report", null))
        assertEquals(UiDestination.Home, UiDestination.fromExternal("unknown", "100"))
    }
    @Test fun settingsAndLookupExternalRoutesRemainAddressable() {
        assertEquals(UiDestination.Lookup, UiDestination.fromExternal("lookup", null))
        assertEquals(UiDestination.Setting(com.greatwall.callring.ui.feature.settings.SettingsPage.RUNTIME),
            UiDestination.fromExternal("setting", null, "RUNTIME"))
        assertEquals(UiDestination.Settings, UiDestination.fromExternal("setting", null, "unknown"))
    }
}
