package com.insta360.kmpsdk.demo.touchscene

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.intent.IntentCallback
import androidx.test.runner.intent.IntentMonitorRegistry
import androidx.test.runner.intent.IntentStubber
import androidx.test.runner.intent.IntentStubberRegistry
import com.insta360.kmpsdk.demo.MainActivity
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.phonecamera.PhoneConnectionFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Synthetic View/lifecycle checks only; no photo, microphone, TTS or cloud request is started. */
@RunWith(AndroidJUnit4::class)
class HomeScreenInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var downTime = 0L

    @Test fun homeHasExactlyTwoFullWidthEqualHeightActionZones() = fixture().use { h ->
        onMain {
            val camera = h.camera
            val image = h.activity.findViewById<Button>(R.id.btn_local_image)
            val parent = camera.parent as ViewGroup
            assertTrue(camera.isShown)
            assertTrue(image.isShown)
            assertEquals(parent.width, camera.width)
            assertEquals(parent.width, image.width)
            assertTrue(abs(camera.height - image.height) <= 1)
            assertEquals(0, camera.top)
            assertEquals(camera.bottom, image.top)
            assertEquals(parent.height, image.bottom)
            assertEquals(2, visibleButtons(parent).size)
            assertEquals(h.activity.getString(R.string.unified_home_camera), camera.contentDescription.toString())
            assertEquals(h.activity.getString(R.string.unified_home_image), image.contentDescription.toString())
            assertFalse(h.activity.findViewById<View>(R.id.entry_touchscene_local_demo).isShown)
            assertTrue(h.activity.findViewById<View>(R.id.entry_touchscene_local_demo).isEnabled)
            assertFalse(h.activity.findViewById<View>(R.id.bottom_nav).isShown)
        }
    }

    @Test fun downDoesNotNavigateAndSlidingPastSlopCancelsTheRelease() = fixture().use { h ->
        onMain {
            val slop = ViewConfiguration.get(h.activity).scaledTouchSlop
            send(h.camera, MotionEvent.ACTION_DOWN)
            assertTrue(h.camera.isPressed)
            assertIdle(h)
            send(h.camera, MotionEvent.ACTION_MOVE, h.camera.width / 2f + slop * 2f)
            assertFalse(h.camera.isPressed)
            send(h.camera, MotionEvent.ACTION_UP)
            assertIdle(h)
        }
    }

    @Test fun aFarReleaseWithoutAnyMoveEventDoesNotBypassTheSlopGuard() = fixture().use { h ->
        onMain {
            val slop = ViewConfiguration.get(h.activity).scaledTouchSlop
            send(h.camera, MotionEvent.ACTION_DOWN)
            // Android can coalesce/miss MOVE events: validate the UP coordinate itself too.
            send(h.camera, MotionEvent.ACTION_UP, h.camera.width / 2f + slop * 2f)
            assertFalse(h.camera.isPressed)
            assertIdle(h)
        }
    }

    @Test fun leavingTheZoneOrReceivingCancelNeverCommitsNavigation() = fixture().use { h ->
        onMain {
            send(h.camera, MotionEvent.ACTION_DOWN)
            send(h.camera, MotionEvent.ACTION_MOVE, -16f)
            send(h.camera, MotionEvent.ACTION_UP)
            assertIdle(h)
            send(h.camera, MotionEvent.ACTION_DOWN)
            send(h.camera, MotionEvent.ACTION_CANCEL)
            send(h.camera, MotionEvent.ACTION_UP)
            assertFalse(h.camera.isPressed)
            assertIdle(h)
        }
    }

    @Test fun aSecondPointerCancelsAndTheRemainingPointerCannotCommit() = fixture().use { h ->
        onMain {
            send(h.camera, MotionEvent.ACTION_DOWN)
            pointers(h.camera, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), intArrayOf(0, 1))
            assertFalse(h.camera.isPressed)
            pointers(h.camera, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), intArrayOf(0, 1))
            send(h.camera, MotionEvent.ACTION_UP)
            assertIdle(h)
        }
    }

    @Test fun backgroundCancelsContactAndAStaleCompletionCannotOpenAnything() = fixture().use { h ->
        val token = 500L
        onMain {
            send(h.camera, MotionEvent.ACTION_DOWN)
            installPending(h.fragment, token, "IMAGE")
        }
        h.scenario.moveToState(Lifecycle.State.CREATED)
        onMain {
            assertNull(field(h.fragment, "pendingAction"))
            assertNull(field(h.fragment, "speechOutput"))
            assertFalse(h.camera.isPressed)
        }
        h.scenario.moveToState(Lifecycle.State.RESUMED)
        onMain {
            complete(h.fragment, token)
            assertIdle(h)
        }
    }

    @Test fun oneCompletedActionLaunchesTheOriginalDocumentPickerOnlyOnce() = fixture().use { h ->
        // Inject only a ready-to-complete control token: do not create a speaker or upload anything.
        val sent = CopyOnWriteArrayList<Intent>()
        val callback = IntentCallback { intent ->
            if (intent.action == Intent.ACTION_OPEN_DOCUMENT) sent += Intent(intent)
        }
        // Observe the runner's outbound-intent signal, not framework ActivityMonitor.mHits.
        // The runner stub supplies a cancellation result before an external picker is opened.
        assertFalse("Another test left a global intent stub installed", IntentStubberRegistry.isLoaded())
        IntentMonitorRegistry.getInstance().addIntentCallback(callback)
        IntentStubberRegistry.load(object : IntentStubber {
            override fun getActivityResultForIntent(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT)
                    Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) else null
        })
        try {
            onMain {
                installPending(h.fragment, 700L, "IMAGE")
                complete(h.fragment, 700L)
                complete(h.fragment, 700L)
            }
            instrumentation.waitForIdleSync()
            assertEquals("A single actual OpenDocument Intent must be emitted", 1, sent.size)
            // Match this project's actual AndroidX contract, not assumptions from GetContent.
            val expected = ActivityResultContracts.OpenDocument()
                .createIntent(instrumentation.targetContext, arrayOf("image/*"))
            val actual = sent.single()
            assertEquals(expected.action, actual.action)
            assertEquals(expected.type, actual.type)
            assertEquals(expected.categories, actual.categories)
            assertArrayEquals(expected.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
                actual.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
            onMain {
                assertIdle(h)
                assertNull(field(h.fragment, "pendingImage"))
                val image = h.activity.findViewById<Button>(R.id.btn_local_image)
                listOf(h.camera, image).forEach { button ->
                    assertTrue(button.isShown)
                    assertTrue(button.isEnabled)
                    assertTrue(button.isClickable)
                    send(button, MotionEvent.ACTION_DOWN)
                    assertTrue("Picker cancellation must leave home controls responsive", button.isPressed)
                    send(button, MotionEvent.ACTION_CANCEL)
                    assertFalse(button.isPressed)
                    assertIdle(h)
                }
            }
            assertEquals("Cancelled control contacts must not relaunch the picker", 1, sent.size)
        } finally {
            IntentStubberRegistry.reset()
            IntentMonitorRegistry.getInstance().removeIntentCallback(callback)
        }
    }

    private data class Fixture(val scenario: ActivityScenario<MainActivity>, val activity: MainActivity,
        val fragment: PhoneConnectionFragment, val camera: Button) : AutoCloseable {
        override fun close() = scenario.close()
    }

    private fun fixture(): Fixture {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        try {
            waitFor { onMain {
                activity.findViewById<View>(R.id.btn_wifi)?.height?.let { it > 0 } == true &&
                    home(activity).lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            } }
            return onMain { Fixture(scenario, activity, home(activity), activity.findViewById(R.id.btn_wifi)) }
        } catch (error: Throwable) { scenario.close(); throw error }
    }

    private fun home(activity: MainActivity): PhoneConnectionFragment =
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
            .childFragmentManager.primaryNavigationFragment as PhoneConnectionFragment

    private fun assertIdle(h: Fixture) {
        val host = h.activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        assertEquals(R.id.connectionFragment, host.navController.currentDestination?.id)
        assertNull(field(h.fragment, "pendingAction"))
        assertNull(field(h.fragment, "speechOutput"))
    }

    private fun visibleButtons(view: View): List<Button> = when {
        !view.isShown -> emptyList()
        view is Button -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { visibleButtons(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun field(fragment: PhoneConnectionFragment, name: String): Any? = PhoneConnectionFragment::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(fragment)

    private fun installPending(fragment: PhoneConnectionFragment, token: Long, action: String) {
        val actionType = PhoneConnectionFragment::class.java.declaredClasses.first { it.simpleName == "Action" }
        val value = actionType.enumConstants!!.first { (it as Enum<*>).name == action }
        val pendingType = PhoneConnectionFragment::class.java.declaredClasses.first { it.simpleName == "PendingAction" }
        val pending = pendingType.declaredConstructors.first { it.parameterCount == 3 }
            .apply { isAccessible = true }.newInstance(token, value, false)
        PhoneConnectionFragment::class.java.getDeclaredField("generation").apply { isAccessible = true }.setLong(fragment, token)
        PhoneConnectionFragment::class.java.getDeclaredField("pendingAction").apply { isAccessible = true }.set(fragment, pending)
    }

    private fun complete(fragment: PhoneConnectionFragment, token: Long) {
        PhoneConnectionFragment::class.java.getDeclaredMethod("completeAction", Long::class.javaPrimitiveType!!)
            .apply { isAccessible = true }.invoke(fragment, token)
    }

    private fun send(view: View, action: Int, x: Float = view.width / 2f) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, x, view.height / 2f, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun pointers(view: View, action: Int, ids: IntArray) {
        val properties = ids.map { id -> MotionEvent.PointerProperties().apply {
            this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = ids.mapIndexed { index, _ -> MotionEvent.PointerCoords().apply {
            x = view.width / 2f + index * 4f; y = view.height / 2f; pressure = 1f; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, ids.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting for full-zone home layout", condition())
    }
}
