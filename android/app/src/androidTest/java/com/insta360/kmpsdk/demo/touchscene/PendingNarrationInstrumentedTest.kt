package com.insta360.kmpsdk.demo.touchscene

import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.MainActivity
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.ui.capture.PreviewFragment
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scheduling-only tests on the real local-demo Preview. Speech is detached and closed before
 * injecting pending text; no description request, microphone, camera capture or cloud audio runs.
 * A narration_started trace/hint flag is not evidence of an actual TTS call or audible playback.
 */
@RunWith(AndroidJUnit4::class)
class PendingNarrationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun firstEligibleNarrationConsumesHintOnlyOnceAndLaterNarrationsDoNotResetIt() {
        withPreview { fixture -> main {
            val preview = fixture.preview
            assertFalse(hintAnnounced(preview))
            queue(preview, "第一张图的说明")
            flush(preview)
            assertNull(pending(preview))
            assertTrue(hintAnnounced(preview))
            assertEquals(1, narrationCount(preview))
            flush(preview)
            assertEquals(1, narrationCount(preview))

            nextDescription(preview)
            queue(preview, "下一张图的说明")
            flush(preview)
            assertTrue(hintAnnounced(preview))
            assertEquals(2, narrationCount(preview))
            assertNull(pending(preview))
        } }
    }

    @Test fun backgroundDiscardsPendingWithoutResettingHintOrReplayingOnResume() {
        withPreview { fixture ->
            main {
                queue(fixture.preview, "已排程的第一句")
                flush(fixture.preview)
                nextDescription(fixture.preview)
                queue(fixture.preview, "不应在后台或恢复后补播")
            }
            fixture.scenario.moveToState(Lifecycle.State.CREATED)
            main {
                assertTrue(hintAnnounced(fixture.preview))
                assertNull(pending(fixture.preview))
                assertEquals(1, narrationCount(fixture.preview))
            }
            fixture.scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor { main { !flag(fixture.preview, "holdAudioBusy") } }
            main {
                assertTrue(hintAnnounced(fixture.preview))
                assertNull(pending(fixture.preview))
                flush(fixture.preview)
                assertEquals(1, narrationCount(fixture.preview))
            }
        }
    }

    @Test fun leavingAndReenteringPreviewCreatesANewPageHintBudget() {
        withPreview { fixture ->
            val previous = fixture.preview
            main {
                queue(previous, "旧页面第一句")
                flush(previous)
                assertTrue(hintAnnounced(previous))
                assertTrue(navHost(fixture.activity).navController.popBackStack())
            }
            instrumentation.waitForIdleSync()
            fixture.preview = openLocalPreview(fixture.activity)
            main {
                assertNotSame(previous, fixture.preview)
                assertFalse(hintAnnounced(fixture.preview))
                assertNull(pending(fixture.preview))
                queue(fixture.preview, "新页面第一句")
                flush(fixture.preview)
                assertTrue(hintAnnounced(fixture.preview))
                assertEquals(1, narrationCount(fixture.preview))
            }
        }
    }

    @Test fun recordingKeepsPendingUntilCancelledThenExplicitFlushConsumesIt() {
        withPreview { fixture -> main {
            val preview = fixture.preview
            val session = holdSession(preview)
            checkNotNull(session.begin(1_000))
            val original = queue(preview, "录音结束后才可排程")
            flush(preview)
            assertSame(original, pending(preview))
            assertFalse(hintAnnounced(preview))
            assertEquals(0, narrationCount(preview))

            session.cancel()
            flush(preview)
            assertNull(pending(preview))
            assertTrue(hintAnnounced(preview))
            assertEquals(1, narrationCount(preview))
        } }
    }

    @Test fun processingKeepsPendingUntilCurrentFinishThenExplicitFlushConsumesIt() {
        withPreview { fixture -> main {
            val preview = fixture.preview
            val session = holdSession(preview)
            val token = checkNotNull(session.begin(1_000))
            assertEquals(HoldToTalkSession.Decision.SUBMIT, session.release(token, 1_500))
            val original = queue(preview, "识别命令期间应保留")
            flush(preview)
            assertSame(original, pending(preview))
            assertFalse(hintAnnounced(preview))
            assertFalse(session.finish(token - 1))
            flush(preview)
            assertSame(original, pending(preview))

            assertTrue(session.finish(token))
            flush(preview)
            assertNull(pending(preview))
            assertTrue(hintAnnounced(preview))
            assertEquals(1, narrationCount(preview))
        } }
    }

    @Test fun staleDescriptionGenerationIsDiscardedWithoutConsumingFirstHint() {
        withPreview { fixture -> main {
            val preview = fixture.preview
            val current = nextDescription(preview)
            queue(preview, "旧请求结果", generation = current - 1)
            flush(preview)
            assertNull(pending(preview))
            assertFalse(hintAnnounced(preview))
            assertEquals(0, narrationCount(preview))

            queue(preview, "当前请求结果")
            flush(preview)
            assertTrue(hintAnnounced(preview))
            assertEquals(1, narrationCount(preview))
        } }
    }

    @Test fun missingEngineDoneCallbackClearsStaleBusyTurnBeforeSchedulingPendingNarration() {
        withPreview { fixture -> main {
            val preview = fixture.preview
            // Closed output cannot synthesize/play. This simulates only a stale OEM active ID,
            // not an actual OEM callback failure or audible playback. Explicitly flush below
            // because this detached output intentionally has no Preview completion callback.
            val closedSpeech = AndroidSpeechOutput(fixture.activity).apply { close() }
            @Suppress("UNCHECKED_CAST")
            val activeId = AndroidSpeechOutput::class.java.getDeclaredField("activeUtteranceId")
                .apply { isAccessible = true }.get(closedSpeech) as AtomicReference<String?>
            activeId.set("missing-done")
            field("speechOutput").set(preview, closedSpeech)
            field("lastSpeechStartedAtMs").setLong(preview, SystemClock.elapsedRealtime() - 1_000L)
            assertTrue(closedSpeech.isSpeaking())
            assertFalse(closedSpeech.isEngineSpeaking())
            val original = queue(preview, "旧播报结束后应排程当前说明")

            flush(preview)
            assertNull(activeId.get())
            assertFalse(closedSpeech.isSpeaking())
            assertSame(original, pending(preview))
            assertFalse(hintAnnounced(preview))
            assertEquals(0, narrationCount(preview))

            flush(preview)
            assertNull(pending(preview))
            assertTrue(hintAnnounced(preview))
            assertEquals(1, narrationCount(preview))
            field("speechOutput").set(preview, null)
        } }
    }

    @Test fun mismatchedMapVersionIsDiscardedWithoutConsumingFirstHint() {
        withPreview { fixture -> main {
            val preview = fixture.preview
            val wrongVersion = (mapVersion(preview) ?: 0L) + 1
            queue(preview, "不同图的结果", version = wrongVersion)
            flush(preview)
            assertNull(pending(preview))
            assertFalse(hintAnnounced(preview))
            assertEquals(0, narrationCount(preview))

            queue(preview, "当前图的结果")
            flush(preview)
            assertTrue(hintAnnounced(preview))
            assertEquals(1, narrationCount(preview))
        } }
    }

    @Test fun audioCleanupPhotoCaptureAndRecordingFeedbackRetainPendingUntilEachBlockerClears() {
        withPreview { fixture -> main {
            val preview = fixture.preview
            for (blocker in listOf("holdAudioBusy", "capturePhotoInFlight", "recordingFeedbackPending")) {
                val previousHint = hintAnnounced(preview)
                val previousStarts = narrationCount(preview)
                field(blocker).setBoolean(preview, true)
                val original = queue(preview, "等待$blocker")
                flush(preview)
                assertSame(blocker, original, pending(preview))
                assertEquals(previousHint, hintAnnounced(preview))
                assertEquals(previousStarts, narrationCount(preview))
                field(blocker).setBoolean(preview, false)
                flush(preview)
                assertNull(pending(preview))
                assertEquals(previousStarts + 1, narrationCount(preview))
                assertTrue(hintAnnounced(preview))
            }
        } }
    }

    private class Fixture(val scenario: ActivityScenario<MainActivity>, val activity: MainActivity,
        var preview: PreviewFragment)

    private fun withPreview(test: (Fixture) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val preview = openLocalPreview(activity)
            test(Fixture(scenario, activity, preview))
        } finally { scenario.close() }
    }

    private fun openLocalPreview(activity: MainActivity): PreviewFragment {
        main {
            navHost(activity).navController.navigate(R.id.previewFragment,
                Bundle().apply { putBoolean("touchsceneLocalDemo", true) })
        }
        instrumentation.waitForIdleSync()
        val preview = main {
            val fragment = navHost(activity).childFragmentManager.primaryNavigationFragment as PreviewFragment
            assertTrue(fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            val originalSpeech = field("speechOutput").get(fragment) as? AndroidSpeechOutput
            field("speechOutput").set(fragment, null)
            originalSpeech?.close()
            fragment
        }
        // Close emits one ordinary end callback. Drain it before adding any test pending item.
        instrumentation.waitForIdleSync()
        return preview
    }

    private fun navHost(activity: MainActivity): NavHostFragment =
        activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment

    private fun field(name: String) = PreviewFragment::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun pending(preview: PreviewFragment): Any? = field("pendingNarration").get(preview)
    private fun hintAnnounced(preview: PreviewFragment): Boolean = flag(preview, "layerHintAnnounced")
    private fun flag(preview: PreviewFragment, name: String): Boolean = field(name).getBoolean(preview)
    private fun holdSession(preview: PreviewFragment) = field("holdSession").get(preview) as HoldToTalkSession
    private fun mapVersion(preview: PreviewFragment): Long? =
        (field("touchSceneCoordinator").get(preview) as? TouchSceneCoordinator)?.activeMapVersion()

    private fun nextDescription(preview: PreviewFragment): Long =
        (field("descriptionGeneration").getLong(preview) + 1).also { field("descriptionGeneration").setLong(preview, it) }

    private fun queue(preview: PreviewFragment, text: String,
        generation: Long = field("descriptionGeneration").getLong(preview),
        version: Long? = mapVersion(preview)): Any {
        val type = PreviewFragment::class.java.declaredClasses.single { it.simpleName == "PendingNarration" }
        return type.declaredConstructors.single { it.parameterTypes.size == 3 }
            .apply { isAccessible = true }.newInstance(text, generation, version).also {
                field("pendingNarration").set(preview, it)
            }
    }

    private fun flush(preview: PreviewFragment) {
        PreviewFragment::class.java.getDeclaredMethod("flushPendingNarration").apply { isAccessible = true }.invoke(preview)
    }

    private fun narrationCount(preview: PreviewFragment): Int =
        (field("trace").get(preview) as TouchSceneTrace).snapshot()
            .count { it.stage == "description" && it.event == "narration_started" }

    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        assertTrue("Timed out waiting for local scheduling cleanup", condition())
    }
}
