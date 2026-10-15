package com.insta360.kmpsdk.demo.touchscene

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.insta360.kmpsdk.demo.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/** Small injectable boundaries for offline lifecycle tests; the application uses their defaults. */
internal interface CloudSpeechSynthesis : AutoCloseable {
    fun synthesize(text: String, onResult: (Result<String>) -> Unit)
    fun cancel()
}

internal interface CloudSpeechPlayback {
    fun prepare(url: String, onPrepared: () -> Unit, onComplete: () -> Unit, onError: () -> Unit)
    fun start()
    fun release()
}

/** Pure turn gate: a late callback cannot finish or start a newer speech turn. */
internal class CloudSpeechTurn {
    private var generation = 0L
    private var active: Long? = null
    @Synchronized fun begin(): Long = (++generation).also { active = it }
    @Synchronized fun isCurrent(token: Long): Boolean = active == token
    @Synchronized fun finish(token: Long): Boolean {
        if (active != token) return false
        active = null
        return true
    }
    @Synchronized fun cancel(): Boolean {
        val wasActive = active != null
        active = null
        generation++
        return wasActive
    }
    @Synchronized fun isSpeaking(): Boolean = active != null
}

/** Existing Chinese TTS fallback only; no page, camera, recognition or tactile behavior lives here. */
internal class CloudTtsFallback(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onFailure: (String) -> Unit,
    private val synthesizer: CloudSpeechSynthesis = BailianSpeechSynthesis(),
    private val playbackFactory: () -> CloudSpeechPlayback = { AndroidCloudSpeechPlayback() },
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val turn = CloudSpeechTurn()
    private var playback: CloudSpeechPlayback? = null
    private var closed = false
    val isSpeaking: Boolean get() = turn.isSpeaking()

    fun speak(text: String) {
        stop(notify = false)
        if (closed) return
        val token = turn.begin()
        // Synthesis and audio preparation are busy too: the original mic must wait for both.
        onSpeakingChanged(true)
        handler.postDelayed({ finish(token, "云端语音准备超时，请重试。") }, PREPARATION_TIMEOUT_MS)
        try {
            synthesizer.synthesize(text) { result ->
                handler.post {
                    if (closed || !turn.isCurrent(token)) return@post
                    result.onSuccess { prepare(token, it) }
                        .onFailure { finish(token, "云端语音合成失败，请检查网络和百炼配置。") }
                }
            }
        } catch (_: Exception) {
            finish(token, "云端语音合成失败，请检查网络和百炼配置。")
        }
    }

    private fun prepare(token: Long, audioUrl: String) {
        try {
            val candidate = playbackFactory()
            playback = candidate
            candidate.prepare(audioUrl, onPrepared = {
                if (closed || !turn.isCurrent(token)) {
                    runCatching { candidate.release() }
                } else {
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ finish(token, "语音播放超时，已停止。") }, PLAYBACK_TIMEOUT_MS)
                    runCatching { candidate.start() }
                        .onFailure { finish(token, "语音播放失败，图片说明仍保留在页面。") }
                }
            }, onComplete = { finish(token, null) },
                onError = { finish(token, "语音播放失败，图片说明仍保留在页面。") })
        } catch (_: Exception) {
            finish(token, "语音播放失败，图片说明仍保留在页面。")
        }
    }

    private fun finish(token: Long, failure: String?) {
        if (!turn.finish(token)) return
        handler.removeCallbacksAndMessages(null)
        synthesizer.cancel()
        releasePlayback()
        onSpeakingChanged(false)
        if (!closed && failure != null) onFailure(failure)
    }

    fun stop(notify: Boolean = true) {
        val wasSpeaking = turn.cancel()
        handler.removeCallbacksAndMessages(null)
        synthesizer.cancel()
        releasePlayback()
        if (notify && wasSpeaking) onSpeakingChanged(false)
    }

    private fun releasePlayback() {
        playback?.let { runCatching { it.release() } }
        playback = null
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        synthesizer.close()
    }

    companion object {
        const val PREPARATION_TIMEOUT_MS = 6_000L
        private const val PLAYBACK_TIMEOUT_MS = 90_000L
    }
}

