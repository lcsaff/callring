package com.greatwall.callring

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import org.junit.Assert.*
import org.junit.Test

class ToolRegistryTest {
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
