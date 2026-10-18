package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.DefaultAppearance
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppearanceViewModelTest {
    @Test fun rapidSelectionsApplyImmediatelyAndLatestIsSavedAfterSuspendedWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val firstWrite = CompletableDeferred<Unit>()
            val requests = mutableListOf<AppearanceSettings>()
            val fake = UiFakeApi()
            val api = object : CallringUiApi by fake {
                override val edits = object : UiEditsApi by fake.edits {
                    override suspend fun appearance(base: AppearanceSettings, settings: AppearanceSettings): ApiResult<AppearanceSettings> {
                        requests += settings
                        if (requests.size == 1) firstWrite.await()
                        return fake.saveAppearance(settings)
                    }
                }
            }
            val model = AppearanceViewModel(api, SavedStateHandle())
            store.put("appearance", model)
            model.refresh(); runCurrent()
            val first = DefaultAppearance.copy(presetId = "sky_blue")
            val latest = DefaultAppearance.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = 0x123456, colorMode = ColorMode.DARK)
            model.edit(first); runCurrent()
            assertEquals(listOf(first), requests)
            model.edit(first.copy(colorMode = ColorMode.DARK))
            model.edit(latest)
            assertEquals(latest, model.draft.value)
            firstWrite.complete(Unit); runCurrent()
            assertEquals(listOf(first, latest), requests)
            assertEquals(latest, fake.appearance)
            assertFalse(model.mutation.value.busy)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun failureKeepsLastPersistedSnapshotAndRetrySavesLatestSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var fail = true
            val fake = UiFakeApi()
            val api = object : CallringUiApi by fake {
                override val edits = object : UiEditsApi by fake.edits {
                    override suspend fun appearance(base: AppearanceSettings, settings: AppearanceSettings): ApiResult<AppearanceSettings> =
                        if (fail) ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "disk unavailable"))
                        else fake.saveAppearance(settings)
                }
            }
            val model = AppearanceViewModel(api, SavedStateHandle())
            store.put("appearance", model)
            model.refresh(); runCurrent()
            val selected = DefaultAppearance.copy(colorMode = ColorMode.DARK)
            model.edit(selected); runCurrent()
            assertEquals(ErrorCode.STORAGE_FAILURE, model.mutation.value.error?.code)
            assertEquals(0L, model.mutation.value.completed)
            assertEquals(DefaultAppearance, model.state.value.data)
            assertEquals(DefaultAppearance, fake.appearance)
            assertEquals(selected, model.draft.value)
            fail = false
            model.save(); runCurrent()
            assertEquals(selected, fake.appearance)
            assertNull(model.mutation.value.error)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun externalAppearanceChangesReplaceLiveSelectionWhenNoSaveIsPending() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val fake = UiFakeApi()
            val model = AppearanceViewModel(fake, SavedStateHandle())
            store.put("appearance", model)
            model.refresh(); runCurrent()
            val external = DefaultAppearance.copy(colorMode = ColorMode.DARK)
            fake.saveAppearance(external); runCurrent()
            assertEquals(external, model.state.value.data)
            assertEquals(external, model.draft.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun colorDragPreviewsEveryValueButPersistsOnlyLatestAfterDebounce() = runTest {
        val requests = mutableListOf<AppearanceSettings>()
        val fake = UiFakeApi()
        val api = object : CallringUiApi by fake {
            override val edits = object : UiEditsApi by fake.edits {
                override suspend fun appearance(base: AppearanceSettings, settings: AppearanceSettings): ApiResult<AppearanceSettings> {
                    requests += settings
                    return fake.saveAppearance(settings)
                }
            }
        }
        val model = AppearanceViewModel(api, SavedStateHandle(), backgroundScope)
        model.refresh(); runCurrent()
        repeat(5) { component ->
            val color = DefaultAppearance.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = component)
            model.editColor(color)
            assertEquals(color, model.draft.value)
            advanceTimeBy(40); runCurrent()
        }
        assertTrue(requests.isEmpty())
        advanceTimeBy(250); runCurrent()
        assertEquals(listOf(DefaultAppearance.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = 4)), requests)
    }

    @Test fun newerChoiceStillPersistsWhenPreviousSuspendedWriteFails() = runTest {
        val firstWrite = CompletableDeferred<Unit>()
        val requests = mutableListOf<AppearanceSettings>()
        val fake = UiFakeApi()
        val api = object : CallringUiApi by fake {
            override val edits = object : UiEditsApi by fake.edits {
                override suspend fun appearance(base: AppearanceSettings, settings: AppearanceSettings): ApiResult<AppearanceSettings> {
                    requests += settings
                    return if (requests.size == 1) {
                        firstWrite.await()
                        ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "first write failed"))
                    } else fake.saveAppearance(settings)
                }
            }
        }
        val model = AppearanceViewModel(api, SavedStateHandle(), backgroundScope)
        model.refresh(); runCurrent()
        model.edit(DefaultAppearance.copy(presetId = "sky_blue")); runCurrent()
        val latest = DefaultAppearance.copy(colorMode = ColorMode.DARK)
        model.edit(latest)
        firstWrite.complete(Unit); runCurrent()
        assertEquals(latest, fake.appearance)
        assertEquals(latest, model.draft.value)
        assertNull(model.mutation.value.error)
        assertEquals(1L, model.mutation.value.completed)
    }
}
