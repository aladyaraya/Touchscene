package com.insta360.kmpsdk.demo.ui.capture

import android.Manifest
import android.net.Uri
import android.widget.Toast
import androidx.camera.view.PreviewView
import com.insta360.kmpsdk.demo.phonecamera.PhoneCameraGateway
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.content.ComponentName
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentPreviewBinding
import com.insta360.kmpsdk.demo.raw.LatestCameraFrameStore
import com.insta360.kmpsdk.demo.raw.Yuv420Frame
import com.insta360.kmpsdk.demo.raw.decodeSampledBitmap
import com.insta360.kmpsdk.demo.raw.toYuv420Frame
import com.insta360.kmpsdk.demo.touchscene.AndroidHapticRenderer
import com.insta360.kmpsdk.demo.touchscene.TactileCell
import com.insta360.kmpsdk.demo.touchscene.TactileMap
import com.insta360.kmpsdk.demo.touchscene.AndroidSpeechOutput
import com.insta360.kmpsdk.demo.touchscene.MlKitSceneDescriber
import com.insta360.kmpsdk.demo.touchscene.ManualWavRecorder
import com.insta360.kmpsdk.demo.touchscene.OpenCvTactileFrameProcessor
import com.insta360.kmpsdk.demo.touchscene.RemoteAiSceneDescriber
import com.insta360.kmpsdk.demo.touchscene.RemoteAsrTranscriber
import com.insta360.kmpsdk.demo.touchscene.TactileDebugMode
import com.insta360.kmpsdk.demo.touchscene.TactileSessionState
import com.insta360.kmpsdk.demo.touchscene.TouchSceneCoordinator
import com.insta360.kmpsdk.demo.touchscene.TouchSceneTrace
import com.insta360.kmpsdk.demo.touchscene.TouchScenePerformanceMetrics
import com.insta360.kmpsdk.demo.touchscene.FakeCameraGateway
import com.insta360.kmpsdk.demo.touchscene.FakeContourScene
import com.insta360.kmpsdk.demo.touchscene.TouchSceneUpdate
import com.insta360.kmpsdk.demo.touchscene.VoiceCommand
import com.insta360.kmpsdk.demo.touchscene.VoiceCommandParser
import com.insta360.kmpsdk.demo.touchscene.VoiceTurnGate
import com.insta360.kmpsdk.demo.touchscene.HoldToTalkSession
import com.insta360.kmpsdk.demo.touchscene.TapHoldGesture
import com.insta360.kmpsdk.demo.touchscene.ControlPressFeedback
import com.insta360.kmpsdk.demo.touchscene.OpenCvRuntimeStatus
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Original TouchScene preview/exploration page, with only its camera input replaced.
 * Phone CameraX supplies real JPEGs to the unchanged YUV/OpenCV/frozen-map chain.
 * The approved follow-up changes only camera actions and hold-to-talk scheduling.
 */

