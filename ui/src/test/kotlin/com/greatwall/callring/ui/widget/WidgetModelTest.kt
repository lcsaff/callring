package com.greatwall.callring.ui.widget

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WidgetModelTest {
    private val failed = ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "无法读取"))

    @Test fun eachWidgetLoadsItsOwnBindingAndOverviewAtCurrentTime() = runTest {
        val queries = mutableListOf<OverviewQuery>()
        val api = object : UiFakeApi() {
            override suspend fun getBinding(widgetId: Int) = ApiResult.Success(WidgetBinding(widgetId, "number-$widgetId"))
            override suspend fun getOverview(query: OverviewQuery): ApiResult<CallerOverview> {
                queries += query
                return super.getOverview(query)
            }
        }
        val model = WidgetModel(api) { 1234L }
        assertEquals("number-7", model.load(7).binding?.number)
        assertEquals("number-8", model.load(8).binding?.number)
        assertEquals(listOf(OverviewQuery("number-7", 1234L), OverviewQuery("number-8", 1234L)), queries)
    }

    @Test fun missingBindingAndReadFailureBothOfferRecovery() = runTest {
        val absent = WidgetModel(object : UiFakeApi() {
            override suspend fun getBinding(widgetId: Int): ApiResult<WidgetBinding?> = ApiResult.Success(null)
        }).load(1)
        assertNull(absent.overview)
        assertTrue(absent.needsConfiguration)
        val failure = WidgetModel(object : UiFakeApi() {
            override suspend fun getBinding(widgetId: Int): ApiResult<WidgetBinding?> = failed
        }).load(2)
        assertTrue(failure.needsConfiguration)
        assertEquals(ErrorCode.STORAGE_FAILURE, failure.error?.code)
    }

    @Test fun overviewFailureKeepsBindingAndRecoverableError() = runTest {
        val api = object : UiFakeApi() {
            override suspend fun getBinding(widgetId: Int) = ApiResult.Success(WidgetBinding(widgetId, "100"))
            override suspend fun getOverview(query: OverviewQuery): ApiResult<CallerOverview> = failed
        }
        val snapshot = WidgetModel(api).load(4)
        assertEquals("100", snapshot.binding?.number)
        assertTrue(snapshot.needsConfiguration)
        assertNotNull(snapshot.error)
    }

    @Test fun whitelistSubmitsExplicitTargetAndRejectsOldBindingAction() = runTest {
        var target: Pair<String, Boolean>? = null
        val api = object : UiFakeApi() {
            override suspend fun getBinding(widgetId: Int) = ApiResult.Success(WidgetBinding(widgetId, "100"))
            override suspend fun setWhitelisted(number: String, enabled: Boolean): ApiResult<CallerProfile> {
                target = number to enabled
                return super.setWhitelisted(number, enabled)
            }
        }
        val model = WidgetModel(api)
        assertTrue(model.setWhitelisted(3, "100", true) is ApiResult.Success)
        assertEquals("100" to true, target)
        target = null
        assertTrue(model.setWhitelisted(3, "old-number", false) is ApiResult.Failure)
        assertNull(target)
    }

    @Test fun deletionRemovesOnlyTheRequestedBinding() = runTest {
        val removed = mutableListOf<Int>()
        val api = object : UiFakeApi() {
            override suspend fun removeBinding(widgetId: Int): ApiResult<Unit> {
                removed += widgetId
                return ApiResult.Success(Unit)
            }
        }
        WidgetModel(api).remove(42)
        assertEquals(listOf(42), removed)
    }
}
