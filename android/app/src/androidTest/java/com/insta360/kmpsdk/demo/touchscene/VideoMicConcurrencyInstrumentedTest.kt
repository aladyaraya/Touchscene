package com.insta360.kmpsdk.demo.touchscene

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Button
import androidx.navigation.fragment.NavHostFragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.MainActivity
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.phonecamera.PhoneCameraGateway
import com.insta360.kmpsdk.demo.phonecamera.PhoneConnectionFragment
import com.insta360.kmpsdk.demo.ui.capture.PreviewFragment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator only: real CameraX MP4 + ManualWavRecorder PCM acquisition, followed by explicit
 * command dispatch. WAV stays in RAM. No ASR/TTS/cloud call, speech recognition, audible-output
 * or non-silent-audio claim is made; this checks microphone concurrency and stop-command routing.
 */
@RunWith(AndroidJUnit4::class)
class VideoMicConcurrencyInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun videoKeepsRecordingDuringManualWavCaptureAndStopCommandReturnsToLiveWithoutPhoto() {
        assumeTrue("Synthetic-camera/microphone regression runs only on an emulator",
            Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.MODEL.contains("sdk", ignoreCase = true) || Build.FINGERPRINT.contains("generic"))
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val videosBefore = mediaUris(video = true)
        val photosBefore = mediaUris(video = false)
        val recorder = ManualWavRecorder()
        try {
            openCameraWithoutCloud(activity)
            val camera = onMain { checkNotNull(gateway(preview(activity))) }
            val photosRequestedBefore = onMain { trace(preview(activity)).count { it.event == "phone_photo_requested" } }
            onMain { dispatch(preview(activity), "开始录像") }
            waitFor { onMain { camera.isRecording && trace(preview(activity)).any { it.event == "recording_started" } } }
            CameraXEncodedVideoAwaiter.awaitVideoSamples(camera)

            // Recheck at the actual AudioRecord boundary, not only when granting permission above.
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                recorder.start().getOrThrow()
            } else error("Microphone permission was withdrawn before the AudioRecord boundary")
            assertTrue(recorder.isRecording)
            SystemClock.sleep(800L)
            assertTrue("AudioRecord must not implicitly stop CameraX video", onMain { camera.isRecording })
            val wav = recorder.stop().getOrThrow()
            assertFalse(recorder.isRecording)
            assertTrue("Manual recorder must return PCM data, not just a WAV header", wav.size >= 44 + 3_200)
            assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
            assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
            val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1, header.getShort(20).toInt()) // PCM
            assertEquals(1, header.getShort(22).toInt()) // mono
            assertEquals(16_000, header.getInt(24))
            assertEquals(16, header.getShort(34).toInt())
            assertEquals(wav.size - 44, header.getInt(40))
            assertTrue(onMain { camera.isRecording })

            // Injecting text is deliberate: this is command routing, not an ASR success claim.
            onMain { dispatch(preview(activity), "停止录像") }
            waitFor { onMain {
                !camera.isRecording && camera.isPhotoReady && camera.previewView.isShown &&
                    trace(preview(activity)).any { it.event == "recording_finished" }
            } }
            val videos = mediaUris(video = true) - videosBefore
            assertEquals("Exactly one actual MP4 must complete", 1, videos.size)
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(context, videos.single())
                assertTrue(checkNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() > 0)
                assertTrue(checkNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt() > 0)
            } finally { metadata.release() }
            assertEquals("Recording and microphone command must not take a static photo", photosBefore, mediaUris(video = false))
            onMain {
                assertEquals(photosRequestedBefore,
                    trace(preview(activity)).count { it.event == "phone_photo_requested" })
                assertFalse(activity.findViewById<TactileMapView>(R.id.tactile_map_view).explorationEnabled)
                assertEquals(context.getString(R.string.unified_action_live),
                    activity.findViewById<Button>(R.id.toggle_describe_live_btn).text.toString())
            }
        } finally {
            recorder.cancel()
            onMain { activity.finish() }
            // Only remove this emulator test's generated video; preserve all pre-existing media.
            (mediaUris(video = true) - videosBefore).forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun openCameraWithoutCloud(activity: MainActivity) {
        onMain {
            val home = navHost(activity).childFragmentManager.primaryNavigationFragment as PhoneConnectionFragment
            activity.findViewById<Button>(R.id.btn_wifi).performClick()
            PhoneConnectionFragment::class.java.getDeclaredField("speechOutput").apply { isAccessible = true }
                .let { field -> (field.get(home) as? AndroidSpeechOutput)?.close(); field.set(home, null) }
        }
        waitFor { onMain { navHost(activity).childFragmentManager.primaryNavigationFragment is PreviewFragment &&
            gateway(preview(activity))?.isPhotoReady == true } }
        onMain {
            val fragment = preview(activity)
            PreviewFragment::class.java.getDeclaredField("speechOutput").apply { isAccessible = true }
                .let { field -> val speech = field.get(fragment) as? AndroidSpeechOutput
                    field.set(fragment, null); speech?.close() }
            val coordinator = PreviewFragment::class.java.getDeclaredField("touchSceneCoordinator")
                .apply { isAccessible = true }.get(fragment) as TouchSceneCoordinator
            TouchSceneCoordinator::class.java.getDeclaredField("describer").apply { isAccessible = true }
                .set(coordinator, LocalContourSceneDescriber())
        }
    }

    private fun navHost(activity: MainActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
    private fun preview(activity: MainActivity) = navHost(activity).childFragmentManager.primaryNavigationFragment as PreviewFragment
    private fun gateway(fragment: PreviewFragment) = PreviewFragment::class.java.getDeclaredField("phoneGateway")
        .apply { isAccessible = true }.get(fragment) as? PhoneCameraGateway
    private fun trace(fragment: PreviewFragment) = (PreviewFragment::class.java.getDeclaredField("trace")
        .apply { isAccessible = true }.get(fragment) as TouchSceneTrace).snapshot()
    private fun dispatch(fragment: PreviewFragment, text: String) = PreviewFragment::class.java
        .getDeclaredMethod("dispatchMicCommand", String::class.java).apply { isAccessible = true }.invoke(fragment, text)

    private fun mediaUris(video: Boolean): Set<Uri> {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val relativePath = if (video) "Movies/TouchScene/" else "Pictures/TouchScene/"
        val extension = if (video) "mp4" else "jpg"
        val result = mutableSetOf<Uri>()
        context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(relativePath, "TouchScene-%.$extension"), null)?.use { cursor ->
            while (cursor.moveToNext()) result += ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
        return result
    }

    private fun <T> onMain(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100L)
        }
        assertTrue("Timed out waiting for synthetic video/microphone concurrency", condition())
    }
}
