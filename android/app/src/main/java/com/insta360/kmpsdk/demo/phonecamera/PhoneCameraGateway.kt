package com.insta360.kmpsdk.demo.phonecamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Rational
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.UUID

/** Camera input adapter only. It never processes pixels or changes the existing tactile pipeline. */
class PhoneCameraGateway(
    context: Context,
    private val owner: LifecycleOwner,
    val previewView: PreviewView,
    private val onPreviewState: (Boolean, Throwable?) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private var provider: ProcessCameraProvider? = null
    private var photoCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var generation = 0L
    private var closed = false
    private var bindingCamera = false
    private var recordingRequested = false
    private var rotation = Surface.ROTATION_0
    private val photoFiles = mutableListOf<File>()
    val isRecording: Boolean get() = recording != null || recordingRequested
    val isPhotoReady: Boolean get() = !closed && !bindingCamera && photoCapture != null && !isRecording
    private val orientation = object : OrientationEventListener(appContext) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            rotation = when (degrees) {
                in 45..134 -> Surface.ROTATION_270
                in 135..224 -> Surface.ROTATION_180
                in 225..314 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            photoCapture?.targetRotation = rotation
            videoCapture?.targetRotation = rotation
        }
    }

    init {
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    fun start() {
        if (closed || bindingCamera || photoCapture != null || isRecording) return
        previewView.post {
            if (!closed && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                previewView.doOnLayout { bind(video = false) {} }
            }
        }
    }

    private fun bind(video: Boolean, failed: ((Throwable) -> Unit)? = null, ready: () -> Unit) {
        if (closed || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val error = SecurityException("Camera permission is required")
            onPreviewState(false, error)
            failed?.invoke(error)
            return
        }
        bindingCamera = true
        val token = ++generation
        val pendingProvider = ProcessCameraProvider.getInstance(appContext)
        pendingProvider.addListener({
            if (closed || token != generation || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@addListener
            runCatching {
                val cameraProvider = pendingProvider.get()
                provider = cameraProvider
                cameraProvider.unbindAll()
                photoCapture = null
                videoCapture = null
                val displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder().setTargetRotation(displayRotation).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val viewport = ViewPort.Builder(Rational(previewView.width.coerceAtLeast(1), previewView.height.coerceAtLeast(1)), displayRotation)
                    .setScaleType(ViewPort.FIT).build()
                val group = UseCaseGroup.Builder().setViewPort(viewport).addUseCase(preview)
                if (video) {
                    val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(
                        Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                    videoCapture = VideoCapture.withOutput(recorder).apply { targetRotation = rotation }
                    group.addUseCase(videoCapture!!)
                } else {
                    photoCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(rotation).build()
                    group.addUseCase(photoCapture!!)
                }
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
                bindingCamera = false
                orientation.enable()
                onPreviewState(true, null)
                ready()
            }.onFailure { error ->
                bindingCamera = false
                recordingRequested = false
                onPreviewState(false, error)
                failed?.invoke(error)
            }
        }, mainExecutor)
    }

    /** Analysis uses a private JPEG; the original capture command saves a real photo to the album. */
    fun takePhoto(saveToGallery: Boolean = false, onResult: (Result<Uri>) -> Unit) {
        if (closed || isRecording || photoCapture == null) {
            onResult(Result.failure(IllegalStateException(if (isRecording) "Stop recording before taking a photo" else "Camera is not ready")))
            return
        }
        val capture = photoCapture!!
        capture.targetRotation = rotation
        val file = if (saveToGallery) null else {
            val folder = File(appContext.cacheDir, "touchscene-phone-frames").apply { mkdirs() }
            File(folder, "frame-${UUID.randomUUID()}.jpg").also { photoFiles += it }
        }
        val options = if (file != null) ImageCapture.OutputFileOptions.Builder(file).build()
        else ImageCapture.OutputFileOptions.Builder(appContext.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "TouchScene-${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TouchScene")
            }).build()
        val token = generation
        runCatching {
            capture.takePicture(options, mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (closed || token != generation) { file?.delete(); return }
                        val uri = output.savedUri ?: file?.let(Uri::fromFile)
                        onResult(if (uri != null) Result.success(uri) else Result.failure(IllegalStateException("Missing saved photo URI")))
                    }
                    override fun onError(error: ImageCaptureException) {
                        file?.delete()
                        if (!closed && token == generation) onResult(Result.failure(error))
                    }
                })
        }.onFailure { error -> file?.delete(); onResult(Result.failure(error)) }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(onStarted: () -> Unit, onFinished: (Result<Uri>) -> Unit) {
        if (closed || isRecording || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        recordingRequested = true
        bind(video = true, failed = { error ->
            recordingRequested = false
            onFinished(Result.failure(error))
            bind(video = false) {}
        }) {
            val capture = videoCapture ?: return@bind
            val token = generation
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "TouchScene-${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TouchScene")
            }
            val options = MediaStoreOutputOptions.Builder(appContext.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values).build()
            runCatching {
                var pending = capture.output.prepareRecording(appContext, options)
                if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
                recording = pending.start(mainExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> if (!closed && token == generation) onStarted()
                        is VideoRecordEvent.Finalize -> {
                            if (!closed && token == generation) {
                                recording = null
                                recordingRequested = false
                                if (event.hasError()) onFinished(Result.failure(IllegalStateException("Video capture failed: ${event.error}")))
                                else onFinished(Result.success(event.outputResults.outputUri))
                                bind(video = false) {}
                            }
                        }
                        else -> Unit
                    }
                }
            }.onFailure { error ->
                recordingRequested = false
                recording = null
                onFinished(Result.failure(error))
                bind(video = false) {}
            }
        }
    }

    fun stopRecording() {
        if (recording != null) recording?.stop()
        else if (recordingRequested) {
            // A stop spoken during camera reconfiguration must not start a late recording.
            recordingRequested = false
            generation++
            bindingCamera = false
            bind(video = false) {}
        }
    }

    fun stop() {
        generation++
        orientation.disable()
        recording?.stop()
        recording = null
        recordingRequested = false
        photoCapture = null
        videoCapture = null
        bindingCamera = false
        provider?.unbindAll()
    }

    override fun close() {
        stop()
        closed = true
        photoFiles.forEach { it.delete() }
        photoFiles.clear()
    }
}
