package com.greatwall.callring

import com.greatwall.callring.application.CoreNumberDeletionApi
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.application.requiredValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NumberDeletionRegressionTest {
    @Test fun backendWidgetWritesPublishPreferenceMembershipChanges() = runBlocking {
        val fixture = ConversationFixtures()
        val before = fixture.coordinator.capture().values.getValue("preferences")
        fixture.backend.widgets.saveBinding(1, "31234").requiredValue()
        assertTrue(fixture.coordinator.capture().values.getValue("preferences") > before)
    }

    @Test fun guardedDeletionMustRemoveWidgetToo() = runBlocking {
        val fixture = ConversationFixtures()
        fixture.backend.callers.saveProfile(SaveCallerProfile("31234", "name", "dept", null, false)).requiredValue()
        fixture.backend.widgets.saveBinding(1, "31234").requiredValue()
        val command = CommandContext("delete", fixture.coordinator.capture(), 1,
            expectedScopes = fixture.coordinator.expect(setOf("profile:31234:exists", "calls:31234"))) { true }
        fixture.backend.commandApi(command).numberDeletion.delete(DeleteNumberCommand("31234", 0, 0, 1)).requiredValue()
        assertFalse(fixture.state.profiles.containsKey("31234"))
        assertTrue(fixture.bindings.isEmpty())
    }

    @Test fun widgetOnlyRemnantsCanBeRemoved() = runBlocking {
        val fixture = ConversationFixtures()
        fixture.backend.widgets.saveBinding(1, "31234").requiredValue()
        fixture.backend.numberDeletion.delete(DeleteNumberCommand("31234", 0, 0, 1)).requiredValue()
        assertTrue(fixture.bindings.isEmpty())
    }

    @Test fun failedWidgetCleanupCanResumeAfterCoreWasDeleted() = runBlocking {
        val fixture = ConversationFixtures()
        fixture.backend.callers.saveProfile(SaveCallerProfile("31234", "name", "dept", null, false)).requiredValue()
        fixture.backend.widgets.saveBinding(1, "31234").requiredValue()
        var fail = true
        val widgets = object : WidgetBindingsApi by fixture.backend.widgets {
            override suspend fun removeBinding(widgetId: Int): ApiResult<Unit> =
                if (fail) ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "fixture"))
                else fixture.backend.widgets.removeBinding(widgetId)
        }
        val deletion = CoreNumberDeletionApi(fixture.backend.callers, widgets)
        assertTrue(deletion.delete(DeleteNumberCommand("31234", 0, 0, 1)) is ApiResult.Failure)
        assertFalse(fixture.state.profiles.containsKey("31234"))
        fail = false
        deletion.delete(DeleteNumberCommand("31234", 0, 0, 1)).requiredValue()
        assertTrue(fixture.bindings.isEmpty())
    }
}