private class AndroidCloudSpeechPlayback : CloudSpeechPlayback {
    private val player = MediaPlayer()
    override fun prepare(url: String, onPrepared: () -> Unit, onComplete: () -> Unit, onError: () -> Unit) {
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        // A signed OSS URL supplies its own authorization. Never forward the API bearer key here.
        player.setDataSource(url)
        player.setOnPreparedListener { onPrepared() }
        player.setOnCompletionListener { onComplete() }
        player.setOnErrorListener { _, _, _ -> onError(); true }
        player.prepareAsync()
    }
    override fun start() = player.start()
    override fun release() = player.release()
}

private class BailianSpeechSynthesis : CloudSpeechSynthesis {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "touchscene-cloud-tts") }
    private val connection = AtomicReference<HttpURLConnection?>()
    private var worker: Future<*>? = null
    override fun synthesize(text: String, onResult: (Result<String>) -> Unit) {
        cancel()
        worker = executor.submit { onResult(runCatching { request(text) }) }
    }

    private fun request(text: String): String {
        check(BuildConfig.TOUCHSCENE_AI_KEY.isNotBlank()) { "cloud TTS is not configured" }
        val url = CloudTtsProtocol.endpoint(BuildConfig.TOUCHSCENE_AI_ENDPOINT)
        val current = url.openConnection() as HttpURLConnection
        connection.set(current)
        try {
            if (Thread.currentThread().isInterrupted) throw IOException("cloud TTS cancelled")
            current.connectTimeout = 5_000
            current.readTimeout = 7_000
            current.instanceFollowRedirects = false
            current.requestMethod = "POST"
            current.doOutput = true
            current.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            current.setRequestProperty("Authorization", "Bearer ${BuildConfig.TOUCHSCENE_AI_KEY}")
            current.outputStream.use { it.write(CloudTtsProtocol.body(text).toByteArray(Charsets.UTF_8)) }
            if (current.responseCode !in 200..299) throw IOException("cloud TTS HTTP ${current.responseCode}")
            val response = current.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("cloud TTS cancelled")
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("cloud TTS response too large")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            return CloudTtsProtocol.audioUrl(response)
        } finally {
            connection.compareAndSet(current, null)
            runCatching { current.disconnect() }
        }
    }

    override fun cancel() {
        worker?.cancel(true)
        worker = null
        connection.get()?.let { runCatching { it.disconnect() } }
    }
    override fun close() { cancel(); executor.shutdownNow() }
    private companion object { const val MAX_RESPONSE_BYTES = 131_072 }
}

internal object CloudTtsProtocol {
    fun endpoint(configuredEndpoint: String): URL {
        val original = RemoteAiSceneDescriber.checkedBailianUrl(configuredEndpoint)
        require(original.host == "dashscope.aliyuncs.com") { "cloud TTS requires the Beijing endpoint" }
        return URL("https", original.host, original.port, "/api/v1/services/audio/tts/SpeechSynthesizer")
    }
    fun body(text: String): String = JSONObject().put("model", "cosyvoice-v3-flash")
        .put("input", JSONObject().put("text", text).put("voice", "longanyang")
            .put("format", "wav").put("sample_rate", 24_000)).toString()

    fun audioUrl(body: String): String {
        val original = URL(JSONObject(body).getJSONObject("output").getJSONObject("audio").getString("url"))
        require(original.protocol in listOf("http", "https") && original.userInfo == null && original.ref == null &&
            original.port in listOf(-1, 80, 443) &&
            Regex("(^|.*\\.)oss(-[a-z0-9-]+)?\\.aliyuncs\\.com").matches(original.host)) {
            "cloud TTS audio address is not trusted OSS"
        }
        // The service returns a signed HTTP OSS URL. Upgrade it without changing its signed query.
        return URL("https", original.host, -1, original.file).toString()
    }
}
