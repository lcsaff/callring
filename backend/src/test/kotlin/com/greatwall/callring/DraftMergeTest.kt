package com.greatwall.callring

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DraftMergeTest {
    @Test fun independentChangesMergeAndSameFieldConflictsWithoutWriting() = runBlocking {
        val f = ConversationFixtures()
        val base = (f.backend.settings.getScoreSettings() as ApiResult.Success).value
        f.backend.settings.saveScoreSettings(base.copy(repeatMinutes = 42))
        val saved = f.backend.edits.score(base, base.copy(offHoursWeight = 13)) as ApiResult.Success
        assertEquals(42, saved.value.repeatMinutes)
        assertEquals(13, saved.value.offHoursWeight)
        val conflict = f.backend.edits.score(base, base.copy(repeatMinutes = 18)) as ApiResult.Failure
        assertEquals(ErrorCode.CONFLICT, conflict.error.code)
        assertTrue(conflict.error.fieldErrors.containsKey("repeatMinutes"))
        assertEquals(saved.value, (f.backend.settings.getScoreSettings() as ApiResult.Success).value)
    }
    @Test fun profileDraftPreservesConcurrentIdentityAndWhitelist() = runBlocking {
        val f = ConversationFixtures()
        f.backend.callers.saveProfile(SaveCallerProfile("31234", "old", "org", null, false))
        val base = (f.backend.callers.getEditor("31234") as ApiResult.Success).value.draft
        f.backend.callers.saveProfile(SaveCallerProfile("31234", "AI", "org", null, true))
        val saved = f.backend.edits.profile(base, base.copy(department = "user")) as ApiResult.Success
        assertEquals("AI", saved.value.name)
        assertEquals("user", saved.value.department)
        assertTrue(saved.value.whitelisted)
    }
    @Test fun explicitNullClearsAppearanceAndValidationRemainsShared() = runBlocking {
        val f = ConversationFixtures()
        val original = (f.backend.settings.getAppearance() as ApiResult.Success).value
        val base = (f.backend.settings.saveAppearance(original.copy(customSeedRgb = 123)) as ApiResult.Success).value
        val saved = f.backend.edits.appearance(base, base.copy(customSeedRgb = null)) as ApiResult.Success
        assertNull(saved.value.customSeedRgb)
        assertTrue(f.backend.edits.score((f.backend.settings.getScoreSettings() as ApiResult.Success).value,
            ScoreSettings(-1, 2, 10, 30)) is ApiResult.Failure)
    }
}
