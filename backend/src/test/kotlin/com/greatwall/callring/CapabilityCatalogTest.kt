package com.greatwall.callring

import com.greatwall.callring.application.conversation.*
import org.junit.Assert.*
import org.junit.Test

class CapabilityCatalogTest {
    @Test fun allLegacyAndExtensionToolsAreDiscoverableWithNoBusinessDependency() {
        val catalog=CapabilityCatalog(ToolRegistry())
        val names=linkedSetOf<String>();var cursor:String?=null
        do { val page=catalog.discover(cursor=cursor,limit=12); names.addAll(page.tools.map{it.name});cursor=page.nextCursor } while(cursor!=null)
        assertTrue(names.containsAll(ToolRegistry().definitions.map{it.name}))
        assertTrue(names.containsAll(listOf("data_query","data_get","data_aggregate","data_readText","task_prepare","callers_saveProfiles")))
        val profile=catalog.discover(exactNames=listOf("settings_saveAppearance")).tools.single()
        assertTrue(profile.description.contains("colorMode"))
        assertEquals(listOf("settings_saveAppearance"),catalog.discover(query="深色模式").tools.map{it.name})
    }
}
