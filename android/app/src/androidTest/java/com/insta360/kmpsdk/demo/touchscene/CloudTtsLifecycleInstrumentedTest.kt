package com.insta360.kmpsdk.demo.touchscene

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic fake synthesis/playback. No cloud request, device microphone or sound is used. */
@RunWith(AndroidJUnit4::class)
class CloudTtsLifecycleInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val audioUrl = "https://fixture.oss-cn-beijing.aliyuncs.com/test.wav?signature=fixture"

    @Test fun preparingIsBusyAndStopRejectsLateAudioWithoutStartingPlayback() {
        val synthesis = FakeSynthesis()
        val players = ArrayList<FakePlayback>()
        val states = ArrayList<Boolean>()
        lateinit var speech: CloudTtsFallback
        instrumentation.runOnMainSync {
            speech = CloudTtsFallback(instrumentation.targetContext, { states.add(it) }, { fail(it) }, synthesis,
                { FakePlayback().also(players::add) })
            speech.speak("测试说明")
            assertTrue(speech.isSpeaking)
            assertEquals(listOf(true), states)
            speech.stop()
            assertFalse(speech.isSpeaking)
            assertTrue(synthesis.cancelCount > 0)
            synthesis.results[0](Result.success(audioUrl))
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue(players.isEmpty())
            assertEquals(listOf(true, false), states)
            speech.close()
        }
    }

    @Test fun closeRejectsLateSynthesisAndAnyLaterSpeak() {
        val synthesis = FakeSynthesis()
        val players = ArrayList<FakePlayback>()
        lateinit var speech: CloudTtsFallback
        instrumentation.runOnMainSync {
            speech = CloudTtsFallback(instrumentation.targetContext, {}, { fail(it) }, synthesis,
                { FakePlayback().also(players::add) })
            speech.speak("测试说明")
            speech.close()
            assertFalse(speech.isSpeaking)
            assertTrue(synthesis.closed)
            synthesis.results[0](Result.success(audioUrl))
            speech.speak("不得迟到播放")
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertTrue(players.isEmpty()); assertEquals(1, synthesis.results.size) }
    }

    @Test fun oldPreparedAndCompleteCallbacksDoNotStartOldAudioOrClearNewTurn() {
        val synthesis = FakeSynthesis()
        val players = ArrayList<FakePlayback>()
        val states = ArrayList<Boolean>()
        lateinit var speech: CloudTtsFallback
        instrumentation.runOnMainSync {
            speech = CloudTtsFallback(instrumentation.targetContext, { states.add(it) }, { fail(it) }, synthesis,
                { FakePlayback().also(players::add) })
            speech.speak("第一句")
            synthesis.results[0](Result.success(audioUrl))
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(1, players.size)
            speech.speak("第二句")
            synthesis.results[1](Result.success(audioUrl))
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(2, players.size)
            players[0].onPrepared()
            players[0].onComplete()
            assertEquals(0, players[0].startCount)
            assertTrue(speech.isSpeaking)
            assertEquals(0, players[1].releaseCount)
            players[1].onPrepared()
            assertEquals(1, players[1].startCount)
            assertTrue(speech.isSpeaking)
            assertEquals(listOf(true, true), states)
            players[1].onComplete()
            assertFalse(speech.isSpeaking)
            speech.close()
        }
    }

    private class FakeSynthesis : CloudSpeechSynthesis {
        val results = ArrayList<(Result<String>) -> Unit>()
        var cancelCount = 0
        var closed = false
        override fun synthesize(text: String, onResult: (Result<String>) -> Unit) { results.add(onResult) }
        override fun cancel() { cancelCount++ }
        override fun close() { closed = true; cancel() }
    }
    private class FakePlayback : CloudSpeechPlayback {
        lateinit var onPrepared: () -> Unit
        lateinit var onComplete: () -> Unit
        var startCount = 0
        var releaseCount = 0
        override fun prepare(url: String, onPrepared: () -> Unit, onComplete: () -> Unit, onError: () -> Unit) {
            this.onPrepared = onPrepared; this.onComplete = onComplete
        }
        override fun start() { startCount++ }
        override fun release() { releaseCount++ }
    }
}
