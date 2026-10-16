package com.insta360.kmpsdk.demo.touchscene

import android.Manifest
import android.media.AudioAttributes
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.navigation.fragment.NavHostFragment
import androidx.camera.view.PreviewView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.insta360.kmpsdk.demo.MainActivity
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.phonecamera.PhoneCameraGateway
import com.insta360.kmpsdk.demo.ui.capture.PreviewFragment
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opens preview only, no photo capture/audio/HTTP. Brief real vibration is intentional. */
@RunWith(AndroidJUnit4::class)
class CameraHapticUsageInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun touchClassificationHasNoInterruptionBypassFlags() {
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, TouchFeedbackVibration.legacyAttributes().usage)
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals(VibrationAttributes.USAGE_TOUCH, TouchFeedbackVibration.attributes().usage)
            assertEquals(0, TouchFeedbackVibration.attributes().flags)
        }
    }

    @Test fun unchangedLoopReachesSystemAsTouchWhilePhoneCameraIsOpen() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        // startActivitySync waits for queue-idle and can stall on this OEM's live UI.
        // Launch normally via shell, then use bounded lifecycle monitoring instead.
        shell("am start -W -f 0x10008000 -n ${context.packageName}/${MainActivity::class.java.name}")
        var launched: MainActivity? = null
        waitFor {
            launched = onMain { ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() }
            launched != null
        }
        val activity = checkNotNull(launched)
        var renderer: AndroidHapticRenderer? = null
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        try {
            onMain {
                val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                // Navigate directly: no homepage TTS/cloud prompt and no shutter press.
                host.navController.navigate(R.id.previewFragment)
                activity.supportFragmentManager.executePendingTransactions()
                host.childFragmentManager.executePendingTransactions()
                val fragment = host.childFragmentManager.primaryNavigationFragment as PreviewFragment
                PreviewFragment::class.java.getDeclaredField("speechOutput").apply { isAccessible = true }
                    .let { f -> (f.get(fragment) as? AndroidSpeechOutput)?.close(); f.set(fragment, null) }
            }
            waitFor {
                onMain {
                    val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    val fragment = host.childFragmentManager.primaryNavigationFragment as? PreviewFragment
                    val camera = fragment?.let { field(it, "phoneGateway") as? PhoneCameraGateway }
                    camera?.isPhotoReady == true && camera.previewView.isShown &&
                        camera.previewView.previewStreamState.value == PreviewView.StreamState.STREAMING
                }
            }
            // Compare identical waveform data, changing only classification.
            val effect = VibrationEffect.createWaveform(AndroidHapticRenderer.SUSTAINED_TIMINGS,
                AndroidHapticRenderer.SUSTAINED_AMPLITUDES, 0)
            onMain { vibrator.vibrate(effect) }
            val unclassified = currentVibration()
            onMain { vibrator.cancel() }
            waitFor { !currentVibration().contains(context.packageName) }
            onMain {
                renderer = AndroidHapticRenderer(context)
                renderer.render(1)
            }
            waitFor { currentVibration().let { it.contains(context.packageName) && it.contains("TOUCH") } }
            val classified = currentVibration()
            Log.i("TouchSceneCameraHaptic", "cameraOpen=true oldActive=${unclassified.contains(context.packageName)} newTouchActive=${classified.contains(context.packageName) && classified.contains("TOUCH")}")
            assertTrue("Classified waveform missing from current vibration: $classified",
                classified.contains(context.packageName) && classified.contains("TOUCH"))
            onMain { renderer!!.cancel() }
            waitFor { !currentVibration().contains(context.packageName) }
        } finally {
            onMain { renderer?.cancel(); vibrator.cancel(); activity.finish() }
        }
    }

    private fun currentVibration(): String {
        val dump = shell("dumpsys vibrator_manager")
        return dump.substringAfter("  CurrentVibration:").substringBefore("  NextVibration:")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting for camera/haptic state", condition())
    }
}
