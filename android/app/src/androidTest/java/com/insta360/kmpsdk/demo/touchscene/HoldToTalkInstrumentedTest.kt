package com.insta360.kmpsdk.demo.touchscene

import android.Manifest
import android.media.AudioRecord
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.MainActivity
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.ui.capture.PreviewFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * View/lifecycle checks against a real emulator AudioRecord, not cloud recognition tests.
 * Each fixture enters the local demo directly, closes/removes TTS, installs a local describer
 * and an explicitly unconfigured ASR client. No test image, audio or text reaches a cloud API.
 * Synthetic audio and dispatched MotionEvents do not establish physical/TalkBack usability.
 */
@RunWith(AndroidJUnit4::class)
class HoldToTalkInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var touchDownTime = 0L

    @Test fun unifiedCameraPageHasOneFullBottomActionAndNoSeparateMicOrUpload() = fixture().use { h ->
        onMain {
            val uploadId = context.resources.getIdentifier("upload_image_btn", "id", context.packageName)
            assertTrue("Upload must not remain on the camera page", uploadId == 0 || h.activity.findViewById<View>(uploadId) == null)
            listOf("voice_mic_group", "voice_mic_btn", "voice_mic_hint").forEach { name ->
                val id = context.resources.getIdentifier(name, "id", context.packageName)
                assertTrue("Separate mic must be absent: $name", id == 0 || h.activity.findViewById<View>(id) == null)
            }
            assertEquals(context.getString(R.string.unified_action_live), h.hint.text.toString())
            assertEquals(context.getString(R.string.hold_layer_gesture_hint),
                h.activity.findViewById<TextView>(R.id.layer_gesture_hint).text.toString())
            val zone = h.activity.findViewById<View>(R.id.bottom_action_bar)
            assertEquals(zone.width, h.mic.width)
            assertEquals(zone.height, h.mic.height)
            assertTrue(h.mic.isLongClickable)
            assertEquals(context.getString(R.string.unified_action_description), h.mic.contentDescription.toString())
        }
    }

    @Test fun downWaitsForRealFiveHundredMillisecondsThenRecordsWithoutTakingPhoto() = fixture().use { h ->
        val request = onMain { field(h.fragment, "stillRequestGeneration") }
        onMain {
            send(h.mic, MotionEvent.ACTION_DOWN)
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertFalse(h.recorder.isRecording)
            assertNull(field(h.fragment, "holdToken"))
            assertNotNull(field(h.fragment, "actionGestureToken"))
        }
        SystemClock.sleep(150)
        onMain {
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertFalse(h.recorder.isRecording)
        }
        waitFor(4_000) { onMain { h.session.isRecording } }
        onMain {
            assertTrue(SystemClock.uptimeMillis() - touchDownTime >= TapHoldGesture.LONG_PRESS_MS)
            assertActualRecording(h)
            assertFalse(field(h.fragment, "micAwaitListen") as Boolean)
            assertEquals(context.getString(R.string.unified_action_listening), h.hint.text.toString())
            assertTrue(h.mic.isActivated)
            assertTrue(h.mic.isPressed)
            assertEquals(request, field(h.fragment, "stillRequestGeneration"))
            assertFalse(h.activity.findViewById<TactileMapView>(R.id.tactile_map_view).explorationEnabled)
            send(h.mic, MotionEvent.ACTION_CANCEL)
        }
        awaitRecorderReleased(h)
    }

    @Test fun shortTapFreezesThenAnotherShortTapResumesWithoutRecording() = fixture().use { h ->
        onMain {
            send(h.mic, MotionEvent.ACTION_DOWN)
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertFalse(h.recorder.isRecording)
            send(h.mic, MotionEvent.ACTION_UP)
            assertTrue(SystemClock.uptimeMillis() - touchDownTime < TapHoldGesture.LONG_PRESS_MS)
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertNull(field(h.fragment, "holdToken"))
        }
        waitFor { onMain { h.activity.findViewById<TactileMapView>(R.id.tactile_map_view).explorationEnabled } }
        onMain {
            assertEquals(context.getString(R.string.unified_action_frozen), h.hint.text.toString())
            send(h.mic, MotionEvent.ACTION_DOWN)
            send(h.mic, MotionEvent.ACTION_UP)
            assertFalse(h.activity.findViewById<TactileMapView>(R.id.tactile_map_view).explorationEnabled)
            assertFalse(h.recorder.isRecording)
            assertEquals(context.getString(R.string.unified_action_live), h.hint.text.toString())
        }
        awaitRecorderReleased(h)
    }

    @Test fun confirmedVoiceHoldWithUnderThreeHundredMillisecondsOfAudioDiscardsWithoutTap() = fixture().use { h ->
        val request = onMain { field(h.fragment, "stillRequestGeneration") }
        startHold(h)
        onMain {
            val held = SystemClock.elapsedRealtime() - (field(h.fragment, "holdStartedAtMs") as Long)
            assertTrue("This fixture must release the real recording before 300 ms: $held ms",
                held < HoldToTalkSession.MIN_HOLD_MS)
            send(h.mic, MotionEvent.ACTION_UP)
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertNull(field(h.fragment, "holdToken"))
            assertNull(field(h.fragment, "actionGestureToken"))
            assertEquals(request, field(h.fragment, "stillRequestGeneration"))
            assertFalse(h.activity.findViewById<TactileMapView>(R.id.tactile_map_view).explorationEnabled)
        }
        awaitRecorderReleased(h)
    }

    @Test fun leavingTheButtonOrReceivingCancelDiscardsRecording() = fixture().use { h ->
        listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL).forEach { cancellation ->
            startHold(h)
            onMain {
                assertActualRecording(h)
                if (cancellation == MotionEvent.ACTION_MOVE) send(h.mic, cancellation, h.mic.width + 24f)
                else send(h.mic, cancellation)
                assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
                assertEquals("IDLE", micState(h))
                assertNull(field(h.fragment, "holdToken"))
                assertFalse(h.mic.isPressed)
            }
            awaitRecorderReleased(h)
            onMain {
                val before = field(h.fragment, "micTranscriptionGeneration")
                send(h.mic, MotionEvent.ACTION_UP)
                assertEquals(before, field(h.fragment, "micTranscriptionGeneration"))
                assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            }
        }
    }

    @Test fun secondPointerCancelsAndRemainingPointerCannotResumeOrSubmit() = fixture().use { h ->
        startHold(h)
        onMain {
            assertActualRecording(h)
            sendPointers(h.mic, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), intArrayOf(0, 1))
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertNull(field(h.fragment, "holdToken"))
            sendPointers(h.mic, MotionEvent.ACTION_POINTER_UP, intArrayOf(0, 1))
        }
        awaitRecorderReleased(h)
        onMain {
            val before = field(h.fragment, "micTranscriptionGeneration")
            sendPointers(h.mic, MotionEvent.ACTION_MOVE, intArrayOf(1))
            sendPointers(h.mic, MotionEvent.ACTION_UP, intArrayOf(1))
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertEquals(before, field(h.fragment, "micTranscriptionGeneration"))
            assertFalse(h.recorder.isRecording)
        }
    }

    @Test fun backgroundingStopsActualRecordingAndDoesNotRearmOnResume() = fixture().use { h ->
        startHold(h)
        h.scenario.moveToState(Lifecycle.State.CREATED)
        awaitRecorderReleased(h)
        onMain {
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertEquals("IDLE", micState(h))
            assertNull(field(h.fragment, "holdToken"))
        }
        h.scenario.moveToState(Lifecycle.State.RESUMED)
        onMain {
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertFalse(h.recorder.isRecording)
            assertEquals(context.getString(R.string.unified_action_live), h.hint.text.toString())
        }
    }

    @Test fun simulatedGrantedPermissionWithLostPendingStateNeverStartsEitherRecorder() = fixture().use { h ->
        onMain {
            // Invoke only the result boundary, not a real Android permission dialog/recreation.
            setField(h.fragment, "micPermissionPending", false)
            permissionResult(h, granted = true)
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertEquals("IDLE", micState(h))
            assertNull(field(h.fragment, "holdToken"))
            assertFalse(field(h.fragment, "micPermissionPending") as Boolean)
            assertFalse((field(h.fragment, "voiceTurnGate") as VoiceTurnGate).isListening())
            assertFalse(h.recorder.isRecording)
            assertTrue(h.activity.findViewById<TextView>(R.id.touchscene_status).text.toString().contains("重新按住说话"))
        }
        awaitRecorderReleased(h)
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals(context.getString(R.string.unified_action_live), h.hint.text.toString())
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertFalse(h.recorder.isRecording)
            assertFalse((field(h.fragment, "voiceTurnGate") as VoiceTurnGate).isListening())
        }
    }

    @Test fun simulatedDeniedPermissionStaysIdleAndDoesNotBlockANewExplicitHold() = fixture().use { h ->
        onMain {
            // Runtime permission remains granted by the fixture; this simulates only a denied result.
            setField(h.fragment, "micPermissionPending", true)
            permissionResult(h, granted = false)
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertEquals("IDLE", micState(h))
            assertNull(field(h.fragment, "holdToken"))
            assertFalse(field(h.fragment, "micPermissionPending") as Boolean)
            assertFalse(h.recorder.isRecording)
            assertFalse((field(h.fragment, "voiceTurnGate") as VoiceTurnGate).isListening())
        }
        awaitRecorderReleased(h)
        onMain {
            assertEquals(context.getString(R.string.unified_action_live), h.hint.text.toString())
        }
        startHold(h)
        onMain {
            assertActualRecording(h)
            send(h.mic, MotionEvent.ACTION_CANCEL)
        }
        awaitRecorderReleased(h)
    }

    @Test fun processingRejectsRepeatedDownWithoutAllocatingAnotherToken() = fixture().use { h ->
        onMain {
            // Construct only the controller's processing state; no WAV or ASR is produced.
            val now = SystemClock.elapsedRealtime()
            val token = checkNotNull(h.session.begin(now))
            assertEquals(HoldToTalkSession.Decision.SUBMIT,
                h.session.release(token, now + HoldToTalkSession.MIN_HOLD_MS))
            setField(h.fragment, "holdToken", null)
            setField(h.fragment, "holdAudioBusy", true)
            setMicUiState(h, "PROCESSING")
            val transcription = field(h.fragment, "micTranscriptionGeneration")
            repeat(2) {
                send(h.mic, MotionEvent.ACTION_DOWN)
                send(h.mic, MotionEvent.ACTION_UP)
                assertEquals(HoldToTalkSession.State.PROCESSING, h.session.state)
                assertTrue(h.session.isCurrent(token))
                assertNull(field(h.fragment, "holdToken"))
                assertEquals(transcription, field(h.fragment, "micTranscriptionGeneration"))
                assertFalse(h.recorder.isRecording)
                // Independently check the PROCESSING gate, not only the audio-busy guard.
                setField(h.fragment, "holdAudioBusy", false)
            }
            assertEquals("PROCESSING", micState(h))
            assertEquals(context.getString(R.string.unified_action_processing), h.hint.text.toString())
            cancel(h)
        }
        awaitRecorderReleased(h)
    }

    @Test fun realLongReleaseEntersProcessingThenBackgroundInvalidatesItBeforeAnyAsr() = fixture().use { h ->
        val ioEntered = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        val executor = onMain { field(h.fragment, "audioIo") as ExecutorService }
        executor.execute { ioEntered.countDown(); releaseIo.await(15, TimeUnit.SECONDS) }
        try {
            assertTrue("Audio executor barrier was not installed", ioEntered.await(3, TimeUnit.SECONDS))
            startHold(h)
            SystemClock.sleep(HoldToTalkSession.MIN_HOLD_MS + 60)
            onMain {
                send(h.mic, MotionEvent.ACTION_UP)
                assertEquals(HoldToTalkSession.State.PROCESSING, h.session.state)
                assertEquals("PROCESSING", micState(h))
                assertTrue(field(h.fragment, "holdAudioBusy") as Boolean)
            }
            h.scenario.moveToState(Lifecycle.State.CREATED)
            onMain {
                assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
                assertNull(field(h.fragment, "holdToken"))
            }
        } finally { releaseIo.countDown() }
        awaitRecorderReleased(h)
        h.scenario.moveToState(Lifecycle.State.RESUMED)
        onMain {
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertEquals("IDLE", micState(h))
            assertFalse(h.recorder.isRecording)
        }
    }

    @Test fun actualThirtySecondLimitCancelsRecorderAndIgnoresTheRemainingUp() = fixture().use { h ->
        startHold(h)
        // Deliberately use the real 30-second production timer. Do not adjust its clock/state.
        waitFor(35_000) { onMain {
            h.session.state == HoldToTalkSession.State.IDLE &&
                !(field(h.fragment, "holdAudioBusy") as Boolean) && !h.recorder.isRecording
        } }
        onMain {
            assertEquals("IDLE", micState(h))
            assertEquals(context.getString(R.string.unified_action_live), h.hint.text.toString())
            assertTrue(h.activity.findViewById<TextView>(R.id.touchscene_status).text.toString().contains("重新按住说话"))
            assertNull(field(h.fragment, "holdToken"))
            val before = field(h.fragment, "micTranscriptionGeneration")
            send(h.mic, MotionEvent.ACTION_UP)
            assertEquals(before, field(h.fragment, "micTranscriptionGeneration"))
            assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
            assertFalse(h.recorder.isRecording)
        }
    }

    @Test fun queuedPointerAndCancelCleanupCannotUnlockOrStopANewerRecording() = fixture().use { h ->
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val middleEntered = CountDownLatch(1)
        val releaseMiddle = CountDownLatch(1)
        val executor = onMain { field(h.fragment, "audioIo") as ExecutorService }
        executor.execute { firstEntered.countDown(); releaseFirst.await(15, TimeUnit.SECONDS) }
        try {
            assertTrue(firstEntered.await(3, TimeUnit.SECONDS))
            startHold(h)
            onMain {
                assertActualRecording(h)
                sendPointers(h.mic, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), intArrayOf(0, 1))
            }
            // Allow cleanup #1 to run, but keep newer cleanup #2/#3 behind this barrier.
            executor.execute { middleEntered.countDown(); releaseMiddle.await(15, TimeUnit.SECONDS) }
            onMain {
                sendPointers(h.mic, MotionEvent.ACTION_POINTER_UP, intArrayOf(0, 1))
                send(h.mic, MotionEvent.ACTION_CANCEL)
                send(h.mic, MotionEvent.ACTION_DOWN)
                assertTrue(field(h.fragment, "holdAudioBusy") as Boolean)
                assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
                assertNull(field(h.fragment, "holdToken"))
            }
            releaseFirst.countDown()
            assertTrue("Oldest cleanup did not finish before the middle barrier", middleEntered.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            onMain {
                assertTrue("An old cleanup callback must not unlock while newer cleanup is queued",
                    field(h.fragment, "holdAudioBusy") as Boolean)
                send(h.mic, MotionEvent.ACTION_DOWN)
                assertEquals(HoldToTalkSession.State.IDLE, h.session.state)
                assertFalse(h.recorder.isRecording)
                assertNull(field(h.fragment, "holdToken"))
            }
        } finally { releaseFirst.countDown(); releaseMiddle.countDown() }
        awaitRecorderReleased(h)
        startHold(h)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
        onMain {
            assertActualRecording(h)
            send(h.mic, MotionEvent.ACTION_CANCEL)
        }
        awaitRecorderReleased(h)
    }

    private inner class Fixture(val scenario: ActivityScenario<MainActivity>, val activity: MainActivity,
        val fragment: PreviewFragment, val mic: View) : AutoCloseable {
        val session get() = field(fragment, "holdSession") as HoldToTalkSession
        val recorder get() = field(fragment, "manualWavRecorder") as ManualWavRecorder
        val hint: TextView get() = mic as TextView
        override fun close() {
            try {
                onMain { cancel(this) }
                awaitRecorderReleased(this)
            } finally { scenario.close() }
        }
    }

    private fun fixture(): Fixture {
        // Match the existing phone-camera fixture. Never revoke permissions for other tests.
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        scenario.onActivity {
            activity = it
            val host = it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            host.navController.navigate(R.id.action_connectionFragment_to_previewFragment,
                Bundle().apply { putBoolean("touchsceneLocalDemo", true) })
            host.childFragmentManager.executePendingTransactions()
            val fragment = checkNotNull(previewOrNull(activity))
            (field(fragment, "speechOutput") as? AndroidSpeechOutput)?.close()
            setField(fragment, "speechOutput", null)
            val coordinator = field(fragment, "touchSceneCoordinator") as TouchSceneCoordinator
            TouchSceneCoordinator::class.java.getDeclaredField("describer").apply { isAccessible = true }
                .set(coordinator, LocalContourSceneDescriber())
            setField(fragment, "remoteAsrTranscriber", RemoteAsrTranscriber(context, endpoint = "", apiKey = "", model = ""))
        }
        try {
            waitFor { onMain { previewOrNull(activity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true &&
                activity.findViewById<View>(R.id.toggle_describe_live_btn)?.let { it.isShown && it.height > 0 } == true } }
            return onMain {
                val fragment = checkNotNull(previewOrNull(activity))
                (field(fragment, "speechOutput") as? AndroidSpeechOutput)?.close()
                setField(fragment, "speechOutput", null)
                // Even an erroneous submission is rejected before network code or Base64 encoding.
                setField(fragment, "remoteAsrTranscriber", RemoteAsrTranscriber(context, endpoint = "", apiKey = "", model = ""))
                Fixture(scenario, activity, fragment, activity.findViewById(R.id.toggle_describe_live_btn))
            }
        } catch (error: Throwable) { scenario.close(); throw error }
    }

    private fun previewOrNull(activity: MainActivity): PreviewFragment? =
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)
            ?.childFragmentManager?.primaryNavigationFragment as? PreviewFragment

    private fun assertActualRecording(h: Fixture) {
        assertEquals(HoldToTalkSession.State.RECORDING, h.session.state)
        assertEquals("LISTENING", micState(h))
        assertNotNull(field(h.fragment, "holdToken"))
        assertTrue(h.recorder.isRecording)
        val native = ManualWavRecorder::class.java.getDeclaredField("audioRecord")
            .apply { isAccessible = true }.get(h.recorder) as AudioRecord
        assertEquals(AudioRecord.STATE_INITIALIZED, native.state)
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, native.recordingState)
    }

    private fun startHold(h: Fixture) {
        onMain { send(h.mic, MotionEvent.ACTION_DOWN) }
        waitFor(4_000) { onMain { h.session.isRecording && h.recorder.isRecording } }
        onMain { assertActualRecording(h) }
    }

    private fun cancel(h: Fixture) {
        PreviewFragment::class.java.getDeclaredMethod("cancelMicSession", String::class.java)
            .apply { isAccessible = true }.invoke(h.fragment, *arrayOf<Any?>(null))
    }

    private fun permissionResult(h: Fixture, granted: Boolean) {
        PreviewFragment::class.java.getDeclaredMethod("onMicrophonePermissionResult", Boolean::class.javaPrimitiveType!!)
            .apply { isAccessible = true }.invoke(h.fragment, granted)
    }

    private fun micState(h: Fixture): String = (field(h.fragment, "micUiState") as Enum<*>).name

    private fun setMicUiState(h: Fixture, name: String) {
        val type = PreviewFragment::class.java.getDeclaredField("micUiState").type
        val state = type.enumConstants!!.first { (it as Enum<*>).name == name }
        PreviewFragment::class.java.getDeclaredMethod("updateMicUi", type)
            .apply { isAccessible = true }.invoke(h.fragment, state)
    }

    private fun field(fragment: PreviewFragment, name: String): Any? = PreviewFragment::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(fragment)

    private fun setField(fragment: PreviewFragment, name: String, value: Any?) {
        PreviewFragment::class.java.getDeclaredField(name).apply { isAccessible = true }.set(fragment, value)
    }

    private fun awaitRecorderReleased(h: Fixture) = waitFor(8_000) { onMain {
        !(field(h.fragment, "holdAudioBusy") as Boolean) && !h.recorder.isRecording
    } }

    private fun send(view: View, action: Int, x: Float = view.width / 2f) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = now
        val event = MotionEvent.obtain(touchDownTime.takeIf { it > 0 } ?: now, now, action, x, view.height / 2f, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun sendPointers(view: View, action: Int, ids: IntArray) {
        val now = SystemClock.uptimeMillis()
        val properties = ids.map { id -> MotionEvent.PointerProperties().apply {
            this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = ids.mapIndexed { index, _ -> MotionEvent.PointerCoords().apply {
            x = view.width / 2f + index * 4f; y = view.height / 2f; pressure = 1f; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(touchDownTime.takeIf { it > 0 } ?: now, now, action, ids.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting for hold-to-talk state", condition())
    }
}
