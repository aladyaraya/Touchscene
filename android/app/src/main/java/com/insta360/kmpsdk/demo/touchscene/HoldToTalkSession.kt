package com.insta360.kmpsdk.demo.touchscene

/**
 * Pure hold-to-talk turn gate. Callers own AudioRecord, asynchronous cleanup and ASR execution.
 * Supply a monotonic millisecond clock (for example elapsedRealtime), not wall-clock time.
 */
class HoldToTalkSession {
    enum class State { IDLE, RECORDING, PROCESSING }
    enum class Decision { SUBMIT, CANCEL, IGNORE }

    @Volatile var state: State = State.IDLE
        private set
    val isRecording: Boolean get() = state == State.RECORDING
    val isProcessing: Boolean get() = state == State.PROCESSING

    private var generation = 0L
    private var activeToken: Long? = null
    private var startedAtMs = 0L

    @Synchronized
    fun begin(nowMs: Long): Long? {
        if (state != State.IDLE) return null
        val token = ++generation
        activeToken = token
        startedAtMs = nowMs
        state = State.RECORDING
        return token
    }

    /** Reserve the turn before audio setup, but measure audio only after start() succeeds. */
    @Synchronized
    fun markRecordingStarted(token: Long, nowMs: Long): Boolean {
        if (state != State.RECORDING || activeToken != token) return false
        startedAtMs = nowMs
        return true
    }

    @Synchronized
    fun release(token: Long, nowMs: Long): Decision {
        if (state != State.RECORDING || activeToken != token) return Decision.IGNORE
        val heldMs = nowMs - startedAtMs
        if (heldMs < MIN_HOLD_MS || heldMs >= MAX_HOLD_MS) {
            cancel()
            return Decision.CANCEL
        }
        state = State.PROCESSING
        return Decision.SUBMIT
    }

    @Synchronized
    fun timeout(token: Long, nowMs: Long): Decision {
        if (state != State.RECORDING || activeToken != token || nowMs - startedAtMs < MAX_HOLD_MS) {
            return Decision.IGNORE
        }
        cancel()
        return Decision.CANCEL
    }

    @Synchronized
    fun cancel() {
        generation++
        activeToken = null
        state = State.IDLE
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = activeToken == token && state != State.IDLE

    /** Only the active ASR turn may finish. An old result must not clear a newer recording. */
    @Synchronized
    fun finish(token: Long): Boolean {
        if (state != State.PROCESSING || activeToken != token) return false
        activeToken = null
        state = State.IDLE
        return true
    }

    companion object {
        const val MIN_HOLD_MS = 300L
        const val MAX_HOLD_MS = 30_000L
    }
}
