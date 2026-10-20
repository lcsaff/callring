package com.greatwall.callring.ui.widget

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.testing.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import androidx.lifecycle.SavedStateHandle

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WidgetConfigurationModelTest {
    @Test fun processRestorationKeepsEditedSelectionInsteadOfTheOldBinding() = runTest {
        val api = object : UiFakeApi() {
            override suspend fun getBinding(widgetId: Int) = ApiResult.Success(WidgetBinding(widgetId, "200"))
        }
        val handle = SavedStateHandle()
        val first = WidgetConfigurationModel(14, api, backgroundScope, savedState = handle)
        runCurrent()
        first.changeQuery("100"); first.selectDirectNumber(); runCurrent()
        val restored = WidgetConfigurationModel(14, api, backgroundScope,
            savedState = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        runCurrent()
        assertEquals("100", restored.query.value)
        assertEquals("100", restored.selectedNumber.value)
        assertNull(restored.savedBinding.value)
    }
    @Test fun directNumberUsesApiNormalizationAndOnlyExplicitSaveWritesBinding() = runTest {
        val writes = mutableListOf<WidgetBinding>()
        val api = object : UiFakeApi() {
            override fun checkNumber(raw: String) = NumberCheck("100", raw == "raw" || raw == "100", false)
            override suspend fun getBinding(widgetId: Int): ApiResult<WidgetBinding?> = ApiResult.Success(null)
            override suspend fun saveBinding(widgetId: Int, number: String): ApiResult<WidgetBinding> {
                return WidgetBinding(widgetId, number).also { writes += it }.let { ApiResult.Success(it) }
            }
        }
        val model = WidgetConfigurationModel(11, api, backgroundScope)
        runCurrent()
        model.changeQuery("raw")
        runCurrent()
        assertTrue(writes.isEmpty())
        model.selectDirectNumber()
        assertEquals("100", model.selectedNumber.value)
        assertTrue(writes.isEmpty())
        model.save(); runCurrent()
        assertEquals(listOf(WidgetBinding(11, "100")), writes)
        assertEquals(WidgetBinding(11, "100"), model.savedBinding.value)
    }

    @Test fun invalidNumberCannotWriteAndFailureDoesNotCompleteConfiguration() = runTest {
        var writes = 0
        val api = object : UiFakeApi() {
            override suspend fun getBinding(widgetId: Int): ApiResult<WidgetBinding?> = ApiResult.Success(null)
            override suspend fun saveBinding(widgetId: Int, number: String): ApiResult<WidgetBinding> {
                writes++
                return ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "保存失败"))
            }
        }
        val model = WidgetConfigurationModel(12, api, backgroundScope)
        runCurrent()
        model.changeQuery("invalid"); model.selectDirectNumber(); model.save(); runCurrent()
        assertEquals(0, writes)
        model.changeQuery("100"); model.selectDirectNumber(); model.save(); runCurrent()
        assertEquals(1, writes)
        assertNull(model.savedBinding.value)
        assertNotNull(model.mutation.value.error)
    }

    @Test fun changingSearchClearsSelectionAndDoesNotSaveOnCancellation() = runTest {
        val queries = mutableListOf<String>()
        val api = object : UiFakeApi() {
            override suspend fun getBinding(widgetId: Int): ApiResult<WidgetBinding?> = ApiResult.Success(null)
            override suspend fun search(query: CallerSearch): ApiResult<Page<CallerProfile>> {
                queries += query.text
                return super.search(query)
            }
        }
        val model = WidgetConfigurationModel(13, api, backgroundScope)
        runCurrent()
        model.changeQuery("100"); model.selectDirectNumber(); runCurrent()
        assertEquals("100", model.selectedNumber.value)
        model.changeQuery("department"); runCurrent()
        assertNull(model.selectedNumber.value)
        assertEquals("department", queries.last())
        assertNull(model.savedBinding.value)
    }

    @Test fun bindingReadFailureKeepsNumberSelectionAvailable() = runTest {
        val api = object : UiFakeApi() {
            override suspend fun getBinding(widgetId: Int): ApiResult<WidgetBinding?> = ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "无法读取"))
        }
        val model = WidgetConfigurationModel(14, api, backgroundScope)
        runCurrent()
        assertNotNull(model.binding.value.error)
        model.changeQuery("100"); model.selectDirectNumber()
        assertEquals("100", model.selectedNumber.value)
    }
}

