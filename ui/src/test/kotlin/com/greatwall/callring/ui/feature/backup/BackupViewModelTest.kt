package com.greatwall.callring.ui.feature.backup

import com.greatwall.callring.contracts.backup.*
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val summary = BackupSummary(1, 2, 3, 4, 0, 0, false, false)
    private inner class Api : BackupApi {
        override val progress = MutableStateFlow(BackupProgress(BackupPhase.IDLE))
        var secret: CharArray? = null
        var restores = 0
        var discarded = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun export(destination: DocumentRef, password: CharArray, includeAudio: Boolean): ApiResult<BackupSummary> {
            secret = password
            return ApiResult.Success(summary)
        }
        override suspend fun preview(source: DocumentRef, password: CharArray): ApiResult<BackupPreview> {
            secret = password
            gate?.await()
            return ApiResult.Success(BackupPreview("verified", summary))
        }
        override suspend fun restore(previewToken: String): ApiResult<RestoreReceipt> { restores++; return ApiResult.Success(RestoreReceipt(true)) }
        override suspend fun discard(previewToken: String): ApiResult<Unit> { discarded += previewToken; return ApiResult.Success(Unit) }
    }

    @Test fun passwordIsMemoryOnlyClearedAfterExportAndNoRestoreWithoutPreview() = runTest {
        val api = Api()
        val model = BackupViewModel(api, backgroundScope)
        model.editPassword("local password")
        model.export(DocumentRef("content://backup")); runCurrent()
        assertEquals("", model.password.value)
        assertTrue(api.secret!!.all { it == 0.toChar() })
        assertEquals(summary, model.state.value.exported)
        model.restore(); runCurrent()
        assertEquals(0, api.restores)
    }

    @Test fun previewRequiresExplicitConfirmAndCannotBeRestoredTwice() = runTest {
        val api = Api()
        val model = BackupViewModel(api, backgroundScope)
        model.editPassword("local password")
        model.preview(DocumentRef("content://backup")); runCurrent()
        assertNotNull(model.state.value.preview)
        assertEquals(0, api.restores)
        model.restore(); model.restore(); runCurrent()
        assertEquals(1, api.restores)
        assertNull(model.state.value.preview)
        assertEquals("", model.password.value)
    }

    @Test fun cancelledPreviewCannotPublishOrTriggerRestoration() = runTest {
        val api = Api().apply { gate = CompletableDeferred() }
        val model = BackupViewModel(api, backgroundScope)
        model.editPassword("local password")
        model.preview(DocumentRef("content://backup")); runCurrent()
        model.cancel(); api.gate!!.complete(Unit); runCurrent()
        assertNull(model.state.value.preview)
        assertFalse(model.state.value.busy)
        model.restore(); runCurrent()
        assertEquals(0, api.restores)
        assertTrue(api.secret!!.all { it == 0.toChar() })
    }

    @Test fun leavingScreenDiscardsVerifiedPreviewAndClearsPassword() = runTest {
        val api = Api()
        val model = BackupViewModel(api, backgroundScope)
        model.editPassword("local password")
        model.preview(DocumentRef("content://backup")); runCurrent()
        model.editPassword("next draft")
        model.exit(); runCurrent()
        assertEquals("", model.password.value)
        assertEquals(listOf("verified"), api.discarded)
        assertNull(model.state.value.preview)
    }
}
