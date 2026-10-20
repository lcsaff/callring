package com.greatwall.callring

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Golden update is explicit; an ordinary test run can never rewrite the baseline. */
class ToolCompatibilityBaselineTest {
    @Test fun allDiscoverableOperationsMatchCompatibilityBaseline() {
        val registry = ToolRegistry()
        val definitions = registry.allDefinitions
        assertEquals(81, definitions.size)
        assertEquals(81, definitions.map { it.name }.toSet().size)
        assertEquals((1..72).map { "O%02d".format(it) } + (1..9).map { "X%02d".format(it) }, definitions.map { it.id })
        val contracts = registry.operationContracts().associateBy { it.index }
        val operations = definitions.mapIndexed { order, d ->
            val policy = contracts[d.index]?.receiptPolicy?.name ?: when (d.index) {
                73 -> "CATALOG_READ"
                74,75,76,77,80 -> "SOURCE_READ"
                78 -> "IN_MEMORY_PLAN"
                79 -> "BATCH_CHILD_PERSISTED_READBACK"
                81 -> "PERSISTED_READBACK"
                else -> error("Unclassified extension ${d.name}")
            }
            val confirmation = when(d.index) {
                20,32,53,57,63 -> "AUTHORITATIVE_TARGET_CONFIRM"
                66 -> "LOCAL_CREDENTIAL_EDITOR"
                72 -> "LOCAL_CONNECTION_COPY_CONFIRM"
                81 -> "AUTHORITATIVE_TARGET_DOUBLE_CONFIRM"
                else -> "NO_SEPARATE_CONFIRM"
            }
            aiObject(
                "toolName" to aiString(d.name), "operationCode" to aiString(d.id),
                "legacyIndex" to aiNumber(d.index), "discoveryOrder" to aiNumber(order + 1),
                "schema" to d.schema, "schemaDefaults" to aiObject(),
                "runtimeDefaults" to aiString(defaults(d.index)), "write" to aiBool(d.write),
                "requiresForegroundHost" to aiBool(d.write),
                "confirmation" to aiString(confirmation), "receiptClassification" to aiString(policy),
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

    private fun defaults(index: Int): String {
        val specific = when(index) {
            3 -> "text absent: empty search; source path trims blank to no text filter; cursor absent: first page; source limit: limit > page.pageSize > 50; page.pageIndex defaults 0 and nonzero is rejected by source reader; fallback pageSize=50"
            4 -> "number absent/null: new empty draft; source path returns one profile when number omitted"
            5 -> "workAtEpochMillis absent: backend clock; source reader work projection uses source observation time"
            7 -> "name/department/positionId/whitelisted omitted: preserve current draft; explicit positionId null clears"
            9 -> "filter=ALL; cursor absent: first page; source limit: limit > page.pageSize > 50; pageIndex=0; zoneId absent: current schedule zone, UTC if unavailable in source path"
            10 -> "range.zoneId absent: current schedule zone"
            15 -> "mode/allNumbers/weeklyWindows omitted: preserve current quiet settings"
            19 -> "id omitted: create; name/level required semantically when creating; existing name/level preserved if omitted; active preserved on edit, true on create"
            21 -> "id is required but nullable; null clears my position"
            23 -> "zoneId/weekly/makeup omitted: preserve current work schedule"
            28 -> "windows omitted: empty list"
            31 -> "id omitted: create; period omitted on edit: existing timed interval; period required semantically on create; note preserved on edit, empty on create; ALL_DAY requires startDate/endDateInclusive/zoneId, TIMED requires epoch bounds"
            34 -> "all omitted score fields preserve current settings"
            37 -> "all omitted appearance fields preserve current settings; explicit customSeedRgb null clears; no-op emits no new mutation"
            52 -> "recording/ai omitted: empty patch; nested omitted fields Keep; context/output omitted individually preserve current budget; secrets unchanged"
            55 -> "handle omitted: foreground IMPORT_RECORDING picker and IMPORTED source; provided handle: scoped SYSTEM recording"
            65 -> "omitted fields Keep; context/output omitted individually preserve current budget; secrets unchanged"
            73 -> "category=null (all), exactNames=[], query=null, cursor=null (offset 0), limit=8 (allowed 1..12); discovery retains allDefinitions order"
            74 -> "projection=default; filter=empty QueryFilter (all stored, no UI date cap); cursor=null; limit=50 (1..200); maxBytes=32768 (1..131072)"
            75 -> "projection=default"
            76 -> "metric=count; filter=empty QueryFilter; groupBy=null"
            77 -> "cursor=null; maxChars=4096 (Unicode code points; max 16384)"
            78 -> "targets=[]"
            79 -> "per item omitted profile fields delegated to callers_saveProfile; CREATE_PROFILES plan requires non-existing unique valid numbers; 1..32 items"
            else -> "No optional business arguments; required fields must be supplied."
        }
        val paging = if(index in setOf(2,6,11,14,18,22,25,30,33,36,38,44,45,47,50,51,54,61,62,64)) " Source adapter supplies projection/default pagination; see ScopedToolReader for per-operation composed reads." else ""
        val orchestration = if(index <= 72) " dependsOn omitted: []; write taskId omitted: auto-plan only when batch has exactly one write, otherwise explicit plan required." else " Extensions do not automatically inherit dependsOn/taskId schema fields."
        return specific + paging + orchestration
    }
}
