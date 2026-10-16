package com.insta360.kmpsdk.demo.touchscene

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.raw.Yuv420Frame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic views only: no Activity/camera/microphone/network or user photo. */
@RunWith(AndroidJUnit4::class)
class PortraitContourInstrumentedTest {
    @Test fun portraitEdgeHitsAboveAndBelowLegacyGrid() = verifyPortrait(TactileDebugMode.EDGE)
    @Test fun portraitContourHitsAboveAndBelowLegacyGrid() = verifyPortrait(TactileDebugMode.BINARY)

    private fun verifyPortrait(mode: TactileDebugMode) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (sourceY in listOf(24, 216)) {
                val view = makeView(mode, boundary = BooleanArray(120 * 240).also { it[sourceY * 120 + 60] = true })
                val content = TouchMapper.centerFit(600, 1000, 120, 240)
                // Aim at pixel centers, not floating-point pixel boundaries.
                val viewX = content.left + (60.5f / 120f) * content.width
                val viewY = content.top + ((sourceY + 0.5f) / 240f) * content.height
                assertEquals(GridPoint(60, sourceY), TouchMapper.map(viewX, viewY, content, 120, 240))
                val legacy = TouchMapper.centerFit(600, 1000, 64, 48)
                assertNull(TouchMapper.map(viewX, viewY, legacy))
                var sampled = -1
                view.onTouchDebug = { _, _, _, _, value -> sampled = value }
                touch(view, viewX, viewY)
                assertEquals("Visible $mode at y=$viewY must reach haptic render(value)", 1, sampled)
            }
        }
    }

    @Test fun portraitLetterboxIsSilent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = makeView(TactileDebugMode.EDGE, BooleanArray(120 * 240) { true })
            var sampled = -1
            view.onTouchDebug = { _, _, _, _, value -> sampled = value }
            touch(view, 10f, 100f) // Image is x=50..550, not the full View.
            assertEquals(0, sampled)
        }
    }

    @Test fun originalImageIsSilentEvenWhenEveryPixelIsBright() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // White RGB and white analysis pixels must not become a tactile shape.
            val view = makeView(TactileDebugMode.ORIGINAL, BooleanArray(120 * 240) { true }, gray = 255)
            var sampled = -1
            view.onTouchDebug = { _, _, _, _, value -> sampled = value }
            for ((x, y) in listOf(300f to 100f, 300f to 500f, 300f to 900f, 10f to 500f)) {
                val now = SystemClock.uptimeMillis()
                val action = if (sampled == -1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
                val event = MotionEvent.obtain(now, now, action, x, y, 0)
                try { view.onTouchEvent(event) } finally { event.recycle() }
                assertEquals("Original image must be silent at ($x,$y)", 0, sampled)
            }
            val now = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 10f, 500f, 0)
            try { view.onTouchEvent(up) } finally { up.recycle() }
        }
    }

    @Test fun photoPerimeterDoesNotRenderOrVibrateAsContour() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val w = 120; val h = 240
            val boundary = BooleanArray(w * h) { i -> i % w == 0 || i % w == w - 1 || i / w == 0 || i / w == h - 1 }
            val view = makeView(TactileDebugMode.BINARY, boundary)
            var sampled = -1
            view.onTouchDebug = { _, _, _, _, value -> sampled = value }
            touch(view, 300f, 5f)
            assertEquals(0, sampled)
        }
    }

    @Test fun optimizedDilationIsPixelIdenticalAndReportsLocalComputeTimes() {
        val w = 384; val h = 512; val radius = 12
        val boundary = BooleanArray(w * h) { i -> i % 37 == 0 }
        repeat(2) {
            ContourHapticMask.build(boundary, w, h, radius)
            ContourBandRasterizer.build(boundary, w, h, radius)
        }
        val originalTimes = LongArray(5)
        val optimizedTimes = LongArray(5)
        repeat(5) { iteration ->
            var original = BooleanArray(0)
            var optimized = BooleanArray(0)
            val oldRun = {
                val start = SystemClock.elapsedRealtimeNanos()
                original = ContourHapticMask.build(boundary, w, h, radius)
                originalTimes[iteration] = SystemClock.elapsedRealtimeNanos() - start
            }
            val newRun = {
                val start = SystemClock.elapsedRealtimeNanos()
                optimized = ContourBandRasterizer.build(boundary, w, h, radius)
                optimizedTimes[iteration] = SystemClock.elapsedRealtimeNanos() - start
            }
            if (iteration % 2 == 0) { oldRun(); newRun() } else { newRun(); oldRun() }
            assertArrayEquals(original, optimized)
        }
        android.util.Log.i("TouchSceneContourBench", "warmup=2 samples=5 original_median_us=${originalTimes.sorted()[2]/1000}, optimized_median_us=${optimizedTimes.sorted()[2]/1000}")
    }

    private fun makeView(mode: TactileDebugMode, boundary: BooleanArray, gray: Int = 0): TactileMapView {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val w = 120; val h = 240
        val result = CannyTactileProcessor(CannyConfig(analysisWidth = w, analysisHeight = h))
            .processDetailed(GrayFrame(w, h, 1L, ByteArray(w*h)))
            .copy(boundary = boundary, cannyEdges = boundary, gray = IntArray(w*h) { gray })
        val source = Yuv420Frame(w, h, 1L, 1L, ByteArray(w*h) { gray.toByte() },
            ByteArray(w*h/4) { 128.toByte() }, ByteArray(w*h/4) { 128.toByte() })
        return TactileMapView(context).apply {
            layout(0, 0, 600, 1000)
            submitMap(result.map)
            submitDebugSnapshot(TactileDebugSnapshot(source, result, PhotoBinaryLayer(64, 48, ByteArray(64*48))))
            if (mode != TactileDebugMode.ORIGINAL) cycleDebugMode()
            if (mode == TactileDebugMode.BINARY) cycleDebugMode()
            explorationEnabled = true
        }
    }

    private fun touch(view: TactileMapView, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, x, y, 0)
        try { view.onTouchEvent(down); view.onTouchEvent(up) }
        finally { down.recycle(); up.recycle() }
    }
}
