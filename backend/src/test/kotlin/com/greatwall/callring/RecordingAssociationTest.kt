package com.greatwall.callring

import com.greatwall.callring.application.recording.IncomingRecordingSession
import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.domain.calling.RecordingCallLink
import com.greatwall.callring.domain.calling.resolveRecordingCall

import com.greatwall.callring.application.*
import org.junit.Assert.*
import org.junit.Test

class RecordingAssociationTest {
    @Test fun lateIndependentRegistrationAfterAnswerCanStillStartCapture() {
        val session = IncomingRecordingSession()
        session.ringing()
        session.answered()
        assertNull(session.activeCandidate(101))
        session.register(-1, 100)
        assertEquals(-1L, session.activeCandidate(101))
        session.idle(102)
        assertNull(session.activeCandidate(103))
        session.ringing(); session.register(-2, 104)
        assertNull(session.activeCandidate(105))
    }
    @Test fun hiddenRecordingIdentityRequiresExactlyOneVisibleLink() {
        val hidden = RecordingCallLink(1, "31234", 100, null, hidden = true)
        val first = RecordingCallLink(2, "31234", 101, 60, linkedScreenId = 1)
        assertNull(resolveRecordingCall(hidden, emptyList()))
        assertNull(resolveRecordingCall(hidden, listOf(first, first.copy(id = 3))))
        assertEquals(RecordingCall(1, "31234", 101, 60), resolveRecordingCall(hidden, listOf(first)))
    }
    @Test fun synchronizedCallUsesOriginalScreeningArchive() {
        val records = listOf(RecordingCallLink(1, "31234", 100, null, hidden = true),
            RecordingCallLink(2, "31234", 101, 60, linkedScreenId = 1))
        assertEquals(RecordingCall(1, "31234", 101, 60), resolveRecordingCall(records[1], emptyList()))
        assertEquals(resolveRecordingCall(records[1], emptyList()), resolveRecordingCall(records[0], listOf(records[1])))
    }
    @Test fun idleClearsIncomingSessionSoOutgoingCallCannotReuseIt() {
        val session = IncomingRecordingSession()
        session.ringing()
        session.register(1, 100)
        assertEquals(1L, session.candidate(101))
        session.idle(102)
        assertNull(session.candidate(103))
        session.register(1, 100)
        assertNull(session.candidate(103))
        session.register(2, 103)
        assertNull(session.candidate(104))
    }
    @Test fun ambiguousConcurrentIncomingSessionsAreNotAutoCaptured() {
        val session = IncomingRecordingSession()
        session.ringing(); session.register(1, 100); session.register(2, 101)
        assertNull(session.candidate(102))
    }
}
