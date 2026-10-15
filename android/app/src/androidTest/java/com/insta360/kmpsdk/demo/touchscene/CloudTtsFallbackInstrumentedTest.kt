package com.insta360.kmpsdk.demo.touchscene

import android.media.MediaPlayer
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.BuildConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudTtsFallbackInstrumentedTest {
    private class Synthesis : CloudSpeechSynthesis {
        val callbacks = mutableListOf<(Result<String>) -> Unit>()
        override fun synthesize(text: String, onResult: (Result<String>) -> Unit) { callbacks += onResult }
        override fun cancel() = Unit // Intentionally deliver stale results after cancellation.
        override fun close() = Unit
    }
    private class Playback : CloudSpeechPlayback {
        lateinit var prepared: () -> Unit
        lateinit var completed: () -> Unit
        var starts = 0
        var releases = 0
        override fun prepare(url: String, onPrepared: () -> Unit, onComplete: () -> Unit, onError: () -> Unit) {
            prepared = onPrepared; completed = onComplete
        }
        override fun start() { starts++ }
        override fun release() { releases++ }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun preparationIsBusyAndStopRejectsLateSynthesis() {
        val synthesis = Synthesis()
        val players = mutableListOf<Playback>()
        val events = mutableListOf<Boolean>()
        val fallback = onMain { CloudTtsFallback(context, { events += it }, {}, synthesis,
            { Playback().also { players += it } }) }
        try {
            onMain { fallback.speak("测试"); assertTrue(fallback.isSpeaking); fallback.stop() }
            synthesis.callbacks[0](Result.success("fixture.wav"))
            instrumentation.waitForIdleSync()
            onMain {
                assertFalse(fallback.isSpeaking)
                assertTrue(players.isEmpty())
                assertEquals(listOf(true, false), events)
            }
        } finally { onMain { fallback.close() } }
    }

    @Test fun closeRejectsLatePlayerPreparation() {
        val synthesis = Synthesis()
        val player = Playback()
        val fallback = onMain { CloudTtsFallback(context, {}, {}, synthesis, { player }) }
        onMain { fallback.speak("测试") }
        synthesis.callbacks[0](Result.success("fixture.wav"))
        instrumentation.waitForIdleSync()
        onMain {
            fallback.close()
            player.prepared()
            player.completed()
            assertEquals(0, player.starts)
            assertTrue(player.releases > 0)
            assertFalse(fallback.isSpeaking)
        }
    }

    @Test fun replacingSpeechHasNoFalseCompletionAndOldCompletionCannotEndNewTurn() {
        val synthesis = Synthesis()
        val players = mutableListOf<Playback>()
        val events = mutableListOf<Boolean>()
        val fallback = onMain { CloudTtsFallback(context, { events += it }, {}, synthesis,
            { Playback().also { players += it } }) }
        try {
            onMain { fallback.speak("第一句") }
            synthesis.callbacks[0](Result.success("fixture-1.wav"))
            instrumentation.waitForIdleSync()
            onMain { players[0].prepared(); fallback.speak("第二句"); players[0].completed() }
            onMain { assertTrue(fallback.isSpeaking); assertFalse(events.contains(false)) }
            synthesis.callbacks[1](Result.success("fixture-2.wav"))
            instrumentation.waitForIdleSync()
            onMain {
                players[1].prepared(); players[1].completed()
                assertFalse(fallback.isSpeaking)
                assertEquals(1, events.count { !it })
                assertEquals(1, players[0].starts)
                assertEquals(1, players[1].starts)
            }
        } finally { onMain { fallback.close() } }
    }

    @Test fun synthesisFailureClearsBusyAndReportsFailure() {
        val synthesis = Synthesis()
        val failures = mutableListOf<String>()
        val fallback = onMain { CloudTtsFallback(context, {}, { failures += it }, synthesis) }
        try {
            onMain { fallback.speak("测试") }
            synthesis.callbacks[0](Result.failure(IllegalStateException("fixture failure")))
            instrumentation.waitForIdleSync()
            onMain { assertFalse(fallback.isSpeaking); assertEquals(1, failures.size) }
        } finally { onMain { fallback.close() } }
    }

    @Test fun realBailianFixtureReachesMediaPlayerPlaybackAndCompletion() {
        assumeTrue(BuildConfig.TOUCHSCENE_AI_KEY.isNotBlank())
        val failures = mutableListOf<String>()
        val fallback = onMain { CloudTtsFallback(context, {}, { failures += it }) }
        var playerStarted = false
        try {
            onMain { fallback.speak("语音测试。") }
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (SystemClock.uptimeMillis() < deadline) {
                val busy = onMain {
                    val playback = CloudTtsFallback::class.java.getDeclaredField("playback")
                        .apply { isAccessible = true }.get(fallback)
                    if (playback != null) {
                        val player = playback.javaClass.getDeclaredField("player")
                            .apply { isAccessible = true }.get(playback) as MediaPlayer
                        if (runCatching { player.isPlaying }.getOrDefault(false)) playerStarted = true
                    }
                    fallback.isSpeaking
                }
                if (!busy) break
                SystemClock.sleep(50)
            }
            onMain { assertTrue(failures.toString(), failures.isEmpty()); assertFalse(fallback.isSpeaking) }
            assertTrue("The actual cloud audio never entered MediaPlayer playback", playerStarted)
        } finally { onMain { fallback.close() } }
    }

    private fun <T> onMain(value: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = value() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
