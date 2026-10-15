package com.insta360.kmpsdk.demo.touchscene

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.MainActivity
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.phonecamera.PhoneCameraGateway
import com.insta360.kmpsdk.demo.phonecamera.PhoneConnectionFragment
import com.insta360.kmpsdk.demo.raw.LatestCameraFrameStore
import com.insta360.kmpsdk.demo.raw.decodeSampledBitmap
import com.insta360.kmpsdk.demo.raw.toYuv420Frame
import com.insta360.kmpsdk.demo.ui.capture.PreviewFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** CameraX runs on the emulator's synthetic camera. No physical sound/vibration claim. */
@RunWith(AndroidJUnit4::class)
class PhoneCameraMinimalInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun originalHomeControlsAndCoreNavigationAreRetained() {
        val activity = launch()
        try {
            onMain {
                assertTrue(activity.findViewById<Button>(R.id.btn_wifi).isShown)
                assertTrue(activity.findViewById<Button>(R.id.btn_local_image).isShown)
                assertFalse(activity.findViewById<View>(R.id.bottom_nav).isShown)
                assertFalse(activity.findViewById<View>(R.id.entry_live_stream).isShown)
            }
        } finally { onMain { activity.finish() } }
    }

    @Test fun realCameraJpegFreezesOriginalPageAndRetakesAfterReturnToLive() {
        val activity = launch()
        try {
            openCamera(activity)
            var previousTimestamp: Long? = null
            repeat(2) {
                waitFor { onMain { preview(activity).let(::gateway)?.let { it.previewView.isShown } == true } }
                onMain { activity.findViewById<Button>(R.id.toggle_describe_live_btn).performClick() }
                waitFor { onMain { activity.findViewById<TactileMapView>(R.id.tactile_map_view)?.explorationEnabled == true } }
                val source = LatestCameraFrameStore.snapshot()
                assertNotNull(source)
                assertTrue(source!!.width > 0 && source.height > 0)
                assertNotEquals(previousTimestamp, source.sourceTimestampMs)
                previousTimestamp = source.sourceTimestampMs
                onMain {
                    val map = activity.findViewById<TactileMapView>(R.id.tactile_map_view)
                    assertEquals(TactileDebugMode.ORIGINAL, map.debugMode)
                    assertNotNull(map.displayedMapVersion)
                    assertEquals(context.getString(R.string.unified_action_frozen),
                        activity.findViewById<Button>(R.id.toggle_describe_live_btn).text.toString())
                    assertTrue(activity.findViewById<View>(R.id.toggle_describe_live_btn).isShown)
                    activity.findViewById<Button>(R.id.toggle_describe_live_btn).performClick()
                }
                waitFor { onMain { !activity.findViewById<TactileMapView>(R.id.tactile_map_view).explorationEnabled } }
                onMain { assertEquals(context.getString(R.string.unified_action_live),
                    activity.findViewById<Button>(R.id.toggle_describe_live_btn).text.toString()) }
            }
        } finally { onMain { activity.finish() } }
    }

    @Test fun voicePhotoWhileFrozenSavesOneJpegAndRefreshesMapFromThatSameOriginal() {
        val activity = launch()
        val photosBefore = photoUris()
        try {
            openCamera(activity)
            onMain { activity.findViewById<Button>(R.id.toggle_describe_live_btn).performClick() }
            waitFor { onMain { activity.findViewById<TactileMapView>(R.id.tactile_map_view).explorationEnabled } }
            val before = onMain { checkNotNull(activity.findViewById<TactileMapView>(R.id.tactile_map_view).displayedMapVersion) }
            val beforeTimestamp = checkNotNull(LatestCameraFrameStore.snapshot()).sourceTimestampMs
            val savedBefore = onMain { trace(preview(activity)).count { it.event == "phone_photo_saved" } }
            val requestedBefore = onMain { trace(preview(activity)).count { it.event == "phone_photo_requested" } }
            onMain {
                PreviewFragment::class.java.getDeclaredMethod("dispatchMicCommand", String::class.java)
                    .apply { isAccessible = true }.invoke(preview(activity), "请帮我拍照")
            }
            waitFor { onMain {
                activity.findViewById<TextView>(R.id.result_summary).text.toString() == "照片已保存到手机相册" &&
                    activity.findViewById<TactileMapView>(R.id.tactile_map_view).displayedMapVersion != before
            } }
            val savedUris = photoUris() - photosBefore
            assertEquals("Voice photo must save exactly one real gallery JPEG", 1, savedUris.size)
            val saved = savedUris.single()
            context.contentResolver.openFileDescriptor(saved, "r").use { descriptor ->
                assertNotNull(descriptor)
                assertTrue("Saved gallery JPEG must contain bytes", descriptor!!.statSize > 0)
            }
            val source = checkNotNull(LatestCameraFrameStore.snapshot())
            assertTrue("A new captured frame must replace the old frozen source", source.sourceTimestampMs > beforeTimestamp)
            val decoded = checkNotNull(decodeSampledBitmap(context.contentResolver, saved, 1600))
            try {
                val fromSavedUri = decoded.toYuv420Frame(source.sourceTimestampMs, source.receivedAtElapsedRealtimeMs)
                assertEquals(fromSavedUri.width, source.width)
                assertEquals(fromSavedUri.height, source.height)
                assertArrayEquals("Analysis must use the very same saved original JPEG", fromSavedUri.y, source.y)
                assertArrayEquals(fromSavedUri.u, source.u)
                assertArrayEquals(fromSavedUri.v, source.v)
            } finally { decoded.recycle() }
            onMain {
                val map = activity.findViewById<TactileMapView>(R.id.tactile_map_view)
                assertTrue(checkNotNull(map.displayedMapVersion) > before)
                assertTrue(map.explorationEnabled)
                assertEquals(TactileDebugMode.ORIGINAL, map.debugMode)
                assertEquals(savedBefore + 1, trace(preview(activity)).count { it.event == "phone_photo_saved" })
                assertEquals(requestedBefore + 1, trace(preview(activity)).count { it.event == "phone_photo_requested" })
                assertEquals(context.getString(R.string.unified_action_frozen),
                    activity.findViewById<Button>(R.id.toggle_describe_live_btn).text.toString())
            }
        } finally {
            onMain { activity.finish() }
            // Only this emulator test's newly created gallery photo(s), never pre-existing media.
            (photoUris() - photosBefore).forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test fun phoneRecordingProducesRealVideoAndReturnsToPhotoCapture() {
        val activity = launch()
        val started = AtomicBoolean(false)
        val finished = AtomicReference<Result<Uri>?>(null)
        var saved: Uri? = null
        try {
            openCamera(activity)
            val camera = onMain { checkNotNull(gateway(preview(activity))) }
            onMain { camera.startRecording({ started.set(true) }, { finished.set(it) }) }
            waitFor { started.get() }
            CameraXEncodedVideoAwaiter.awaitVideoSamples(camera)
            onMain { camera.stopRecording() }
            waitFor { finished.get() != null }
            saved = checkNotNull(finished.get()).getOrThrow()
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(context, saved)
                assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
                assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt() > 0)
            } finally { metadata.release() }
            waitFor { onMain { !camera.isRecording && camera.previewView.isShown } }
            // Binding back to PHOTO is asynchronous; wait for ImageCapture to be installed.
            waitFor { onMain {
                PhoneCameraGateway::class.java.getDeclaredField("photoCapture")
                    .apply { isAccessible = true }.get(camera) != null
            } }
        } finally {
            onMain { activity.finish() }
            // This URI is solely the synthetic video created above on the emulator.
            saved?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test fun selectedOriginalColourJpegFeedsExistingFrozenPipeline() {
        val activity = launch()
        val file = File(context.cacheDir, "minimal-original-colour-fixture.jpg")
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            bitmap.setPixel(x, y, if (x < bitmap.width / 2) Color.RED else Color.BLUE)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        try {
            openCamera(activity)
            onMain {
                // Inject the synthetic JPEG at the phone input adapter, not a removed camera-page picker.
                val fragment = preview(activity)
                PreviewFragment::class.java.getDeclaredField("stillRequestInFlight")
                    .apply { isAccessible = true }.setBoolean(fragment, true)
                PreviewFragment::class.java.getDeclaredField("stillRequestSaveToGallery")
                    .apply { isAccessible = true }.setBoolean(fragment, false)
                val generation = PreviewFragment::class.java.getDeclaredField("stillRequestGeneration")
                    .apply { isAccessible = true }.getLong(fragment)
                PreviewFragment::class.java.getDeclaredMethod("processPhoneImage", Uri::class.java, Long::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(fragment, Uri.fromFile(file), generation)
            }
            waitFor { onMain { activity.findViewById<TactileMapView>(R.id.tactile_map_view)?.explorationEnabled == true } }
            val frame = checkNotNull(LatestCameraFrameStore.snapshot())
            assertEquals(240, frame.width)
            assertEquals(320, frame.height)
            val original = frame.toRgbDebugBitmap(frame.width, frame.height)
            try {
                assertTrue(Color.red(original.getPixel(40, 160)) > 180)
                assertTrue(Color.blue(original.getPixel(200, 160)) > 180)
                assertTrue(Color.blue(original.getPixel(40, 160)) < 70)
                assertTrue(Color.red(original.getPixel(200, 160)) < 70)
            } finally { original.recycle() }
        } finally { onMain { activity.finish() }; file.delete() }
    }

    private fun launch(): MainActivity {
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        return instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    }

    private fun openCamera(activity: MainActivity) {
        onMain {
            val home = (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
                .childFragmentManager.primaryNavigationFragment as PhoneConnectionFragment
            activity.findViewById<Button>(R.id.btn_wifi).performClick()
            // Close in this same main turn, before TTS initialization can request cloud synthesis.
            // The production token-bound completion/deadline still performs navigation.
            PhoneConnectionFragment::class.java.getDeclaredField("speechOutput").apply { isAccessible = true }
                .let { field -> (field.get(home) as? AndroidSpeechOutput)?.close(); field.set(home, null) }
        }
        waitFor { onMain { activity.findViewById<Button>(R.id.toggle_describe_live_btn) != null &&
            gateway(preview(activity))?.previewView?.isShown == true } }
        onMain {
            val fragment = preview(activity)
            PreviewFragment::class.java.getDeclaredField("speechOutput").apply { isAccessible = true }
                .let { field -> val speech = field.get(fragment) as? AndroidSpeechOutput
                    field.set(fragment, null); speech?.close() }
            val coordinator = PreviewFragment::class.java.getDeclaredField("touchSceneCoordinator")
                .apply { isAccessible = true }.get(fragment) as TouchSceneCoordinator
            // Keep real OpenCV and coordinator; replace only the external description boundary.
            TouchSceneCoordinator::class.java.getDeclaredField("describer").apply { isAccessible = true }
                .set(coordinator, LocalContourSceneDescriber())
            assertEquals(context.getString(R.string.unified_action_live),
                activity.findViewById<Button>(R.id.toggle_describe_live_btn).text.toString())
        }
    }

    private fun trace(fragment: PreviewFragment): List<TouchSceneTrace.Event> =
        (PreviewFragment::class.java.getDeclaredField("trace").apply { isAccessible = true }
            .get(fragment) as TouchSceneTrace).snapshot()

    private fun photoUris(): Set<Uri> {
        val result = mutableSetOf<Uri>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("Pictures/TouchScene/", "TouchScene-%.jpg"), null)?.use { cursor ->
            while (cursor.moveToNext()) result += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        }
        return result
    }

    private fun preview(activity: MainActivity): PreviewFragment =
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
            .childFragmentManager.primaryNavigationFragment as PreviewFragment

    private fun gateway(preview: PreviewFragment): PhoneCameraGateway? =
        PreviewFragment::class.java.getDeclaredField("phoneGateway")
            .apply { isAccessible = true }.get(preview) as? PhoneCameraGateway

    private fun <T> onMain(value: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = value() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for phone input on original page", condition())
    }
}
