package com.insta360.kmpsdk.demo.touchscene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.insta360.kmpsdk.demo.raw.toYuv420Frame
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import timber.log.Timber

/** Real packaged JNI, synthetic fixtures only: no camera, microphone, speech or network. */
@RunWith(AndroidJUnit4::class)
class OpenCvNativeInstrumentedTest {
    private val fallbackWarnings = CopyOnWriteArrayList<String>()
    private val capture = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (message.contains("OpenCV tactile processing failed") || message.contains("falling back to Canny")) {
                fallbackWarnings += message
            }
        }
    }

    @Before fun loadRealJniAndCaptureFallbackWarnings() {
        Timber.plant(capture)
        // A missing transitive libc++ dependency must fail here, not pass via Kotlin fallback.
        System.loadLibrary("opencv_java4")
        assertTrue(Core.getBuildInformation().contains("OpenCV"))
        Core.setRNGSeed(RNG_SEED)
    }

    @After fun forbidFallbackAndDetachLogger() {
        Timber.uproot(capture)
        assertTrue("Native processing silently fell back: $fallbackWarnings", fallbackWarnings.isEmpty())
    }

    @Test fun directJniProducesNonemptyBinaryLayersWithoutChangingOriginalColors() {
        val original = fixture(320, 240)
        val before = pixels(original)
        try {
            val edge = OpenCvImageProcessor.processEdge(original)
            Core.setRNGSeed(RNG_SEED)
            val outline = OpenCvImageProcessor.processOutline(original)
            try {
                assertBinaryNonempty(edge, 320, 240)
                assertBinaryNonempty(outline, 320, 240)
                assertArrayEquals("JNI must not replace/mutate original RGB input", before, pixels(original))
                assertTrue("Original fixture must be genuinely colored", before.any { Color.red(it) != Color.blue(it) })
                Core.setRNGSeed(RNG_SEED)
                val repeated = OpenCvImageProcessor.processOutline(original)
                try { assertArrayEquals("Seeded GrabCut must be repeatable", pixels(outline), pixels(repeated)) }
                finally { repeated.recycle() }
            } finally { edge.recycle(); outline.recycle() }
        } finally { original.recycle() }
    }

    @Test fun wrapperMatchesDirectJniForSameColorFrameAcrossAllRotationsAndDownscaling() {
        val original = fixture(320, 240)
        try {
            val source = original.toYuv420Frame(101L, 202L)
            for (degrees in listOf(0, 90, 180, 270)) {
                val frame = source.rotated(degrees)
                val originalY = frame.y.copyOf()
                val originalU = frame.u.copyOf()
                val originalV = frame.v.copyOf()
                val width = if (degrees == 90 || degrees == 270) 120 else 160
                val height = if (degrees == 90 || degrees == 270) 160 else 120
                val rgb = frame.toRgbDebugBitmap(width, height)
                val expectedEdge = OpenCvImageProcessor.processEdge(rgb)
                Core.setRNGSeed(RNG_SEED)
                val expectedOutline = OpenCvImageProcessor.processOutline(rgb)
                try {
                    assertBinaryNonempty(expectedEdge, width, height)
                    assertBinaryNonempty(expectedOutline, width, height)
                    val rgbPixels = pixels(rgb)
                    assertTrue("Rotated source is RGB, not a mask", rgbPixels.any { Color.red(it) - Color.blue(it) > 100 })
                    Core.setRNGSeed(RNG_SEED)
                    val processed = OpenCvTactileFrameProcessor(openCvLongEdge = 160).process(frame)
                    val result = processed.result
                    assertEquals(width, result.width)
                    assertEquals(height, result.height)
                    assertFalse(result.usedThresholdFallback)
                    assertArrayEquals("Wrapper edge must be the actual JNI result at $degrees degrees",
                        mask(expectedEdge), result.cannyEdges)
                    assertArrayEquals("Wrapper outline must be the actual JNI result at $degrees degrees",
                        mask(expectedOutline), result.subject)
                    assertArrayEquals(rgbPixels.map { luminance(it) }.toIntArray(), result.gray)
                    assertTrue(result.boundary.any { it })
                    assertTrue(result.map.copyCells().any { it == TactileCell.BOUNDARY.code })
                    assertTrue(processed.photoBinary.cells.any { it.toInt() == 1 })
                    assertTrue(processed.photoBinary.cells.any { it.toInt() == 0 })
                    assertEquals(202L, result.map.sourceTimestampMs)
                    assertArrayEquals(originalY, frame.y)
                    assertArrayEquals(originalU, frame.u)
                    assertArrayEquals(originalV, frame.v)
                } finally { rgb.recycle(); expectedEdge.recycle(); expectedOutline.recycle() }
            }
        } finally { original.recycle() }
    }

    @Test fun directJniOutlineDownsamplesLargeInputAndReturnsOriginalDimensions() {
        val original = fixture(960, 640) // Exercises processOutline's native long-edge > 800 branch.
        try {
            Core.setRNGSeed(RNG_SEED)
            val outline = OpenCvImageProcessor.processOutline(original)
            try { assertBinaryNonempty(outline, 960, 640) } finally { outline.recycle() }
        } finally { original.recycle() }
    }

    private fun fixture(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(30, 65, 145))
            val paint = Paint().apply { color = Color.rgb(235, 145, 40); isAntiAlias = false }
            canvas.drawRoundRect(width * .28f, height * .22f, width * .72f, height * .78f,
                width * .06f, height * .06f, paint)
            paint.color = Color.rgb(235, 205, 100)
            canvas.drawRect(width * .38f, height * .34f, width * .52f, height * .55f, paint)
        }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun mask(bitmap: Bitmap) = pixels(bitmap).map { Color.red(it) > 127 }.toBooleanArray()

    private fun assertBinaryNonempty(bitmap: Bitmap, width: Int, height: Int) {
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        val data = pixels(bitmap)
        assertTrue("Output must contain foreground", data.any { Color.red(it) == 255 })
        assertTrue("Output must contain background", data.any { Color.red(it) == 0 })
        assertTrue("Output must be a binary grayscale mask", data.all {
            Color.red(it) == Color.green(it) && Color.red(it) == Color.blue(it) &&
                Color.red(it) in setOf(0, 255)
        })
    }

    private fun luminance(argb: Int) =
        (Color.red(argb) * 299 + Color.green(argb) * 587 + Color.blue(argb) * 114) / 1000

    private companion object { const val RNG_SEED = 20261015 }
}
