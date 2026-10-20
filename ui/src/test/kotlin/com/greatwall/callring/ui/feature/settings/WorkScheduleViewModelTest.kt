package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkScheduleViewModelTest {
    @Test fun calendarFailureKeepsCachedSnapshotAndShowsReadableRetryMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val cached = CalendarInfo(listOf(CalendarYear(2026, "holiday-cn", "2026-10-18", "v1")), emptyList())
            val api = object : UiFakeApi() {
                override suspend fun getCalendar() = ApiResult.Success(cached)
                override suspend fun synchronizeCalendar(year: Int): ApiResult<CalendarInfo> =
                    ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "io.failure", retryable = true))
            }
            val model = CalendarViewModel(api)
            store.put("calendar", model)
            model.refresh(); runCurrent()
            model.synchronize(2026); runCurrent()
            assertEquals(cached, model.state.value.data)
            assertEquals(ErrorCode.IO_FAILURE, model.mutation.value.error?.code)
            assertEquals("节假日同步未完成，现有日历未改变，请稍后重试", model.mutation.value.error?.message)
            assertTrue(model.mutation.value.error!!.retryable)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun savingUneditedLegacyDraftStillUsesOneSharedSchedule() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val common = listOf(TimeWindow(540, 720), TimeWindow(780, 1080))
            val legacy = WorkScheduleSettings("UTC", mapOf(1 to common, 2 to listOf(TimeWindow(600, 1200))),
                listOf(TimeWindow(600, 1200)), 1, 0)
            var command: SaveWorkSchedule? = null
            val api = object : UiFakeApi() {
                override suspend fun getWorkSchedule() = ApiResult.Success(legacy)
                override suspend fun saveWorkSchedule(value: SaveWorkSchedule): ApiResult<WorkScheduleSettings> {
                    command = value
                    return ApiResult.Success(legacy.copy(weekly = value.weekly, makeup = value.makeup))
                }
            }
            val model = WorkScheduleViewModel(api, SavedStateHandle())
            store.put("work", model)
            model.refresh(); runCurrent()
            model.save(); runCurrent()
            assertNotNull(command)
            (1..5).forEach { assertEquals(common, command!!.weekly[it]) }
            (6..7).forEach { assertEquals(emptyList<TimeWindow>(), command!!.weekly[it]) }
            assertEquals(common, command!!.makeup)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
