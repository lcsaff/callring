package com.greatwall.callring

import com.greatwall.callring.application.conversation.execution.ToolBatchEngine
import com.greatwall.callring.application.conversation.execution.ToolExecutor
import com.greatwall.callring.application.conversation.foreground.ForegroundActionService
import com.greatwall.callring.application.conversation.foreground.ForegroundResourceRegistry
import com.greatwall.callring.application.conversation.operation.CapabilityCatalog
import com.greatwall.callring.application.conversation.operation.ToolRegistry
import com.greatwall.callring.application.conversation.query.AppearanceQueryProjection
import com.greatwall.callring.application.conversation.query.QueryService
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.ports.query.ScopedReadPort
import com.greatwall.callring.application.ports.query.SourcePage
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.settings.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiAppearancePresetsTest {
    private val expected = listOf(
        Triple("unicom_red", "联通红", 0xE60012),
        Triple("sky_blue", "天空蓝", 0x1976D2),
        Triple("teal_green", "青绿色", 0x008577),
    )

    private class Fixture(mode: ColorMode = ColorMode.FOLLOW_SYSTEM) {
        val f = ConversationFixtures().apply {
            appearance = AppearanceSettings(ThemeSelection.CUSTOM, "sky_blue", 0x123456, mode)
        }
        var unrelatedReads = 0
        val projection = AppearanceQueryProjection { f.appearance }
        val source = object : ScopedReadPort {
            override val domains = AiDomain.entries.toSet()
            override fun version(domain: AiDomain) = f.coordinator.marker()
            override fun query(spec: QuerySpec, after: String?): SourcePage {
                if (spec.domain != AiDomain.D11) { unrelatedReads++; error("Unexpected domain") }
                return SourcePage(projection.rows(spec).toList(), 1)
            }
            override fun get(domain: AiDomain, key: String, projection: String) =
                query(QuerySpec(domain, projection), null).rows.singleOrNull { it.key == key }
        }
        val q = QueryService(listOf(source), revision = f.coordinator::marker)
        val executor = ToolExecutor({ f.backend }, f.coordinator,
            ForegroundActionService(checkCommand = { _, _ -> true }).also { it.attachHost() }, ForegroundResourceRegistry(),
            queries = q, documentWriteVerified = { false })
        val engine = ToolBatchEngine({ f.backend }, executor, q, f.coordinator,
            "session", "turn", 1, { true })
    }

    @Test fun everyDisplayedPresetNameDiscoversTheReadAndWriteTools() {
        val discovery = CapabilityCatalog(ToolRegistry())
        expected.forEach { (id, name, _) ->
            val tools = discovery.discover(query = name).tools
            for (toolName in listOf("settings_getAppearance", "settings_saveAppearance")) {
                val tool = tools.singleOrNull { it.name == toolName }
                assertNotNull("$name must discover $toolName", tool)
                assertTrue(tool!!.description.contains(id))
            }
        }
    }

    @Test fun sourceQueryReturnsAllThreePresetsWithoutChangingStoredAppearance() = runBlocking {
        val t = Fixture()
        val before = t.f.appearance
        val row = t.q.query(QuerySpec(AiDomain.D11)).requiredValue().items.single()
        val presets = row["presets"] as? AiJson.Arr
        assertNotNull("D11 must expose the available theme presets", presets)
        assertEquals(expected, presets!!.values.map {
            val preset = it as AiJson.Obj
            Triple(preset["id"].string(), preset["name"].string(), preset["seedRgb"].long().toInt())
        })
        assertEquals(before, t.f.appearance)
        assertEquals(0, t.unrelatedReads)
        assertEquals(0L, t.f.coordinator.marker())
    }

    @Test fun eachPresetCanBeSelectedFromToolReadbackWhilePreservingEveryColorMode() = runBlocking {
        for (mode in ColorMode.entries) for ((id, name, _) in expected) {
            val t = Fixture(mode)
            val read = t.engine.execute(listOf(ConversationToolCall("read", "settings_getAppearance", aiObject()))).single()
            val value = (read.value as AiJson.Obj)["value"] as AiJson.Obj
            val presets = value["presets"] as? AiJson.Arr
            assertNotNull("The appearance tool must expose $name", presets)
            val preset = presets!!.values.map { it as AiJson.Obj }.single { it["name"].string() == name }
            val saved = t.engine.execute(listOf(ConversationToolCall("save", "settings_saveAppearance",
                aiObject("selection" to aiString("PRESET"), "presetId" to preset["id"])))).single()
            assertEquals(VerificationStatus.VERIFIED, saved.verification)
            assertEquals(id, t.f.appearance.presetId)
            assertEquals(ThemeSelection.PRESET, t.f.appearance.selection)
            assertEquals(mode, t.f.appearance.colorMode)
            assertEquals(0x123456, t.f.appearance.customSeedRgb)
            assertEquals(1L, t.f.coordinator.marker())
            assertEquals(0, t.unrelatedReads)
            assertTrue(t.engine.complete)
        }
    }
}
