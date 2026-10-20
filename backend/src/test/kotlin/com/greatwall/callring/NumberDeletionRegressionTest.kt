package com.greatwall.callring

import com.greatwall.callring.application.callers.NumberDeletionService
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
        assertFalse(fixture.core.people.containsKey("31234"))
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
        val deletion = NumberDeletionService(fixture.backend.callers::numberDataImpact,
            { number, count, cmd -> fixture.backend.commandApi(cmd).callers.deleteNumberCore(number, count) }, EmptyNumberRecordings,
            { number -> ApiResult.Success(fixture.bindings.values.filter { it.number == number }) },
            { binding, cmd -> if (fail) ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "fixture")) else fixture.backend.commandApi(cmd).widgets.removeBinding(binding.widgetId) }, fixture.coordinator, active = { true })
        assertTrue(deletion.delete(DeleteNumberCommand("31234", 0, 0, 1)) is ApiResult.Failure)
        assertFalse(fixture.core.people.containsKey("31234"))
        fail = false
        deletion.delete(DeleteNumberCommand("31234", 0, 0, 1)).requiredValue()
        assertTrue(fixture.bindings.isEmpty())
    }
}
