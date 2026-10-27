package com.greatwall.callring

import com.greatwall.callring.application.conversation.execution.ToolBatchEngine
import com.greatwall.callring.application.conversation.execution.ToolExecutor
import com.greatwall.callring.application.conversation.execution.json
import com.greatwall.callring.application.conversation.operation.CapabilityCatalog
import com.greatwall.callring.application.conversation.operation.ToolRegistry
import com.greatwall.callring.application.conversation.query.ScopedToolReader
import com.greatwall.callring.application.conversation.query.json

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Golden update is explicit; an ordinary test run can never rewrite the baseline. */
class ToolCompatibilityBaselineTest {
    @Test fun allDiscoverableOperationsMatchCompatibilityBaseline() {
        val registry = ToolRegistry()
        val definitions = registry.definitions
        assertEquals(85, definitions.size)
        assertEquals(85, definitions.map { it.name }.toSet().size)
        assertEquals((1..72).map { "O%02d".format(it) } + (1..13).map { "X%02d".format(it) }, definitions.map { it.operationCode })
        val operations = definitions.map { d ->
            aiObject(
                "toolName" to aiString(d.name), "operationCode" to aiString(d.operationCode),
                "legacyIndex" to aiNumber(d.legacyIndex), "discoveryOrder" to aiNumber(d.displayOrder),
                "schema" to d.schema, "schemaDefaults" to aiObject(),
                "runtimeDefaults" to aiString(d.runtimeDefaults), "write" to aiBool(d.write),
                "requiresForegroundHost" to aiBool(d.write),
                "confirmation" to aiString(d.confirmation!!.name), "receiptClassification" to aiString(d.receiptPolicy.name),
                "description" to aiString(d.description),
            )
        }
        val golden = aiObject(
            "formatVersion" to aiNumber(1),
            "catalogVersion" to aiString("2"),
            "schemaDefaultsNote" to aiString("Registry schemas declare no JSON Schema default keywords. runtimeDefaults records omission behavior from ToolExecutor, ScopedToolReader, CapabilityCatalog and ToolBatchEngine; preserved values are not constant defaults."),
            "operations" to AiJson.Arr(operations),
        ).encode() + "\n"
        val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle").isFile || File(it, "settings.gradle.kts").isFile }
        val baseline = File(root, "docs/contracts/ai-operation-compatibility.json")
        if (System.getenv("CALLRING_BOOTSTRAP_TOOL_BASELINE") == "1") {
            check(!baseline.exists()) { "Bootstrap only creates a missing golden; review/remove explicitly before updating" }
            baseline.parentFile.mkdirs()
            baseline.writeText(golden, Charsets.UTF_8)
        }
        assertTrue("Missing baseline; generate once with CALLRING_BOOTSTRAP_TOOL_BASELINE=1", baseline.isFile)
        assertEquals("Tool wire contract changed; review compatibility before updating golden", baseline.readText(Charsets.UTF_8).replace("\r\n", "\n"), golden)
    }

}
