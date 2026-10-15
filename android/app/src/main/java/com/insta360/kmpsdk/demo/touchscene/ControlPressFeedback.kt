package com.insta360.kmpsdk.demo.touchscene

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Finite control acknowledgement only. The original map/haptic renderer is unchanged. */
class ControlPressFeedback(context: Context) : AutoCloseable {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)

    fun press() {
        if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun stop() { vibrator.cancel() }
    override fun close() = stop()
}
