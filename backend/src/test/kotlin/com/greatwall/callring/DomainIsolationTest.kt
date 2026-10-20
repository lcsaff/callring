package com.greatwall.callring

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DomainIsolationTest {
    @Test fun domainHasNoTransportApplicationOrIoDependencies() {
        val root = File("src/main/kotlin/com/greatwall/callring/domain")
        assertTrue("domain source root exists", root.isDirectory)
        assertTrue("domain has Kotlin sources", root.walkTopDown().any { it.extension == "kt" })
        val forbidden = Regex("com\\.greatwall\\.callring\\.(contracts|application)|import (android|kotlinx\\.coroutines|java\\.io|java\\.net\\.(?!URI))")
        val violations = root.walkTopDown().filter { it.extension == "kt" }.filter { forbidden.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals("domain dependencies", emptyList<String>(), violations)
    }
    @Test fun settingsValidationDelegatesPureRulesToDomain() {
        val source = File("src/main/kotlin/com/greatwall/callring/application/settings/WorkScheduleService.kt").readText()
        assertFalse("minute validation belongs to domain", source.contains("w.startMinute in"))
        assertFalse("window validation belongs to domain", source.contains("w.endMinute in"))
    }
}
