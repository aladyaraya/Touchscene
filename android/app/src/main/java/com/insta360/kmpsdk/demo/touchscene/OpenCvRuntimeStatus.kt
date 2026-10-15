package com.insta360.kmpsdk.demo.touchscene

import org.opencv.core.Core
import timber.log.Timber

/** Explicit JNI preflight. A successful source/hash check is not a native-runtime check. */
object OpenCvRuntimeStatus {
    @Volatile var fallbackObserved: Boolean = false
        private set
    @Volatile var onFallback: (() -> Unit)? = null

    fun noteFallback() {
        fallbackObserved = true
        onFallback?.invoke()
    }

    val result: Result<String> by lazy {
        runCatching {
            System.loadLibrary("c++_shared")
            System.loadLibrary("opencv_java4")
            Core.getVersionString().also { check(it.isNotBlank()) }
        }.onSuccess { Timber.i("TouchSceneNative loaded OpenCV=%s", it) }
            .onFailure { Timber.e(it, "TouchSceneNative unavailable; original processor will use fallback") }
    }

    const val FAILURE_MESSAGE = "原版轮廓引擎加载失败，当前使用备用处理模式，轮廓效果可能不同。请更新应用运行库。"
    const val PROCESSING_FAILURE_MESSAGE = "原版轮廓处理未能完成，当前使用备用处理模式，轮廓效果可能不同。"
}
