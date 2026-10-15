package com.insta360.kmpsdk.demo.touchscene

import org.junit.Assert.*
import org.junit.Test

class HoldToTalkSessionTest {
    @Test fun `audio setup delay does not count toward the minimum recording length`() {
        val session = HoldToTalkSession()
        val token = checkNotNull(session.begin(1_000))
        assertTrue(session.markRecordingStarted(token, 1_200))
        assertEquals(HoldToTalkSession.Decision.CANCEL, session.release(token, 1_350))
    }

    @Test fun `timeout starts at successful recording rather than turn reservation`() {
        val session = HoldToTalkSession()
        val token = checkNotNull(session.begin(1_000))
        assertTrue(session.markRecordingStarted(token, 1_200))
        assertEquals(HoldToTalkSession.Decision.IGNORE, session.timeout(token, 31_000))
        assertEquals(HoldToTalkSession.Decision.CANCEL, session.timeout(token, 31_200))
    }

    @Test fun `late recording start cannot change a cancelled or newer turn`() {
        val session = HoldToTalkSession()
        val old = checkNotNull(session.begin(1_000))
        session.cancel()
        val current = checkNotNull(session.begin(2_000))
        assertFalse(session.markRecordingStarted(old, 2_500))
        assertTrue(session.markRecordingStarted(current, 2_100))
        assertEquals(HoldToTalkSession.Decision.SUBMIT, session.release(current, 2_400))
        assertFalse(session.markRecordingStarted(current, 3_000))
    }

    @Test fun `idle accepts one recording and rejects a second begin`() {
        val session = HoldToTalkSession()
        assertEquals(HoldToTalkSession.State.IDLE, session.state)
        val token = checkNotNull(session.begin(1_000))
        assertTrue(session.isRecording)
        assertFalse(session.isProcessing)
        assertTrue(session.isCurrent(token))
        assertNull(session.begin(1_100))
        assertTrue(session.isCurrent(token))
    }

    @Test fun `shorter than 300 ms cancels without submission`() {
        for (duration in listOf(0L, 1L, 299L)) {
            val session = HoldToTalkSession()
            val token = checkNotNull(session.begin(1_000))
            assertEquals(HoldToTalkSession.Decision.CANCEL, session.release(token, 1_000 + duration))
            assertEquals(HoldToTalkSession.State.IDLE, session.state)
            assertFalse(session.isCurrent(token))
            assertFalse(session.finish(token))
        }
    }

    @Test fun `exactly 300 ms submits and enters processing`() {
        val session = HoldToTalkSession()
        val token = checkNotNull(session.begin(1_000))
        assertEquals(HoldToTalkSession.Decision.SUBMIT, session.release(token, 1_300))
        assertTrue(session.isProcessing)
        assertFalse(session.isRecording)
        assertTrue(session.isCurrent(token))
    }

    @Test fun `29999 ms submits but 30000 ms and later cancel`() {
        val accepted = HoldToTalkSession()
        val valid = checkNotNull(accepted.begin(5_000))
        assertEquals(HoldToTalkSession.Decision.SUBMIT, accepted.release(valid, 34_999))
        for (duration in listOf(30_000L, 30_001L, 60_000L)) {
            val rejected = HoldToTalkSession()
            val token = checkNotNull(rejected.begin(5_000))
            assertEquals(HoldToTalkSession.Decision.CANCEL, rejected.release(token, 5_000 + duration))
            assertEquals(HoldToTalkSession.State.IDLE, rejected.state)
            assertFalse(rejected.isCurrent(token))
        }
    }

    @Test fun `cancel invalidates recording and processing tokens`() {
        val session = HoldToTalkSession()
        val recording = checkNotNull(session.begin(0))
        session.cancel()
        assertEquals(HoldToTalkSession.Decision.IGNORE, session.release(recording, 1_000))
        assertFalse(session.isCurrent(recording))
        val processing = checkNotNull(session.begin(2_000))
        assertEquals(HoldToTalkSession.Decision.SUBMIT, session.release(processing, 2_500))
        session.cancel()
        assertFalse(session.isCurrent(processing))
        assertFalse(session.finish(processing))
        assertEquals(HoldToTalkSession.State.IDLE, session.state)
    }

