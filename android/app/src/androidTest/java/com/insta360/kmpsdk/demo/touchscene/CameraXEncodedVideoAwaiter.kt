package com.insta360.kmpsdk.demo.touchscene

import android.os.Looper
import android.os.SystemClock
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.phonecamera.PhoneCameraGateway
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Test-only bridge for the pinned CameraX 1.4.2 dependency. VideoRecordEvent.Start does not
 * guarantee an encoded keyframe. Wait for at least two written video timestamps and a muxer
 * video track, not elapsed wall time or audio-only bytes. No production callback/API changes.
 */
internal object CameraXEncodedVideoAwaiter {
    private data class Sample(val hasVideoTrack: Boolean, val firstUs: Long, val latestUs: Long, val bytes: Long) {
        val hasTwoVideoSamples: Boolean get() = hasVideoTrack && bytes > 0 &&
            firstUs != Long.MAX_VALUE && latestUs != Long.MAX_VALUE && latestUs > firstUs
    }

    fun awaitVideoSamples(camera: PhoneCameraGateway, timeoutMs: Long = 20_000L) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Never wait for encoding on the main thread" }
        val recorderRef = AtomicReference<Recorder>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val capture = PhoneCameraGateway::class.java.getDeclaredField("videoCapture")
                .apply { isAccessible = true }.get(camera) as? VideoCapture<*>
            recorderRef.set(checkNotNull(capture?.output as? Recorder) { "No active CameraX Recorder" })
        }
        val recorder = recorderRef.get()
        fun field(name: String) = Recorder::class.java.getDeclaredField(name).apply { isAccessible = true }
        val sequential = field("mSequentialExecutor").get(recorder) as Executor
        val videoTrack = field("mVideoTrackIndex")
        val firstVideo = field("mFirstRecordingVideoDataTimeUs")
        val latestVideo = field("mPreviousRecordingVideoDataTimeUs")
        val recordedBytes = field("mRecordingBytes")
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastSample: Sample? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val sample = AtomicReference<Sample?>()
            val failure = AtomicReference<Throwable?>()
            val inspected = CountDownLatch(1)
            // These fields are owned by Recorder's sequential executor. Inspect on that same
            // executor; a loop of synchronous main-thread reads is neither needed nor safe.
            sequential.execute {
                try {
                    sample.set(Sample(videoTrack.get(recorder) != null,
                        firstVideo.getLong(recorder), latestVideo.getLong(recorder), recordedBytes.getLong(recorder)))
                } catch (error: Throwable) { failure.set(error) }
                finally { inspected.countDown() }
            }
            assertTrue("Recorder did not answer within the encoded-video deadline",
                inspected.await((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L), TimeUnit.MILLISECONDS))
            failure.get()?.let { throw AssertionError("Unable to inspect pinned CameraX 1.4.2 video samples", it) }
            lastSample = sample.get()
            if (lastSample?.hasTwoVideoSamples == true) return
            SystemClock.sleep(minOf(50L, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)))
        }
        fail("No two encoded video samples within ${timeoutMs}ms: $lastSample")
    }
}
