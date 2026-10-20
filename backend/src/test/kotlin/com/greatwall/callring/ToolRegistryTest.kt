package com.greatwall.callring

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.application.conversation.*
import org.junit.Assert.*
import org.junit.Test

class ToolRegistryTest {
    @Test fun invalidEnumReturnsSafeKnownFieldPathForRepair() {
        val failure=runCatching{ToolRegistry().validate(ConversationToolCall("one","settings_saveAppearance",aiObject("colorMode" to aiString("secret-invalid-value"))))}.exceptionOrNull()
        assertTrue(failure is com.greatwall.callring.application.ports.BackendFailure)
        val error=(failure as com.greatwall.callring.application.ports.BackendFailure).error
        assertTrue(error.fieldErrors.containsKey("$.colorMode"))
        assertFalse(error.toString().contains("secret-invalid-value"))
    }
    @Test fun all72OperationsHaveUniqueStrictSchemasAndRejectUnknownArguments() {
        val registry = ToolRegistry()
        assertEquals((1..72).map { "O%02d".format(it) }.toSet(), registry.definitions.map { it.id }.toSet())
        assertEquals(72, registry.definitions.map { it.name }.toSet().size)
        val check = registry.definitions.first()
        registry.validate(ConversationToolCall("one", check.name, aiObject("raw" to aiString("31234"))))
        assertTrue(runCatching { registry.validate(ConversationToolCall("two", check.name, aiObject("uri" to aiString("content://forged")))) }.isFailure)
        assertTrue(runCatching { registry.validate(ConversationToolCall("three", "execute_code", aiObject())) }.isFailure)
    }
}
