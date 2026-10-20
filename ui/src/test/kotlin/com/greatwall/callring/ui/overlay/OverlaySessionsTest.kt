package com.greatwall.callring.ui.overlay

import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.runtime.*
import org.junit.Assert.*
import org.junit.Test

class OverlaySessionsTest {
    private var epoch = 100_000L
    private var elapsed = 0L
    private fun sessions() = OverlaySessions({ epoch }, { elapsed })
    private fun request(id: String, at: Long = epoch, number: String = "100") = IncomingCallUiRequest(id, number, at, CallDecision.QUIET_SILENCED)

    @Test fun repeatedClosedSessionCannotReopenEvenForSameNumber() {
        val model = sessions()
        assertTrue(model.accept(request("first")))
        model.dismiss()
        assertFalse(model.accept(request("first")))
        assertTrue(model.accept(request("second")))
        assertEquals("second", model.state.value?.request?.sessionId)
    }
    @Test fun disposalOfAnOlderWindowOwnerCannotDismissANewerOwnersSession() {
        val model = sessions()
        val old = model.attachOwner()
        model.accept(request("one"))
        val current = model.attachOwner()
        model.accept(request("two"))
        model.dismissOwned(old)
        assertEquals("two", model.state.value?.request?.sessionId)
        model.dismissOwned(current)
        assertNull(model.state.value)
    }
    @Test fun lateOlderAndExpiredRequestsCannotReplaceNewSession() {
        val model = sessions()
        model.accept(request("new"))
        assertFalse(model.accept(request("old", epoch - 1)))
        assertEquals("new", model.state.value?.request?.sessionId)
        assertFalse(model.accept(request("expired", epoch - 120_001)))
    }
    @Test fun originalCallTimestampAndDecisionNeverChangeOnRuntimeRefresh() {
        val model = sessions()
        val request = request("one")
        model.accept(request)
        model.phone(PhoneActivity.RINGING)
        assertEquals(request, model.state.value?.request)
    }
    @Test fun manuallyRequestedHistoricalPreviewDoesNotPretendToBeALiveCall() {
        val model = sessions()
        val historical = request("preview", epoch - 300_000)
        model.preview(historical)
        model.phone(PhoneActivity.IDLE)
        elapsed = 10_000
        model.phone(PhoneActivity.IDLE)
        assertEquals(historical, model.state.value?.request)
        assertTrue(model.state.value!!.preview)
        assertTrue(model.accept(request("live")))
        assertFalse(model.state.value!!.preview)
        model.phone(PhoneActivity.RINGING); model.phone(PhoneActivity.IDLE)
        assertNull(model.state.value)
    }
    @Test fun settingClockBackDoesNotRejectANewIncomingSession() {
        val model = sessions()
        model.accept(request("before"))
        model.dismiss()
        epoch -= 60_000
        assertTrue(model.accept(request("after")))
        assertEquals("after", model.state.value?.request?.sessionId)
    }
    @Test fun initialIdleAllowsPhoneStateToCatchUpButEventuallyCloses() {
        val model = sessions()
        model.accept(request("one"))
        model.phone(PhoneActivity.IDLE)
        assertNotNull(model.state.value)
        elapsed = 5_001
        model.phone(PhoneActivity.IDLE)
        assertNull(model.state.value)
    }
    @Test fun unavailablePhoneStatusCannotKeepTheCardOpenForever() {
        val model = sessions()
        model.accept(request("one"))
        model.phone(PhoneActivity.UNKNOWN)
        assertNotNull(model.state.value)
        elapsed = 5_001
        model.phone(PhoneActivity.UNKNOWN)
        assertNull(model.state.value)
    }
    @Test fun answerClosesCardAndIdleDoesNotResurrectIt() {
        val model = sessions()
        model.accept(request("one")); model.phone(PhoneActivity.RINGING)
        model.phone(PhoneActivity.ACTIVE)
        assertNull(model.state.value)
        model.phone(PhoneActivity.IDLE)
        assertNull(model.state.value)
    }
    @Test fun callEndPreservesAnOpenEditorUntilUserFinishes() {
        val model = sessions()
        model.accept(request("one")); model.beginEditing()
        model.phone(PhoneActivity.ACTIVE)
        assertTrue(model.state.value!!.editing)
        assertTrue(model.state.value!!.callEnded)
        model.finishEditing()
        assertNull(model.state.value)
        assertFalse(model.accept(request("one")))
    }
    @Test fun newSessionQueuesWithoutOverwritingDraftEvenForSameNumber() {
        val model = sessions()
        model.accept(request("one")); model.beginEditing()
        epoch++
        assertFalse(model.accept(request("two")))
        assertEquals("one", model.state.value?.request?.sessionId)
        model.phone(PhoneActivity.RINGING)
        model.finishEditing()
        assertEquals("two", model.state.value?.request?.sessionId)
        assertFalse(model.state.value!!.editing)
    }
    @Test fun queuedCallThatHasEndedDoesNotPopUpAfterFinishingEditor() {
        val model = sessions()
        model.accept(request("one")); model.beginEditing()
        epoch++; model.accept(request("two"))
        model.phone(PhoneActivity.ACTIVE)
        model.finishEditing()
        assertNull(model.state.value)
        assertFalse(model.accept(request("two")))
    }
    @Test fun collapseCannotHideAnOpenDraft() {
        val model = sessions()
        model.accept(request("one")); model.beginEditing(); model.toggleCollapsed()
        assertFalse(model.state.value!!.collapsed)
    }
    @Test fun dismissDiscardsQueuedRequestsAndTombstonesBothIdentities() {
        val model = sessions()
        model.accept(request("one")); model.beginEditing()
        epoch++; model.accept(request("two")); model.dismiss()
        assertFalse(model.accept(request("one")))
        assertFalse(model.accept(request("two")))
    }
}