    @Test fun `repeated release submits exactly once and cannot cancel processing`() {
        val session = HoldToTalkSession()
        val token = checkNotNull(session.begin(0))
        assertEquals(HoldToTalkSession.Decision.SUBMIT, session.release(token, 500))
        assertEquals(HoldToTalkSession.Decision.IGNORE, session.release(token, 600))
        assertEquals(HoldToTalkSession.Decision.IGNORE, session.release(token, 30_000))
        assertTrue(session.isProcessing)
    }

    @Test fun `processing rejects begin until current result finishes exactly once`() {
        val session = HoldToTalkSession()
        val token = checkNotNull(session.begin(1_000))
        session.release(token, 1_500)
        assertNull(session.begin(2_000))
        assertFalse(session.finish(token + 1))
        assertTrue(session.isProcessing)
        assertTrue(session.finish(token))
        assertFalse(session.finish(token))
        assertFalse(session.isCurrent(token))
        assertEquals(HoldToTalkSession.State.IDLE, session.state)
        assertTrue(checkNotNull(session.begin(3_000)) > token)
    }

    @Test fun `timeout before 30 seconds is ignored and at 30 seconds cancels`() {
        val session = HoldToTalkSession()
        val token = checkNotNull(session.begin(1_000))
        assertEquals(HoldToTalkSession.Decision.IGNORE, session.timeout(token, 30_999))
        assertTrue(session.isRecording)
        assertEquals(HoldToTalkSession.Decision.CANCEL, session.timeout(token, 31_000))
        assertFalse(session.isCurrent(token))
        assertEquals(HoldToTalkSession.Decision.IGNORE, session.timeout(token, 31_001))
        assertEquals(HoldToTalkSession.Decision.IGNORE, session.release(token, 31_001))
    }

    @Test fun `old timeout and release cannot cancel a new recording`() {
        val session = HoldToTalkSession()
        val old = checkNotNull(session.begin(0))
        session.cancel()
        val current = checkNotNull(session.begin(50_000))
        assertTrue(current > old)
        assertEquals(HoldToTalkSession.Decision.IGNORE, session.timeout(old, 50_100))
        assertEquals(HoldToTalkSession.Decision.IGNORE, session.release(old, 50_200))
        assertFalse(session.finish(old))
        assertTrue(session.isRecording)
        assertTrue(session.isCurrent(current))
    }

    @Test fun `old processing completion cannot finish a new processing session`() {
        val session = HoldToTalkSession()
        val old = checkNotNull(session.begin(0))
        session.release(old, 500)
        session.cancel()
        val current = checkNotNull(session.begin(1_000))
        session.release(current, 1_500)
        assertFalse(session.finish(old))
        assertTrue(session.isProcessing)
        assertTrue(session.isCurrent(current))
        assertTrue(session.finish(current))
    }

    @Test fun `recording timeout cannot cancel processing and finish cannot end recording`() {
        val session = HoldToTalkSession()
        val token = checkNotNull(session.begin(0))
        assertFalse(session.finish(token))
        assertTrue(session.isRecording)
        session.release(token, 500)
        assertEquals(HoldToTalkSession.Decision.IGNORE, session.timeout(token, 40_000))
        assertTrue(session.isProcessing)
    }

    @Test fun `backward clock duration fails closed instead of submitting`() {
        val session = HoldToTalkSession()
        val token = checkNotNull(session.begin(1_000))
        assertEquals(HoldToTalkSession.Decision.CANCEL, session.release(token, 999))
        assertEquals(HoldToTalkSession.State.IDLE, session.state)
    }
}
