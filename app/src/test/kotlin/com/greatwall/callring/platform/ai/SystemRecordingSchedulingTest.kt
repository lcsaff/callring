package com.greatwall.callring.platform.ai

import com.greatwall.callring.domain.calling.NumberRule
import com.greatwall.callring.domain.calling.NumberRuleSnapshot
import com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.ports.recording.RecordingIdentityEnrichment
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.bootstrap.RecordingAssembly
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.data.recording.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SystemRecordingSchedulingTest {
    @Test fun actualAssemblySchedulesOnlyDiscoveryChanges() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val coordinator = BusinessRevisionCoordinator()
        val namespace = "scheduling"
        val cipher = object : RecordingCipher { override fun encrypt(value: String) = value; override fun decrypt(value: String) = value }
        val store = AndroidRecordingStore(context,namespace,cipher,recordingVersion = { coordinator.capture().values.getValue("recording") })
        val connection = AiConnection(url="https://fixture.invalid/v1",apiKey="fixture",model="fixture")
        store.persistSettings(RecordingSettings(analysis=connection))
        val schedules = mutableListOf<Long>()
        val enrichment = object : RecordingIdentityEnrichment {
    override fun suggestions(archive: com.greatwall.callring.contracts.recording.CallArchive): com.greatwall.callring.contracts.recording.CallerSuggestions? = error("unused")
    override fun hasConfirmation(archive: com.greatwall.callring.contracts.recording.CallArchive): Boolean = error("unused")
    override fun confirm(archive: com.greatwall.callring.contracts.recording.CallArchive, command: com.greatwall.callring.contracts.recording.ConfirmCallerSuggestions, checkActive: () -> Unit): com.greatwall.callring.contracts.recording.CallerSuggestionConfirmation = error("unused")

            override fun capture(number: String, recordedAt: Long) = CallerIdentityBaseline("fixture",emptyMap())
            override fun apply(number: String, baseline: CallerIdentityBaseline, archiveId: Long, generation: Long, candidates: Map<String,Pair<String,String>>) = Unit
        }
        val runtime = RecordingAssembly(context,namespace,{null},{null},{schedules += it},storeFactory={store},
            coordinator=coordinator,operationActive={true},enrichment=enrichment,numberRules = { NumberRuleSnapshot(listOf(NumberRule.DEFAULT),0) }, numberMatcher = PrefixLengthNumberMatcher, findCalls={_,_->emptyList()})
        try {
            val api = runtime.aiSettings
            api.setSystemFolder("content://fixture/tree/first")
            assertTrue(schedules.isEmpty())
            var base = api.getRecordingView().requiredValue()
            base = api.patchRecording(base,RecordingSettingsPatch(recordingEnabled=PatchField.Set(true))).requiredValue()
            assertEquals(listOf(0L),schedules)
            base = api.patchRecording(base,RecordingSettingsPatch(recordingEnabled=PatchField.Set(true))).requiredValue()
            assertEquals(1,schedules.size)
            api.setSystemFolder("content://fixture/tree/second")
            api.setSystemFolder("content://fixture/tree/second")
            assertEquals(2,schedules.size)
            assertEquals(2,schedules.size)
            base = api.patchRecording(base,RecordingSettingsPatch(automaticAnalysis=PatchField.Set(true))).requiredValue()
            val ai = api.patchAi(api.getAiView().requiredValue(),AiSettingsPatch(budget=PatchField.Set(ConversationBudget(65536,4096)))).requiredValue()
            assertEquals(2,schedules.size)
            assertEquals(2,schedules.size)
            base = api.getRecordingView().requiredValue()
            base = api.patchRecording(base,RecordingSettingsPatch(recordingEnabled=PatchField.Set(false))).requiredValue()
            assertEquals(listOf(0L,0L),schedules)
            // No service/discovery is initialized: these are callbacks from real settings commits.
            assertNull(store.sourceBinding("any"))
        } finally {
            runtime.close()
            context.deleteDatabase(namespace+"_recording.db")
            context.deleteSharedPreferences(namespace+"_recording")
        }
    }
}
