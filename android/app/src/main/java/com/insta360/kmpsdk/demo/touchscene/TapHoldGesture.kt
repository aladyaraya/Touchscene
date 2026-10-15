package com.insta360.kmpsdk.demo.touchscene

/** Pure classifier; long confirmation and audio capture are separate from a short click. */
class TapHoldGesture {
    enum class Release { TAP, HOLD, CANCEL, IGNORE }
    private var generation = 0L
    private var active: Long? = null
    private var startedAtMs = 0L
    private var longConfirmed = false

    fun press(nowMs: Long): Long? {
        if (active != null) return null
        return (++generation).also { active = it; startedAtMs = nowMs; longConfirmed = false }
    }

    fun confirmLong(token: Long, nowMs: Long): Boolean {
        if (token != active || longConfirmed || nowMs - startedAtMs < LONG_PRESS_MS) return false
        longConfirmed = true
        return true
    }

    fun release(token: Long, nowMs: Long): Release {
        if (token != active) return Release.IGNORE
        val held = nowMs - startedAtMs
        val result = when {
            longConfirmed -> Release.HOLD
            held in 0 until LONG_PRESS_MS -> Release.TAP
            else -> Release.CANCEL // A delayed timer must never turn a long press into a tap.
        }
        cancel()
        return result
    }

    fun cancel() { generation++; active = null; longConfirmed = false }

    companion object { const val LONG_PRESS_MS = 500L }
}