class PreviewFragment : Fragment() {
    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val LIVE_PREVIEW_FIRST_FRAME_TIMEOUT_MS = 3_000L
        private const val MAX_LIVE_PREVIEW_RECOVERY_ATTEMPTS = 2
        private const val MAX_UPLOAD_LONG_EDGE = 1600
        private const val MIC_RETRY_DELAY_MS = 600L
        private const val MIC_MANUAL_STOP_RESULT_WAIT_MS = 1_200L
        private const val MIC_COMPLETE_SILENCE_MS = 1_800L
        private const val MIC_POSSIBLY_COMPLETE_SILENCE_MS = 1_200L
    }

    private var previewStarted = false
    private var phoneGateway: PhoneCameraGateway? = null
    private var phonePreviewView: PreviewView? = null
    private var cameraPermissionRequested = false
    private var capturePhotoInFlight = false
    private var capturePhotoGeneration = 0L

    private var stillRequestInFlight = false
    private var stillRequestIsRefresh = false
    private var stillRequestSaveToGallery = false
    private var pendingStillCaptureGeneration: Long? = null
    private var stillRequestGeneration = 0L
    private var touchSceneCoordinator: TouchSceneCoordinator? = null
    private var hapticRenderer: AndroidHapticRenderer? = null
    private var speechOutput: AndroidSpeechOutput? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var manualWavRecorder: ManualWavRecorder? = null
    private var remoteAsrTranscriber: RemoteAsrTranscriber? = null
    private val voiceTurnGate = VoiceTurnGate()
    private var micArmed = false
    private var micAwaitListen = false
    private var micRestricted = false
    private var micPermissionPending = false
    private var micUiState = MicUiState.IDLE
    private var micStopRequested = false
    private var micPendingText = ""
    private var micStartGeneration = 0L
    private var micRecognitionGeneration = 0L
    private var micTranscriptionGeneration = 0L
    private val holdSession = HoldToTalkSession()
    private val actionGesture = TapHoldGesture()
    private var actionGestureToken: Long? = null
    private var controlPressFeedback: ControlPressFeedback? = null
    private var touchClickFeedbackPlayed = false
    private var holdPointerId = -1
    private var holdToken: Long? = null
    private var holdStartedAtMs = 0L
    private var holdAudioBusy = false
    private var audioIo: ExecutorService? = null
    private var audioDispatcher: ExecutorCoroutineDispatcher? = null
    private var recordingFeedbackPending = false
    private var descriptionGeneration = 0L
    private var layerHintAnnounced = false
    private var lastSpeechStartedAtMs = 0L
    private var narrationCheckScheduled = false
    private data class PendingNarration(val text: String, val generation: Long, val mapVersion: Long?)
    private var pendingNarration: PendingNarration? = null
    private var manualDescriptionPending = false
    private var trace = TouchSceneTrace()
    private var performanceMetrics = TouchScenePerformanceMetrics()
    private var traceIo: ExecutorService? = null
    private var lastTracedSessionState: TactileSessionState? = null
    private var touchDebugCounts = TactileCellCounts(0, 0, 0)
    private var touchDebugCountsMapVersion: Long? = null
    private val streamStartedAnnounced = AtomicBoolean(false)
    private val localDemoMode: Boolean get() = arguments?.getBoolean("touchsceneLocalDemo") == true

    private data class TactileCellCounts(val background: Int, val subject: Int, val boundary: Int)

    private enum class MicUiState { IDLE, PROMPTING, PREPARING, LISTENING, PROCESSING }

    private fun computeCounts(map: TactileMap): TactileCellCounts {
        var bg = 0; var sub = 0; var bnd = 0
        for (y in 0 until map.height) for (x in 0 until map.width) {
            when (map.cellAt(x, y)) {
                TactileCell.BACKGROUND -> bg++
                TactileCell.SUBJECT -> sub++
                TactileCell.BOUNDARY -> bnd++
            }
        }
        return TactileCellCounts(bg, sub, bnd)
    }

    private fun traceEvent(
        stage: String,
        event: String,
        code: String? = null,
        fallback: String? = null,
        mapVersion: Long? = null,
    ) {
        val entry = trace.record(stage, event, code, fallback, mapVersion)
        Timber.i("TouchSceneTrace %s", entry.toJsonLine())
    }

    private fun flushTrace() {
        val appContext = context?.applicationContext ?: return
        val output = trace.jsonLines()
        val sessionId = trace.sessionId
        traceIo?.execute {
            runCatching {
                val directory = appContext.getExternalFilesDir("touchscene-traces")
                    ?: File(appContext.filesDir, "touchscene-traces")
                check(directory.isDirectory || directory.mkdirs())
                File(directory, "touchscene-$sessionId.jsonl").writeText(output)
            }.onFailure { Timber.w(it, "save TouchScene trace") }
        }
    }

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission(), ::onMicrophonePermissionResult)

    private fun onMicrophonePermissionResult(granted: Boolean) {
        // The original gesture cannot survive a permission dialog or Activity recreation.
        // No granted result (including a restored one) may implicitly start either recorder.
        micPermissionPending = false
        if (_binding == null) return
        cancelMicSession()
        val message = if (granted) "麦克风权限已开启，请重新按住说话"
            else "需要麦克风权限才能使用语音命令"
        binding.touchsceneStatus.text = message
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        speakFeedback(message)
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            _binding?.root?.post {
                if (_binding == null) return@post
                if (granted) startPreviewIfNeeded()
                else {
                    val message = "需要相机权限才能使用手机取景，可返回首页选择图片。"
                    binding.touchsceneStatus.text = message
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    speakFeedback(message)
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        trace = TouchSceneTrace()
        performanceMetrics = TouchScenePerformanceMetrics()
        traceIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "touchscene-trace-io") }
        lastTracedSessionState = null
        traceEvent("connection", "preview_page_entered", code = "PHONE_CAMERA",
            fallback = if (localDemoMode) "local_demo" else null)
        audioIo = Executors.newSingleThreadExecutor { Thread(it, "touchscene-mic-io") }
        audioDispatcher = audioIo!!.asCoroutineDispatcher()
        layerHintAnnounced = false
        pendingNarration = null
        holdAudioBusy = false
        controlPressFeedback = ControlPressFeedback(requireContext())
        setupTouchScene()
        updateDisplayedLayerHint(binding.tactileMapView.debugMode)
        bindUnifiedAction(binding.toggleDescribeLiveBtn)
        refreshUnifiedAction()
        binding.captureBtn.setOnClickListener { takePhonePhoto() }
        binding.streamToggleBtn.setOnClickListener {
            if (previewStarted) stopStreamByUser() else startStreamByUser()
        }
        // Only vendor lens/parameter controls are unavailable on a phone.
        binding.lensSection.isVisible = false
        binding.modeScroll.isVisible = false
        binding.openModeParamsSheet.isVisible = false
        binding.sdStatusLabel.isVisible = false
        binding.blockingOverlay.isVisible = false
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // performResume advances the view lifecycle after this callback returns.
        _binding?.root?.post { if (_binding != null) startPreviewIfNeeded() }
    }

    override fun onPause() {
        traceEvent("performance", "map_window", code = performanceMetrics.snapshot().traceCode(),
            fallback = if (localDemoMode) "local_demo" else null)
        traceEvent("session", "paused")
        flushTrace()
        invalidateNarration()
        cancelMicSession()
        speechOutput?.stop()
        capturePhotoGeneration++
        capturePhotoInFlight = false
        _binding?.blockingOverlay?.isVisible = false
        touchSceneCoordinator?.cancelDescription()
        manualDescriptionPending = false
        _binding?.describeSceneBtn?.isEnabled = true
        stopPreview()
        hapticRenderer?.cancel()
        super.onPause()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() { stopPreview(); super.onStop() }

    private fun initLivePreviewPlayer() {
        if (phoneGateway != null || _binding == null || localDemoMode) return
        val preview = PreviewView(requireContext())
        phonePreviewView = preview
        // INVISIBLE still measures the CameraX surface; GONE would wait forever for doOnLayout.
        binding.livePreviewContainer.visibility = View.INVISIBLE
        binding.livePreviewContainer.addView(preview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        phoneGateway = PhoneCameraGateway(requireContext(), viewLifecycleOwner, preview) { ready, error ->
            if (_binding == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@PhoneCameraGateway
            previewStarted = ready
            if (ready) {
                traceEvent("preview", "phone_camera_ready")
                binding.touchsceneStatus.setText(R.string.touchscene_stream_started)
                streamStartedAnnounced.set(true)
                captureRequestedStillIfReady()
            } else if (error != null) {
                traceEvent("preview", "phone_camera_failed", code = error.javaClass.simpleName)
                val message = "手机相机暂不可用，可返回首页选择图片。"
                binding.touchsceneStatus.text = message
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                speakFeedback(message)
            }
            updateStreamToggleLabel()
        }
    }

    private fun updateLivePreviewVisibility() {
        val b = _binding ?: return
        val showLive = previewStarted &&
            touchSceneCoordinator?.currentSessionState() == TactileSessionState.LIVE &&
            b.tactileMapView.debugMode == TactileDebugMode.ORIGINAL
        b.livePreviewContainer.visibility = if (showLive) View.VISIBLE else View.INVISIBLE
    }

    private fun releaseLivePreviewPlayer() {
        phoneGateway?.close()
        phoneGateway = null
        phonePreviewView?.let { _binding?.livePreviewContainer?.removeView(it) }
        phonePreviewView = null
    }

    private fun cancelStillRequest() {
        invalidateNarration()
        pendingStillCaptureGeneration = null
        capturePhotoInFlight = false
        stillRequestGeneration++
        stillRequestInFlight = false
        touchSceneCoordinator?.cancelPendingStill()
        LatestCameraFrameStore.clear()
        hapticRenderer?.cancel()
        _binding?.let { b ->
            val live = touchSceneCoordinator?.currentSessionState() == TactileSessionState.LIVE
            b.freezeMapBtn.isEnabled = live
            b.refreshMapBtn.isEnabled = !live
            b.toggleDescribeLiveBtn.isEnabled = true
            refreshUnifiedAction()
        }
    }

    private fun processPhoneImage(uri: Uri, generation: Long) {
        val resolver = requireContext().applicationContext.contentResolver
        viewLifecycleOwner.lifecycleScope.launch {
            val frame = runCatching { withContext(Dispatchers.IO) {
                val bitmap = checkNotNull(decodeSampledBitmap(resolver, uri, MAX_UPLOAD_LONG_EDGE))
                try {
                    val now = SystemClock.elapsedRealtime()
                    bitmap.toYuv420Frame(now, now)
                } finally { bitmap.recycle() }
            } }.getOrNull()
            if (_binding == null || !stillRequestInFlight || generation != stillRequestGeneration) return@launch
            if (frame == null) { failStillRequest(generation, "PHONE_IMAGE_DECODE_FAILED"); return@launch }
            LatestCameraFrameStore.clear()
            LatestCameraFrameStore.publish(frame)
            traceEvent("decoder", "phone_original_frame", code = frame.width.toString() + "x" + frame.height)
            binding.rawFrameStatus.text = getString(R.string.raw_frame_ready_format,
                frame.width, frame.height, LatestCameraFrameStore.count())
            // EXIF is normalized by the existing URI decoder. Never submit a rendered layer.
            touchSceneCoordinator?.analyzeAndFreeze(frame, rotationOverride = 0)
        }
    }

    private fun setupTouchScene() {
        val haptics = AndroidHapticRenderer(requireContext())
        hapticRenderer = haptics
        speechOutput = AndroidSpeechOutput(requireContext(), ::onSpeechOutputChanged)
        traceEvent("processor", if (OpenCvRuntimeStatus.result.isSuccess) "native_loaded" else "native_unavailable",
            code = OpenCvRuntimeStatus.result.getOrNull())
        if (OpenCvRuntimeStatus.result.isFailure) {
            Toast.makeText(requireContext(), OpenCvRuntimeStatus.FAILURE_MESSAGE, Toast.LENGTH_LONG).show()
        }
        binding.tactileMapView.hapticRenderer = haptics
        binding.tactileMapView.onExplorationStarted = { version ->
            traceEvent("exploration", "touch_started", mapVersion = version)
        }
        binding.tactileMapView.onTouchDebug = { px, py, grid, cell, layerValue ->
            _binding?.let { b ->
                val counts = touchDebugCounts
                val gx = grid?.x?.toString() ?: "-"
                val gy = grid?.y?.toString() ?: "-"
                b.touchsceneStatus.text =
                    "touch=(${px.toInt()},${py.toInt()}) grid=($gx,$gy) cell=$cell v=$layerValue " +
                        "B=${counts.boundary} S=${counts.subject} BG=${counts.background}"
            }
        }
        binding.mapMoveUpBtn.setOnClickListener { binding.tactileMapView.moveAccessibilityCursor(0, -1) }
        binding.mapMoveDownBtn.setOnClickListener { binding.tactileMapView.moveAccessibilityCursor(0, 1) }
        binding.mapMoveLeftBtn.setOnClickListener { binding.tactileMapView.moveAccessibilityCursor(-1, 0) }
        binding.mapMoveRightBtn.setOnClickListener { binding.tactileMapView.moveAccessibilityCursor(1, 0) }
        // 双击图层循环切换：原图 -> Canny图层 -> 二值图层 -> 原图
        // 由于循环顺序固定，目标图层唯一决定本次切换，语音直接按目标播报
        binding.tactileMapView.onDebugModeChanged = { mode ->
            updateDisplayedLayerHint(mode)
            updateLivePreviewVisibility()
            val promptRes = when (mode) {
                TactileDebugMode.ORIGINAL -> R.string.touchscene_layer_to_original
                TactileDebugMode.EDGE -> R.string.touchscene_layer_to_edge
                TactileDebugMode.BINARY -> R.string.touchscene_layer_to_binary
                else -> 0
            }
            if (promptRes != 0) {
                speakFeedback(getString(promptRes))
                if (!holdSession.isRecording) haptics.playSuccess()
            }
        }

        val livePreviewDoubleTapDetector =
            GestureDetector(
                requireContext(),
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (touchSceneCoordinator?.activeMapVersion() == null) {
                            speakFeedback("请先点击描述当前画面生成触觉图层")
                            return true
                        }
                        binding.tactileMapView.cycleDebugMode()
                        return true
                    }
                },
            )
        binding.livePreviewContainer.setOnTouchListener { _, event ->
            livePreviewDoubleTapDetector.onTouchEvent(event)
            true
        }
        initLivePreviewPlayer()
        binding.touchsceneStatus.text = "正在打开手机相机…"
        touchSceneCoordinator =
            TouchSceneCoordinator(
                frameProcessor = OpenCvTactileFrameProcessor(),
                describer = RemoteAiSceneDescriber(
                    requireContext(),
                    fallback = MlKitSceneDescriber(requireContext()),
                ),
                descriptionTimeoutMs = 10_000L,
            ) { update ->
                val processedAtMs = SystemClock.elapsedRealtime()
                _binding?.root?.post {
                    if (_binding == null) return@post
                    update.debugSnapshot?.source?.let { source ->
                        performanceMetrics.record(
                            source.receivedAtElapsedRealtimeMs,
                            processedAtMs,
                            SystemClock.elapsedRealtime(),
                        )
                    }
                    renderTouchSceneUpdate(update)
                    if (stillRequestInFlight && update.sessionState == TactileSessionState.EXPLORING) {
                        finishStillRequest(update.map)
                    }
                }
            }
        speechRecognizer = createSpeechRecognizer().also(::bindRecognitionListener)
        manualWavRecorder = ManualWavRecorder()
        remoteAsrTranscriber = RemoteAsrTranscriber(requireContext())

        binding.freezeMapBtn.setOnClickListener { freezeCurrentFrame() }
        binding.refreshMapBtn.setOnClickListener {
            if (binding.tactileMapView.isFingerExploring) {
                speakFeedback(getString(R.string.touchscene_lift_finger_before_map_change))
                return@setOnClickListener
            }
            requestStillFrame(refresh = true)
        }
        binding.returnLiveBtn.setOnClickListener { returnToLive() }
        binding.describeSceneBtn.setOnClickListener { describeLatestScene() }
        binding.hapticEnabled.setOnCheckedChangeListener { _, enabled ->
            haptics.setEnabled(enabled)
            binding.hapticSelfTestBtn.isEnabled = enabled
        }
        binding.hapticSelfTestBtn.setOnClickListener { haptics.selfTest() }
        binding.voiceCommandBtn.setOnClickListener { requestVoiceRecognition() }
        if (localDemoMode) {
            traceEvent("fallback", "local_demo_frame_requested", fallback = "local_demo")
            binding.rawFrameStatus.setText(R.string.touchscene_local_demo_active)
            binding.captureBtn.isEnabled = false
            binding.streamToggleBtn.isEnabled = false
        }
    }

    private fun returnToLive() {
        if (binding.tactileMapView.isFingerExploring) {
            speakFeedback(getString(R.string.touchscene_lift_finger_before_map_change))
            return
        }
        cancelStillRequest()
        touchSceneCoordinator?.cancelDescription()
        speechOutput?.stop()
        hapticRenderer?.cancel()
        val map = touchSceneCoordinator?.leaveExploration()
        traceEvent("map", "returned_live", mapVersion = map?.version)
        binding.tactileMapView.explorationEnabled = false
        refreshUnifiedAction()
        speakFeedback(getString(R.string.touchscene_returned_live))
    }

    private fun renderTouchSceneUpdate(update: TouchSceneUpdate) {
        val map = update.map
        if (lastTracedSessionState != update.sessionState) {
            lastTracedSessionState = update.sessionState
            traceEvent("map", "state_${update.sessionState.name.lowercase()}", mapVersion = map?.version)
        }
        // In LIVE this is a visual debug preview only. EXPLORING/STALE always retains its frozen version.
        binding.tactileMapView.submitMap(map)
        binding.tactileMapView.submitDebugSnapshot(update.debugSnapshot)
        binding.tactileMapView.explorationEnabled = update.sessionState != TactileSessionState.LIVE
        binding.scrollView.gestureInterceptTarget =
            if (update.sessionState == TactileSessionState.LIVE) null else binding.tactileMapView
        if (map != null && touchDebugCountsMapVersion != map.version) {
            touchDebugCounts = computeCounts(map)
            touchDebugCountsMapVersion = map.version
        }
        val canExplore = update.sessionState != TactileSessionState.LIVE && map != null
        binding.freezeMapBtn.isEnabled = update.sessionState == TactileSessionState.LIVE && !stillRequestInFlight
        binding.returnLiveBtn.isEnabled = update.sessionState != TactileSessionState.LIVE
        binding.mapMoveUpBtn.isEnabled = canExplore
        binding.mapMoveDownBtn.isEnabled = canExplore
        binding.mapMoveLeftBtn.isEnabled = canExplore
        binding.mapMoveRightBtn.isEnabled = canExplore
        binding.refreshMapBtn.isEnabled = update.sessionState != TactileSessionState.LIVE && !stillRequestInFlight

        refreshUnifiedAction()

        binding.touchsceneStatus.text =
            when (update.sessionState) {
                TactileSessionState.LIVE -> getString(R.string.touchscene_live_map)
                TactileSessionState.EXPLORING ->
                    getString(R.string.touchscene_exploring_map, map?.version ?: 0L) +
                        " · B=${touchDebugCounts.boundary} S=${touchDebugCounts.subject} BG=${touchDebugCounts.background}"
                TactileSessionState.STALE -> getString(R.string.touchscene_stale_map)
            }
        if (update.sessionState == TactileSessionState.STALE) {
            binding.touchsceneStatus.announceForAccessibility(getString(R.string.touchscene_stale_map))
        }
        updateLivePreviewVisibility()
    }

    /** 点击后获取一张新帧，分析完成才进入触觉探索。 */
    private fun freezeCurrentFrame() {
        if (binding.tactileMapView.isFingerExploring) {
            speakFeedback(getString(R.string.touchscene_lift_finger_before_map_change))
            return
        }
        requestStillFrame(refresh = false)
    }

    /** One JPEG serves storage, OpenCV and AI; never capture an extra analysis photo for voice. */
    private fun requestStillFrame(refresh: Boolean, saveToGallery: Boolean = false) {
        if (stillRequestInFlight || capturePhotoInFlight) return
        if (phoneGateway?.isRecording == true) {
            speakFeedback("正在录像，请长按说停止录像，再拍照。")
            return
        }
        if (binding.tactileMapView.isFingerExploring) {
            speakFeedback(getString(R.string.touchscene_lift_finger_before_map_change))
            return
        }
        invalidateNarration()
        touchSceneCoordinator?.cancelDescription()
        speechOutput?.stop()
        manualDescriptionPending = false
        binding.describeSceneBtn.isEnabled = true
        stillRequestInFlight = true
        stillRequestIsRefresh = refresh
        stillRequestSaveToGallery = saveToGallery
        capturePhotoInFlight = saveToGallery
        val generation = ++stillRequestGeneration
        pendingStillCaptureGeneration = generation
        traceEvent("map", if (refresh) "refresh_requested" else "freeze_requested")
        binding.freezeMapBtn.isEnabled = false
        binding.refreshMapBtn.isEnabled = false
        refreshUnifiedAction()
        binding.touchsceneStatus.setText(R.string.touchscene_freezing_frame)
        if (localDemoMode) {
            pendingStillCaptureGeneration = null
            touchSceneCoordinator?.analyzeAndFreeze(FakeCameraGateway().frame(
                FakeContourScene.IRREGULAR, SystemClock.elapsedRealtime()))
        } else {
            // A lifecycle/rebind race must not require a second user click.
            if (!previewStarted) startPreviewIfNeeded()
            else if (phoneGateway?.isPhotoReady != true) phoneGateway?.start()
            captureRequestedStillIfReady()
        }
        binding.root.postDelayed({
            if (_binding == null || !stillRequestInFlight || generation != stillRequestGeneration) return@postDelayed
            failStillRequest(generation, "TIMEOUT")
        }, 15_000L)
    }

    private fun captureRequestedStillIfReady() {
        val generation = pendingStillCaptureGeneration ?: return
        val camera = phoneGateway ?: return
        if (_binding == null || !stillRequestInFlight || generation != stillRequestGeneration ||
            !camera.isPhotoReady) return
        pendingStillCaptureGeneration = null // Claim exactly once, before calling CameraX.
        traceEvent("capture", "phone_photo_requested", code = if (stillRequestSaveToGallery) "SAVE_AND_DESCRIBE" else "DESCRIBE")
        camera.takePhoto(saveToGallery = stillRequestSaveToGallery) { result ->
            if (_binding == null || !stillRequestInFlight || generation != stillRequestGeneration) return@takePhoto
            result.onSuccess { uri ->
                traceEvent("capture", "phone_photo_saved", code = if (stillRequestSaveToGallery) "SAVE_AND_DESCRIBE" else "DESCRIBE")
                processPhoneImage(uri, generation)
            }.onFailure { failStillRequest(generation, "PHONE_CAPTURE_FAILED") }
        }
    }

    private fun failStillRequest(generation: Long, code: String) {
        if (!stillRequestInFlight || generation != stillRequestGeneration) return
        cancelStillRequest()
        val isLive = touchSceneCoordinator?.currentSessionState() == TactileSessionState.LIVE
        binding.freezeMapBtn.isEnabled = isLive
        binding.refreshMapBtn.isEnabled = !isLive
        refreshUnifiedAction()
        binding.touchsceneStatus.setText(R.string.touchscene_no_frame)
        traceEvent("map", "freeze_failed", code = code)
        speakFeedback(getString(R.string.touchscene_no_frame))
    }

    private fun updateDisplayedLayerHint(mode: TactileDebugMode) {
        val label = when (mode) {
            TactileDebugMode.EDGE -> R.string.touchscene_local_image_layer_edge
            TactileDebugMode.BINARY -> R.string.touchscene_local_image_layer_outline
            else -> R.string.touchscene_local_image_layer_original
        }
        _binding?.layerGestureHint?.text = getString(R.string.current_layer_gesture_hint,
            getString(label), getString(R.string.hold_layer_gesture_hint))
    }

    private fun finishStillRequest(map: TactileMap?) {
        if (map == null) return
        val refreshed = stillRequestIsRefresh
        val saved = stillRequestSaveToGallery
        stillRequestInFlight = false
        capturePhotoInFlight = false
        pendingStillCaptureGeneration = null
        stillRequestGeneration++
        traceEvent("map", if (refreshed) "refreshed" else "frozen", mapVersion = map.version)
        binding.refreshMapBtn.isEnabled = true
        binding.returnLiveBtn.isEnabled = true
        binding.tactileMapView.explorationEnabled = true
        binding.scrollView.gestureInterceptTarget = binding.tactileMapView
        refreshUnifiedAction()
        if (saved) {
            binding.resultPanel.isVisible = true
            binding.resultSummary.text = "照片已保存到手机相册"
            traceEvent("capture", "finished", code = "PHONE_PHOTO")
            hapticRenderer?.playSuccess()
            speakFeedback("照片已保存，正在描述新画面")
        } else speakFeedback("已冻结当前画面，正在识别画面内容")
        // A refreshed voice photo is a new scene too, not merely a local-map refresh.
        describeLatestScene()
    }

    private fun invalidateNarration() {
        descriptionGeneration++
        pendingNarration = null
    }

    /** Ordinary feedback must not be captured as a command. Description has a deferred slot. */
    private fun speakFeedback(text: String) {
        if (_binding == null || holdSession.isRecording ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        speechOutput?.speak(text)
    }

    private fun flushPendingNarration() {
        val pending = pendingNarration ?: return
        if (_binding == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (pending.generation != descriptionGeneration ||
            pending.mapVersion != touchSceneCoordinator?.activeMapVersion()) {
            pendingNarration = null
            return
        }
        if (holdSession.state != HoldToTalkSession.State.IDLE || holdAudioBusy ||
            capturePhotoInFlight || recordingFeedbackPending) return
        if (speechOutput?.isSpeaking() == true) {
            // Some OEM engines omit onDone. Never interrupt real playback/preparation.
            if (SystemClock.elapsedRealtime() - lastSpeechStartedAtMs >= 700L &&
                speechOutput?.isEngineSpeaking() != true) {
                speechOutput?.stop()
            } else if (!narrationCheckScheduled) {
                narrationCheckScheduled = true
                binding.root.postDelayed({
                    narrationCheckScheduled = false
                    flushPendingNarration()
                }, 200L)
            }
            return
        }
        pendingNarration = null
        val text = if (layerHintAnnounced) pending.text else
            pending.text.trimEnd().trimEnd('。', '！', '？') + "。双击画面，可在原图、边缘、轮廓间切换。"
        layerHintAnnounced = true
        speechOutput?.speak(text)
        traceEvent("description", "narration_started", mapVersion = pending.mapVersion)
    }

    private fun describeLatestScene() {
        val generation = ++descriptionGeneration
        val mapVersion = touchSceneCoordinator?.activeMapVersion()
        pendingNarration = null
        traceEvent("description", "requested", mapVersion = mapVersion)
        manualDescriptionPending = true
        binding.describeSceneBtn.isEnabled = false
        touchSceneCoordinator?.describeLatest { description ->
            _binding?.root?.post {
                if (_binding == null || generation != descriptionGeneration ||
                    mapVersion != touchSceneCoordinator?.activeMapVersion()) return@post
                manualDescriptionPending = false
                binding.describeSceneBtn.isEnabled = true
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@post
                if (description == null) {
                    traceEvent("description", "failed", code = "NO_RESULT")
                    speakFeedback(getString(R.string.touchscene_no_frame))
                } else {
                    traceEvent("description", "completed", fallback = description.source)
                    binding.touchsceneStatus.text = description.text
                    pendingNarration = PendingNarration(description.text, generation, mapVersion)
                    flushPendingNarration()
                }
            }
        }
    }

    private fun requestVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onSpeechOutputChanged(speaking: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            _binding?.root?.post { onSpeechOutputChanged(speaking) }
            return
        }
        if (_binding == null) return
        if (speaking) {
            lastSpeechStartedAtMs = SystemClock.elapsedRealtime()
            if (holdSession.isRecording) {
                speechOutput?.stop()
                return
            }
            if (voiceTurnGate.speechStarted()) speechRecognizer?.cancel()
        } else {
            if (voiceTurnGate.speechFinished() &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                holdSession.state == HoldToTalkSession.State.IDLE) {
                startVoiceRecognition(resuming = true)
            }
            binding.root.post { flushPendingNarration() }
        }
    }

    private fun onUnifiedTap() {
        if (!canBeginUnifiedAction()) return
        if (phoneGateway?.isRecording == true) {
            speakFeedback("正在录像，请长按说停止录像。")
        } else if (touchSceneCoordinator?.currentSessionState() == TactileSessionState.LIVE) {
            freezeCurrentFrame()
        } else returnToLive()
    }

    private fun canBeginUnifiedAction(): Boolean =
        _binding != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            holdSession.state == HoldToTalkSession.State.IDLE && !holdAudioBusy &&
            !micPermissionPending && !stillRequestInFlight && !capturePhotoInFlight && !recordingFeedbackPending

    /** TalkBack double-tap-and-hold uses this same touch sequence; accessible click is a tap. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun bindUnifiedAction(button: View) {
        button.isLongClickable = true
        button.isHapticFeedbackEnabled = false
        button.setOnClickListener {
            if (!canBeginUnifiedAction()) return@setOnClickListener
            if (!touchClickFeedbackPlayed) controlPressFeedback?.press()
            onUnifiedTap()
        }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.pointerCount == 1 && canBeginUnifiedAction() &&
                        !binding.tactileMapView.isFingerExploring) {
                        val token = actionGesture.press(SystemClock.elapsedRealtime())
                        if (token != null) {
                            actionGestureToken = token
                            holdPointerId = event.getPointerId(0)
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            v.isPressed = true
                            controlPressFeedback?.press()
                            binding.root.postDelayed({
                                if (_binding != null && actionGestureToken == token &&
                                    actionGesture.confirmLong(token, SystemClock.elapsedRealtime())) {
                                    // Only recording begins here. No camera/photo operation.
                                    if (!beginHoldRecording()) cancelUnifiedGesture()
                                }
                            }, TapHoldGesture.LONG_PRESS_MS)
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(holdPointerId)
                    if (actionGestureToken != null && (event.pointerCount != 1 || index < 0 ||
                            event.getX(index) !in 0f..v.width.toFloat() ||
                            event.getY(index) !in 0f..v.height.toFloat())) cancelUnifiedGesture()
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> cancelUnifiedGesture()
                MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    val gesture = actionGestureToken
                    val audio = holdToken
                    val valid = event.pointerCount == 1 && event.getPointerId(0) == holdPointerId &&
                        event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    holdPointerId = -1
                    actionGestureToken = null
                    holdToken = null
                    if (gesture == null) return@setOnTouchListener true
                    if (!valid) { cancelUnifiedGesture(); return@setOnTouchListener true }
                    when (actionGesture.release(gesture, SystemClock.elapsedRealtime())) {
                        TapHoldGesture.Release.TAP -> {
                            touchClickFeedbackPlayed = true
                            try { v.performClick() } finally { touchClickFeedbackPlayed = false }
                        }
                        TapHoldGesture.Release.HOLD -> if (audio != null) {
                            when (holdSession.release(audio, SystemClock.elapsedRealtime())) {
                                HoldToTalkSession.Decision.SUBMIT -> stopManualRecordingAndTranscribe(audio)
                                HoldToTalkSession.Decision.CANCEL -> cancelMicSession(
                                    if (SystemClock.elapsedRealtime() - holdStartedAtMs >= HoldToTalkSession.MAX_HOLD_MS)
                                        "录音超过30秒，已取消，请重新按住说话" else null)
                                HoldToTalkSession.Decision.IGNORE -> Unit
                            }
                        }
                        TapHoldGesture.Release.CANCEL -> cancelUnifiedGesture()
                        TapHoldGesture.Release.IGNORE -> Unit
                    }
                }
            }
            true
        }
    }

    private fun cancelUnifiedGesture() {
        actionGesture.cancel()
        actionGestureToken = null
        holdPointerId = -1
        _binding?.toggleDescribeLiveBtn?.isPressed = false
        _binding?.toggleDescribeLiveBtn?.parent?.requestDisallowInterceptTouchEvent(false)
        controlPressFeedback?.stop()
        if (holdSession.state != HoldToTalkSession.State.IDLE || holdToken != null || holdAudioBusy) {
            cancelMicSession()
        }
    }

    private fun beginHoldRecording(): Boolean {
        if (_binding == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            holdAudioBusy || holdSession.state != HoldToTalkSession.State.IDLE ||
            micPermissionPending || capturePhotoInFlight || stillRequestInFlight || recordingFeedbackPending) return false
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            micPermissionPending = true
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        val token = holdSession.begin(SystemClock.elapsedRealtime()) ?: return false
        holdToken = token
        micArmed = true
        micAwaitListen = false
        micRestricted = false
        voiceTurnGate.reset()
        micRecognitionGeneration++
        speechRecognizer?.cancel()
        speechOutput?.stop()
        controlPressFeedback?.stop()
        hapticRenderer?.cancel()
        // Mute the existing renderer only during capture; do not alter any waveform/algorithm.
        hapticRenderer?.setEnabled(false)
        val result = manualWavRecorder?.start()
        if (result?.isSuccess != true) {
            cancelMicSession()
            binding.touchsceneStatus.setText(R.string.touchscene_mic_record_failed)
            Toast.makeText(requireContext(), R.string.touchscene_mic_record_failed, Toast.LENGTH_LONG).show()
            return false
        }
        holdStartedAtMs = SystemClock.elapsedRealtime()
        if (!holdSession.markRecordingStarted(token, holdStartedAtMs)) {
            cancelMicSession()
            return false
        }
        updateMicUi(MicUiState.LISTENING)
        binding.touchsceneStatus.setText(R.string.hold_mic_recording)
        traceEvent("voice", "hold_started")
        binding.root.postDelayed({
            if (_binding != null && holdSession.timeout(token, SystemClock.elapsedRealtime()) ==
                HoldToTalkSession.Decision.CANCEL) {
                cancelMicSession("录音超过30秒，已取消，请重新按住说话")
            }
        }, HoldToTalkSession.MAX_HOLD_MS)
        return true
    }

    private fun cancelMicSession(message: String? = null) {
        actionGesture.cancel()
        actionGestureToken = null
        controlPressFeedback?.stop()
        holdSession.cancel()
        holdToken = null
        holdPointerId = -1
        micStartGeneration++
        micTranscriptionGeneration++
        micRecognitionGeneration++
        micAwaitListen = false
        micArmed = false
        micRestricted = false
        micStopRequested = false
        micPendingText = ""
        voiceTurnGate.reset()
        speechRecognizer?.cancel()
        speechOutput?.stop()
        _binding?.toggleDescribeLiveBtn?.isPressed = false
        _binding?.toggleDescribeLiveBtn?.parent?.requestDisallowInterceptTouchEvent(false)
        updateMicUi(MicUiState.IDLE)
        val recorder = manualWavRecorder ?: return
        val executor = audioIo ?: return
        val root = _binding?.root
        val cleanupGeneration = micTranscriptionGeneration
        holdAudioBusy = true
        executor.execute {
            recorder.cancel()
            root?.post {
                // Only the last queued cleanup may unlock a reused recorder.
                if (manualWavRecorder !== recorder || _binding == null ||
                    cleanupGeneration != micTranscriptionGeneration) return@post
                holdAudioBusy = false
                hapticRenderer?.setEnabled(binding.hapticEnabled.isChecked)
                if (message != null) {
                    binding.touchsceneStatus.text = message
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                    speakFeedback(message)
                }
                flushPendingNarration()
            }
        }
    }

    private fun updateMicUi(state: MicUiState) {
        micUiState = state
        refreshUnifiedAction()
    }

    private fun refreshUnifiedAction() {
        val b = _binding ?: return
        val live = touchSceneCoordinator?.currentSessionState() == TactileSessionState.LIVE
        val recording = micUiState == MicUiState.LISTENING
        b.toggleDescribeLiveBtn.setText(when {
            recording -> R.string.unified_action_listening
            micUiState == MicUiState.PROCESSING -> R.string.unified_action_processing
            stillRequestInFlight -> R.string.unified_action_processing
            phoneGateway?.isRecording == true -> R.string.unified_action_recording
            live -> R.string.unified_action_live
            else -> R.string.unified_action_frozen
        })
        // Keep accessible instruction stable during capture; no app-spoken prompt enters the mic.
        b.toggleDescribeLiveBtn.contentDescription = getString(R.string.unified_action_description)
        b.toggleDescribeLiveBtn.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), if (live) R.color.touchscene_primary else R.color.touchscene_success))
        b.toggleDescribeLiveBtn.isActivated = recording
        b.toggleDescribeLiveBtn.isEnabled = !stillRequestInFlight
    }

    private fun stopManualRecordingAndTranscribe(token: Long) {
        micArmed = false
        updateMicUi(MicUiState.PROCESSING)
        val generation = ++micTranscriptionGeneration
        val recorder = manualWavRecorder
        val transcriber = remoteAsrTranscriber
        val dispatcher = audioDispatcher ?: return
        holdAudioBusy = true
        lifecycleScope.launch {
            val result = runCatching {
                val wav = withContext(dispatcher) { checkNotNull(recorder).stop().getOrThrow() }
                if (_binding != null && generation == micTranscriptionGeneration) {
                    hapticRenderer?.setEnabled(binding.hapticEnabled.isChecked)
                }
                withContext(Dispatchers.IO) {
                    check(holdSession.isCurrent(token))
                    checkNotNull(transcriber).transcribe(wav)
                }
            }
            if (_binding == null || generation != micTranscriptionGeneration ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                !holdSession.finish(token)) return@launch
            holdAudioBusy = false
            // stop() can fail before producing WAV; do not leave touch feedback muted then.
            hapticRenderer?.setEnabled(binding.hapticEnabled.isChecked)
            updateMicUi(MicUiState.IDLE)
            result.onSuccess { text ->
                traceEvent("voice", "hold_completed")
                dispatchMicCommand(text)
            }.onFailure {
                traceEvent("voice", "hold_failed", code = it.javaClass.simpleName)
                speakFeedback(getString(R.string.touchscene_mic_cloud_failed))
            }
            flushPendingNarration()
        }
    }

    /** Keep the recognizer alive across OEM silence segmentation until the user taps Stop. */
    private fun restartManualMicListening(recreateRecognizer: Boolean = true) {
        if (!micArmed || !micRestricted || micStopRequested || _binding == null) return
        val generation = ++micStartGeneration
        updateMicUi(MicUiState.LISTENING)
        binding.root.postDelayed({
            if (_binding == null || generation != micStartGeneration || !micArmed || !micRestricted || micStopRequested) {
                return@postDelayed
            }
            if (recreateRecognizer) {
                micRecognitionGeneration++
                speechRecognizer?.destroy()
                speechRecognizer = createSpeechRecognizer().also(::bindRecognitionListener)
            }
            voiceTurnGate.beginListening()
            startVoiceRecognition(resuming = true)
        }, MIC_RETRY_DELAY_MS)
    }

    private fun finishManualMicCommand(text: String) {
        if (!micRestricted) return
        micStartGeneration++
        micRestricted = false
        micArmed = false
        micStopRequested = false
        micPendingText = ""
        voiceTurnGate.reset()
        updateMicUi(MicUiState.IDLE)
        if (text.isBlank()) {
            speakFeedback(getString(R.string.touchscene_speech_failed))
        } else {
            dispatchMicCommand(text)
        }
    }

    private fun dispatchMicCommand(text: String) {
        when {
            text.contains("停止摄影") || text.contains("停止录像") || text.contains("停止录音") -> stopPhoneRecording()
            text.contains("拍照") || text.contains("拍一张") -> takePhonePhoto()
            text.contains("摄影") || text.contains("录像") || text.contains("录音") -> startPhoneRecording()
            else -> speakFeedback(getString(R.string.touchscene_mic_no_such_operation))
        }
    }

    private fun createSpeechRecognizer(): SpeechRecognizer {
        val context = requireContext()
        // 系统默认识别服务可被 OEM/用户替换（magicvoice 实测立即拒绝第三方调用），Google 服务存在时显式绑定，离线模型也挂在该服务上
        val google = ComponentName(
            "com.google.android.tts",
            "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
        )
        val present = runCatching { context.packageManager.getServiceInfo(google, 0) }.getOrNull() != null
        Timber.d("mic: createSpeechRecognizer googleServicePresent=%b", present)
        return if (present) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context, google) }
                .getOrDefault(SpeechRecognizer.createSpeechRecognizer(context))
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun bindRecognitionListener(recognizer: SpeechRecognizer) {
        val generation = ++micRecognitionGeneration
        recognizer.setRecognitionListener(buildRecognitionListener(generation))
    }

    private fun startVoiceRecognition(resuming: Boolean = false) {
        if (holdSession.state != HoldToTalkSession.State.IDLE || holdAudioBusy) return
        speechRecognizer?.let(::bindRecognitionListener)
        if (!resuming) {
            voiceTurnGate.beginListening()
            speechOutput?.stop()
        }
        binding.touchsceneStatus.setText(R.string.touchscene_listening)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, MIC_COMPLETE_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, MIC_POSSIBLY_COMPLETE_SILENCE_MS)
            // 连接相机热点时手机无互联网，云端识别必然失败；优先使用端侧离线识别。
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        Timber.d("mic: startListening resuming=%b", resuming)
        runCatching { checkNotNull(speechRecognizer).startListening(intent) }
            .onFailure {
                Timber.w(it, "mic: startListening failed")
                voiceTurnGate.reset()
                if (micRestricted) {
                    micArmed = false
                    micRestricted = false
                    updateMicUi(MicUiState.IDLE)
                }
                speakFeedback(getString(R.string.touchscene_speech_failed))
            }
    }

    private fun buildRecognitionListener(generation: Long): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (generation != micRecognitionGeneration) return
                if (micRestricted && !micStopRequested) updateMicUi(MicUiState.LISTENING)
            }
            override fun onBeginningOfSpeech() {
                if (generation != micRecognitionGeneration) return
                if (micRestricted && !micStopRequested) updateMicUi(MicUiState.LISTENING)
            }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                if (generation != micRecognitionGeneration) return
                if (micRestricted && micStopRequested) updateMicUi(MicUiState.PROCESSING)
            }
            override fun onError(error: Int) {
                if (generation != micRecognitionGeneration) return
                Timber.w("mic: recognition onError code=%d awaitListen=%b", error, micAwaitListen)
                // 提示播报期收掉常驻监听所产生的 cancel 噪声，不能破坏麦克风状态机。
                if (micAwaitListen) {
                    return
                }
                val turnWasActive = voiceTurnGate.recognitionFinished()
                val retryableSilence = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
                if (micRestricted && micStopRequested) {
                    finishManualMicCommand(micPendingText)
                    return
                }
                val fatalError =
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (turnWasActive && micRestricted && micArmed && !fatalError && _binding != null) {
                    val serviceInterrupted = !retryableSilence
                    restartManualMicListening(recreateRecognizer = serviceInterrupted)
                    return
                }
                val wasMicCommand = micRestricted
                micRestricted = false
                micArmed = false
                if (wasMicCommand) updateMicUi(MicUiState.IDLE)
                if (turnWasActive && _binding != null) {
                    speakFeedback(getString(R.string.touchscene_speech_failed))
                }
            }
            override fun onResults(results: Bundle?) {
                if (generation != micRecognitionGeneration) return
                if (!voiceTurnGate.recognitionFinished() || _binding == null) return
                val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val text = candidates.firstOrNull().orEmpty()
                Timber.d("mic: recognition result restricted=%b text=%s", micRestricted, text)
                if (micRestricted) {
                    if (text.isNotBlank()) {
                        micPendingText = listOf(micPendingText, text).filter { it.isNotBlank() }.joinToString(" ")
                    }
                    if (micStopRequested || !micArmed) {
                        finishManualMicCommand(micPendingText)
                    } else {
                        restartManualMicListening(recreateRecognizer = true)
                    }
                } else {
                    dispatchVoiceCommand(VoiceCommandParser.parse(text))
                }
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    private fun dispatchVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.TakePhoto -> takePhonePhoto()
            VoiceCommand.StartStream -> startStreamByUser()
            VoiceCommand.StopStream -> stopStreamByUser()
            VoiceCommand.FreezeFrame -> freezeCurrentFrame()
            VoiceCommand.ReturnLive -> binding.returnLiveBtn.performClick()
            VoiceCommand.DescribeScene -> describeLatestScene()
            VoiceCommand.Repeat -> if (speechOutput?.repeatLast() != true) speakFeedback(getString(R.string.touchscene_no_frame))
            VoiceCommand.Help -> speakFeedback(getString(R.string.touchscene_help))
            is VoiceCommand.Unknown -> speakFeedback(getString(R.string.touchscene_unknown_command))
        }
    }

    private fun startStreamByUser() {
        if (localDemoMode) {
            val message = getString(R.string.touchscene_stream_requires_camera)
            binding.touchsceneStatus.text = message
            speakFeedback(message)
            return
        }
        if (previewStarted) {
            speakFeedback(getString(R.string.touchscene_stream_started))
            return
        }
        startPreviewIfNeeded()
    }

    private fun stopStreamByUser() {
        if (!previewStarted) {
            speakFeedback(getString(R.string.touchscene_stream_not_started))
            return
        }
        stopPreview()
        speakFeedback(getString(R.string.touchscene_stream_stopped))
    }

    private fun takePhonePhoto() {
        if (localDemoMode) {
            binding.touchsceneStatus.setText(R.string.touchscene_capture_requires_camera)
            speakFeedback(getString(R.string.touchscene_capture_requires_camera))
            return
        }
        // Deliberately does not require leaving the frozen map first.
        requestStillFrame(refresh = touchSceneCoordinator?.currentSessionState() != TactileSessionState.LIVE,
            saveToGallery = true)
    }

    private fun startPhoneRecording() {
        if (localDemoMode || !previewStarted || phoneGateway == null) {
            speakFeedback(getString(R.string.touchscene_capture_requires_camera))
            return
        }
        if (stillRequestInFlight || capturePhotoInFlight || phoneGateway?.isRecording == true) {
            speakFeedback(getString(R.string.touchscene_capture_unavailable))
            return
        }
        if (binding.tactileMapView.isFingerExploring) {
            speakFeedback(getString(R.string.touchscene_lift_finger_before_map_change))
            return
        }
        invalidateNarration()
        touchSceneCoordinator?.cancelDescription()
        speechOutput?.stop()
        if (touchSceneCoordinator?.currentSessionState() != TactileSessionState.LIVE) returnToLive()
        recordingFeedbackPending = true
        refreshUnifiedAction()
        phoneGateway?.startRecording(
            onStarted = {
                recordingFeedbackPending = false
                refreshUnifiedAction()
                if (_binding != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    traceEvent("capture", "recording_started", code = "PHONE_CAMERA")
                    hapticRenderer?.playSuccess()
                    binding.touchsceneStatus.setText(R.string.touchscene_recording_started)
                    speakFeedback(getString(R.string.touchscene_recording_started))
                }
            },
            onFinished = { result ->
                recordingFeedbackPending = false
                refreshUnifiedAction()
                if (_binding != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    if (result.isSuccess) {
                        traceEvent("capture", "recording_finished", code = "PHONE_CAMERA")
                        hapticRenderer?.playSuccess()
                        binding.touchsceneStatus.setText(R.string.touchscene_recording_stopped)
                        speakFeedback(getString(R.string.touchscene_recording_stopped))
                    } else {
                        traceEvent("capture", "recording_failed", code = "PHONE_CAMERA")
                        speakFeedback(getString(R.string.touchscene_capture_failed))
                    }
                }
            },
        )
    }

    private fun stopPhoneRecording() {
        if (phoneGateway?.isRecording != true) {
            speakFeedback("当前没有正在进行的录像")
            return
        }
        invalidateNarration()
        touchSceneCoordinator?.cancelDescription()
        recordingFeedbackPending = true
        phoneGateway?.stopRecording()
    }

    private fun startPreviewIfNeeded() {
        if (_binding == null || localDemoMode || previewStarted) return
        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!cameraPermissionRequested) {
                cameraPermissionRequested = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            return
        }
        initLivePreviewPlayer()
        traceEvent("preview", "phone_start_requested")
        phoneGateway?.start()
    }

    private fun stopPreview() {
        recordingFeedbackPending = false
        capturePhotoGeneration++
        capturePhotoInFlight = false
        _binding?.blockingOverlay?.isVisible = false
        phoneGateway?.stop()
        cancelStillRequest()
        previewStarted = false
        streamStartedAnnounced.set(false)
        updateStreamToggleLabel()
    }

    private fun updateStreamToggleLabel() {
        _binding?.streamToggleBtn?.setText(
            if (previewStarted) R.string.touchscene_stream_stop else R.string.touchscene_stream_start,
        )
        updateLivePreviewVisibility()
        refreshUnifiedAction()
    }



    override fun onDestroyView() {
        Timber.d("onDestroyView")
        voiceTurnGate.reset()
        micArmed = false
        micAwaitListen = false
        micRestricted = false
        micPermissionPending = false
        micUiState = MicUiState.IDLE
        micStopRequested = false
        micPendingText = ""
        micStartGeneration++
        micRecognitionGeneration++
        micTranscriptionGeneration++
        invalidateNarration()
        cancelMicSession()
        controlPressFeedback?.close()
        controlPressFeedback = null
        audioDispatcher?.close()
        audioDispatcher = null
        audioIo = null
        manualWavRecorder = null
        remoteAsrTranscriber = null
        stopPreview()
        releaseLivePreviewPlayer()
        speechRecognizer?.destroy()
        speechRecognizer = null
        speechOutput?.close()
        speechOutput = null
        touchSceneCoordinator?.close()
        touchSceneCoordinator = null
        hapticRenderer?.cancel()
        hapticRenderer = null
        traceEvent("session", "view_destroyed")
        flushTrace()
        traceIo?.shutdown()
        traceIo = null
        super.onDestroyView()
        _binding = null
    }
}
