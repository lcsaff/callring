package com.greatwall.callring

import com.greatwall.callring.contracts.CallringUiApi
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationApiContractTest {
    @Test fun rootExposesConversationSettingsAndCompleteSnapshots() {
        val names = CallringUiApi::class.java.methods.map { it.name }.toSet()
        assertTrue("Root API must expose a conversation service", "getConversation" in names)
        assertTrue("Root API must expose scoped AI settings", "getAiSettings" in names)
        assertTrue("Root API must expose complete snapshots", "getSnapshots" in names)
    }
}
