package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.recording.*

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScoreSettingsViewModelTest {
    @Test fun rapidNumericChangesCommitOnceAfterTheDebounceWindow() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val saved = ScoreSettings(5, 2, 10, 30)
            val requests = mutableListOf<Pair<ScoreSettings, ScoreSettings>>()
            val api = object : UiFakeApi() {
                override suspend fun getScoreSettings() = ApiResult.Success(saved)
                override val edits = object : UiEditsApi {
    override suspend fun profile(base: CallerDraft, value: CallerDraft): ApiResult<CallerProfile> = throw AssertionError("Unexpected fixture operation: profile")
    override suspend fun quiet(base: QuietSettings, value: QuietSettings): ApiResult<QuietSettings> = throw AssertionError("Unexpected fixture operation: quiet")
    override suspend fun work(base: WorkScheduleSettings, value: WorkScheduleSettings): ApiResult<WorkScheduleSettings> = throw AssertionError("Unexpected fixture operation: work")
    override suspend fun appearance(base: AppearanceSettings, value: AppearanceSettings): ApiResult<AppearanceSettings> = throw AssertionError("Unexpected fixture operation: appearance")
    override suspend fun position(base: SavePosition?, value: SavePosition): ApiResult<PositionOption> = throw AssertionError("Unexpected fixture operation: position")
    override suspend fun leave(base: SaveLeave?, value: SaveLeave): ApiResult<LeaveEntry> = throw AssertionError("Unexpected fixture operation: leave")

                    override suspend fun score(base: ScoreSettings, value: ScoreSettings): ApiResult<ScoreSettings> {
                        requests += base to value
                        return ApiResult.Success(value)
                    }
                }
            }
            val model = ScoreSettingsViewModel(api, SavedStateHandle())
            store.put("score", model)
            model.refresh(); runCurrent()
            model.edit(saved.copy(repeatMinutes = 11))
            advanceTimeBy(200)
            model.edit(saved.copy(repeatMinutes = 12))
            advanceTimeBy(499); runCurrent()
            assertTrue(requests.isEmpty())
            advanceTimeBy(1); runCurrent()
            assertEquals(listOf(saved to saved.copy(repeatMinutes = 12)), requests)
            assertEquals(saved.copy(repeatMinutes = 12), model.draft.value)
            assertFalse(model.mutation.value.busy)
            assertEquals(0L, model.mutation.value.completed)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
