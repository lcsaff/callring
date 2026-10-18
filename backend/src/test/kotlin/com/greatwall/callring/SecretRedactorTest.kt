package com.greatwall.callring

import com.greatwall.callring.application.conversation.SecretRedactor
import org.junit.Assert.*
import org.junit.Test

class SecretRedactorTest {
    @Test fun supplementaryUnicodeAndMixedEncodingsAreRemoved() {
        val key = "key\uD83D\uDE00-x"
        val redactor = SecretRedactor(listOf(key))
        assertEquals("[redacted] [redacted] [redacted] [redacted]", redactor.text(
            "$key key%F0%9F%98%80-x key\\uD83D\\uDE00-x %6Bey%f0%9f%98%80%2Dx"))
        assertEquals(4, redactor.count)
    }
}
