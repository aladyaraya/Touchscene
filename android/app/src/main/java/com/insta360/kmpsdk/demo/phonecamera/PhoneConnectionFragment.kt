package com.insta360.kmpsdk.demo.phonecamera

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentConnectionBinding
import com.insta360.kmpsdk.demo.touchscene.AndroidSpeechOutput
import com.insta360.kmpsdk.demo.touchscene.ControlPressFeedback
import kotlin.math.hypot

/** Two complete touch zones. A confirmed release announces once before opening its destination. */
class PhoneConnectionFragment : Fragment() {
    private var _binding: FragmentConnectionBinding? = null
    private var feedback: ControlPressFeedback? = null
    private var speechOutput: AndroidSpeechOutput? = null
    private var generation = 0L
    private var pendingAction: PendingAction? = null
    private var pendingImage: Uri? = null
    private val cancelGestures = mutableListOf<() -> Unit>()
    private enum class Action { CAMERA, IMAGE }
    private data class PendingAction(val token: Long, val action: Action, var speechStarted: Boolean = false)

    private val localImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || _binding == null) return@registerForActivityResult
        runCatching { requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        pendingImage = uri
        _binding?.root?.post { openPendingImage() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        FragmentConnectionBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        feedback = ControlPressFeedback(requireContext())
        activity?.findViewById<View>(R.id.bottom_nav)?.visibility = View.GONE
        val b = _binding ?: return
        bindZone(b.btnWifi, Action.CAMERA)
        bindZone(b.btnLocalImage, Action.IMAGE)
        // Hidden original instrumentation entry, never another user-facing focus target.
        b.entryTouchsceneLocalDemo.setOnClickListener {
            cancelPendingAction()
            if (findNavController().currentDestination?.id == R.id.connectionFragment) {
                findNavController().navigate(R.id.action_connectionFragment_to_previewFragment,
                    Bundle().apply { putBoolean("touchsceneLocalDemo", true) })
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindZone(button: MaterialButton, action: Action) {
        val slop = ViewConfiguration.get(requireContext()).scaledTouchSlop.toFloat()
        var pointer = -1
        var valid = false
        var startX = 0f
        var startY = 0f
        var touchClick = false
        val cancelGesture: () -> Unit = {
            valid = false
            pointer = -1
            button.isPressed = false
            button.parent?.requestDisallowInterceptTouchEvent(false)
            feedback?.stop()
        }
        cancelGestures += cancelGesture
        button.isHapticFeedbackEnabled = false
        button.isLongClickable = false
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPendingAction()
                    cancelGesture()
                    if (event.pointerCount == 1) {
                        valid = true
                        pointer = event.getPointerId(0)
                        startX = event.x; startY = event.y
                        view.isPressed = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        feedback?.press()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointer)
                    if (valid && (event.pointerCount != 1 || index < 0 ||
                            event.getX(index) !in 0f..view.width.toFloat() ||
                            event.getY(index) !in 0f..view.height.toFloat() ||
                            hypot(event.getX(index) - startX, event.getY(index) - startY) > slop)) cancelGesture()
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelGesture(); cancelPendingAction()
                }
                MotionEvent.ACTION_UP -> {
                    val confirmed = valid && event.pointerCount == 1 && event.getPointerId(0) == pointer &&
                        event.x in 0f..view.width.toFloat() && event.y in 0f..view.height.toFloat() &&
                        hypot(event.x - startX, event.y - startY) <= slop
                    cancelGesture()
                    if (confirmed) {
                        touchClick = true
                        try { view.performClick() } finally { touchClick = false }
                    }
                }
            }
            true
        }
        button.setOnClickListener {
            if (!touchClick) feedback?.press()
            beginAction(action)
        }
    }

    private fun beginAction(action: Action) {
        if (_binding == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            findNavController().currentDestination?.id != R.id.connectionFragment) return
        cancelPendingAction()
        val token = ++generation
        pendingAction = PendingAction(token, action)
        val screenReader = requireContext().getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        if (screenReader) { completeAction(token); return }
        val root = _binding?.root ?: return
        // A fresh speaker captures its own token; closing the previous speaker cannot complete this turn.
        speechOutput = AndroidSpeechOutput(requireContext()) { speaking ->
            root.post {
                val pending = pendingAction
                if (_binding == null || pending?.token != token || generation != token) return@post
                if (speaking) pending.speechStarted = true
                else if (pending.speechStarted) completeAction(token)
            }
        }
        speechOutput?.speak(getString(if (action == Action.CAMERA)
            R.string.unified_home_open_camera else R.string.unified_home_open_picker))
        root.postDelayed({ completeAction(token) }, 3_000L)
    }

    private fun completeAction(token: Long) {
        val pending = pendingAction ?: return
        if (pending.token != token || generation != token || _binding == null ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        pendingAction = null
        generation++
        val oldSpeech = speechOutput
        speechOutput = null
        oldSpeech?.close()
        feedback?.stop()
        if (findNavController().currentDestination?.id != R.id.connectionFragment) return
        runCatching {
            when (pending.action) {
                Action.CAMERA -> findNavController().navigate(R.id.action_connectionFragment_to_previewFragment)
                Action.IMAGE -> localImagePicker.launch(arrayOf("image/*"))
            }
        }.onFailure { Toast.makeText(requireContext(), R.string.operation_failed_generic, Toast.LENGTH_LONG).show() }
    }

    private fun cancelPendingAction() {
        generation++
        pendingAction = null
        val oldSpeech = speechOutput
        speechOutput = null
        oldSpeech?.close()
    }

    private fun openPendingImage() {
        val uri = pendingImage ?: return
        if (_binding == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        pendingImage = null
        if (findNavController().currentDestination?.id == R.id.connectionFragment) {
            findNavController().navigate(R.id.action_connectionFragment_to_localImageRecognitionFragment,
                Bundle().apply { putString("localImageUri", uri.toString()) })
        }
    }

    override fun onResume() { super.onResume(); _binding?.root?.post { openPendingImage() } }
    override fun onPause() {
        cancelPendingAction()
        cancelGestures.forEach { it() }
        feedback?.stop()
        super.onPause()
    }
    override fun onDestroyView() {
        cancelPendingAction()
        cancelGestures.clear()
        pendingImage = null
        feedback?.close(); feedback = null
        _binding = null
        super.onDestroyView()
    }
}
