package com.insta360.kmpsdk.demo.touchscene

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.RequiresApi

/** Classifies the unchanged map waveform as user touch, not an unknown/media effect. */
internal object TouchFeedbackVibration {
    fun play(vibrator: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, attributes())
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, legacyAttributes())
        }
    }

    @RequiresApi(33)
    internal fun attributes(): VibrationAttributes =
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)

    internal fun legacyAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}
