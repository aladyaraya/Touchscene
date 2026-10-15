package com.insta360.kmpsdk.demo.touchscene

import org.junit.Assert.*
import org.junit.Test

class TapHoldGestureTest {
    @Test fun shortReleaseIsOneTap() {
        val gate = TapHoldGesture(); val token = gate.press(1_000)!!
        assertEquals(TapHoldGesture.Release.TAP, gate.release(token, 1_499))
        assertEquals(TapHoldGesture.Release.IGNORE, gate.release(token, 1_499))
    }
    @Test fun longStartsAtFiveHundredOnlyOnce() {
        val gate = TapHoldGesture(); val token = gate.press(1_000)!!
        assertFalse(gate.confirmLong(token, 1_499)); assertTrue(gate.confirmLong(token, 1_500))
        assertFalse(gate.confirmLong(token, 1_501))
        assertEquals(TapHoldGesture.Release.HOLD, gate.release(token, 1_501))
    }
    @Test fun delayedLongTimerCannotProduceTap() {
        val gate = TapHoldGesture(); val token = gate.press(0)!!
        assertEquals(TapHoldGesture.Release.CANCEL, gate.release(token, 500))
        assertFalse(gate.confirmLong(token, 600))
    }
    @Test fun cancelInvalidatesTimersAndRelease() {
        val gate = TapHoldGesture(); val old = gate.press(0)!!; gate.cancel()
        val current = gate.press(1_000)!!
        assertFalse(gate.confirmLong(old, 2_000))
        assertEquals(TapHoldGesture.Release.IGNORE, gate.release(old, 2_000))
        assertTrue(gate.confirmLong(current, 1_500))
    }
    @Test fun repeatedDownDoesNotReplaceThePendingGesture() {
        val gate = TapHoldGesture(); val token = gate.press(0)!!
        assertNull(gate.press(100)); assertTrue(gate.confirmLong(token, 500))
    }
    @Test fun backwardsClockCancels() {
        val gate = TapHoldGesture(); val token = gate.press(1_000)!!
        assertEquals(TapHoldGesture.Release.CANCEL, gate.release(token, 999))
    }
    @Test fun aShortRecordingAfterLongConfirmationIsNeverATap() {
        val gate = TapHoldGesture(); val token = gate.press(0)!!
        assertTrue(gate.confirmLong(token, 500))
        assertEquals(TapHoldGesture.Release.HOLD, gate.release(token, 550))
    }
}
