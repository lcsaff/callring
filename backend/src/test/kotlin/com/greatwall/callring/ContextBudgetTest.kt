package com.greatwall.callring

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import org.junit.Assert.*
import org.junit.Test

class ContextBudgetTest {
    @Test fun countsCompleteUtf8HistorySchemasAndOutputReserve() {
        val empty = ContextBudget.estimate("{}".toByteArray(), ConversationBudget())
        assertEquals(2L + 1024 + 4096 + 1024, empty)
        assertTrue(runCatching { ContextBudget.check(ByteArray(30000), ConversationBudget(32768, 4096)) }.isFailure)
        assertTrue(ContextBudget.check(ByteArray(30000), ConversationBudget()) <= 49152)
        assertTrue(runCatching { ContextBudget.check(ByteArray(8 * 1024 * 1024 + 1), ConversationBudget(1048576, 4096)) }.isFailure)
    }
    @Test fun replacesSecretsInDataLeavesAndPreservesStructureAndTail() {
        val redactor = SecretRedactor(listOf("private-key", "other-secret"))
        val data = aiObject("content" to aiString("private-key / private%2Dkey / other-secret / TAIL"))
        val result = redactor.redact(data).encode()
        assertFalse(result.contains("private-key"))
        assertFalse(result.contains("other-secret"))
        assertTrue(result.contains("TAIL"))
        assertTrue(result.startsWith("{\"content\":"))
        assertTrue(redactor.count > 0)
        assertEquals("", SecretRedactor(listOf("")).text(""))
    }
}
