package com.insta360.kmpsdk.demo

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import timber.log.Timber

/** 手机输入不再初始化影石编辑器、相机参数缓存或热点网络监听。 */
class DemoApplication : Application() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var instance: DemoApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        // Preserve the original processor byte-for-byte, but surface its explicit fallback warning.
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority >= android.util.Log.WARN &&
                    message.contains("OpenCV tactile processing failed; falling back to Canny")) {
                    com.insta360.kmpsdk.demo.touchscene.OpenCvRuntimeStatus.noteFallback()
                }
            }
        })
        // Load the same OpenCV library before either camera or local-image processing.
        com.insta360.kmpsdk.demo.touchscene.OpenCvRuntimeStatus.result
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
    }
}
