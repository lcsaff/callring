package com.greatwall.callring

import com.greatwall.callring.application.conversation.SecretRedactor
import org.junit.Assert.*
import org.junit.Test

class SecretRedactorTest {
    @Test fun standardJsonShortEscapesAndMixedRepresentationsAreRedactedEvenWhenMalformed(){
        val key="Synthetic-\"\\\t\b\u000c\n\r/😀-end"
        val short="Synthetic-\\\"\\\\\\t\\b\\f\\n\\r\\/\\ud83d\\ude00-end"
        val mixed=key.codePoints().toArray().mapIndexed{i,cp->val chars=String(Character.toChars(cp));when(i%3){0->chars;1->chars.toByteArray(Charsets.UTF_8).joinToString(""){"%%%02X".format(it.toInt() and 255)};else->chars.map{"\\u%04x".format(it.code)}.joinToString("")}}.joinToString("")
        val redactor=SecretRedactor(listOf(key))
        assertEquals("[redacted]",redactor.text(short));assertEquals("[redacted]",redactor.text(mixed))
        assertEquals("{\"value\":\"[redacted]",redactor.text("{\"value\":\"$short"))
        assertEquals("[redacted]",redactor.text(redactor.text(short)))
    }
    @Test fun supplementaryUnicodeAndMixedEncodingsAreRemoved() {
        val key = "key\uD83D\uDE00-x"
        val redactor = SecretRedactor(listOf(key))
        assertEquals("[redacted] [redacted] [redacted] [redacted]", redactor.text(
            "$key key%F0%9F%98%80-x key\\uD83D\\uDE00-x %6Bey%f0%9f%98%80%2Dx"))
        assertEquals(4, redactor.count)
    }
}
