package com.insta360.kmpsdk.demo.touchscene

import android.content.Context
import android.util.Base64
import com.insta360.kmpsdk.demo.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Sends the existing user-ended WAV recording to Bailian, using the model's native protocol. */
class RemoteAsrTranscriber(
    context: Context,
    private val endpoint: String = BuildConfig.TOUCHSCENE_AI_ENDPOINT,
    private val apiKey: String = BuildConfig.TOUCHSCENE_AI_KEY,
    private val model: String = BuildConfig.TOUCHSCENE_AI_ASR_MODEL,
    private val ethernetWaitMs: Long = 1_500L,
    private val cellularWaitMs: Long = 3_000L,
) {

    val isConfigured: Boolean get() = endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    fun transcribe(wav: ByteArray): String {
        check(isConfigured) { "remote speech recognition is not configured" }
        require(wav.isNotEmpty()) { "audio is empty" }
        val url = requestUrl(endpoint, model)
        val audio = Base64.encodeToString(wav, Base64.NO_WRAP)
        val response = post(url, buildRequestBody(model, audio))
        return parseResponseText(response)?.takeIf(String::isNotBlank)
            ?: error("remote ASR response contained no transcript")
    }

    private fun post(url: URL, body: String): String {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            additionalHeaders(model).forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) error("remote ASR HTTP $code")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val NATIVE_ASR_PATH = "/api/v1/services/aigc/multimodal-generation/generation"

        private fun usesNativeProtocol(model: String): Boolean = model.startsWith("qwen-audio-")

        internal fun requestUrl(endpoint: String, model: String): URL {
            val original = RemoteAiSceneDescriber.checkedBailianUrl(endpoint)
            return if (usesNativeProtocol(model)) URL("https", original.host, original.port, NATIVE_ASR_PATH)
                else original
        }

        internal fun additionalHeaders(model: String): Map<String, String> =
            if (usesNativeProtocol(model)) mapOf("X-DashScope-SSE" to "disable") else emptyMap()

        internal fun buildRequestBody(model: String, wavBase64: String): String =
            if (usesNativeProtocol(model)) buildNativeRequestBody(model, wavBase64) else JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "input_audio")
                                        .put(
                                            "input_audio",
                                            JSONObject().put("data", "data:audio/wav;base64,$wavBase64"),
                                        ),
                                ),
                            ),
                    ),
                )
                .put("stream", false)
                .put(
                    "asr_options",
                    JSONObject()
                        .put("language", "zh")
                        .put("enable_itn", false),
                )
                .toString()

        private fun buildNativeRequestBody(model: String, wavBase64: String): String = JSONObject()
            .put("model", model)
            .put("input", JSONObject().put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", JSONArray().put(JSONObject()
                    .put("type", "input_audio")
                    .put("input_audio", JSONObject().put("data", "data:audio/wav;base64,$wavBase64")))))))
            .put("parameters", JSONObject().put("format", "wav").put("sample_rate", "16000"))
            .toString()

        internal fun parseResponseText(body: String): String? {
            val response = runCatching { JSONObject(body) }.getOrElse { return null }
            response.optJSONObject("output")?.let { output ->
                output.optString("text").trim().takeIf(String::isNotEmpty)?.let { return it }
                output.optJSONObject("sentence")?.optString("text")?.trim()
                    ?.takeIf(String::isNotEmpty)?.let { return it }
            }
            val message = runCatching {
                response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            }.getOrElse { return null }
            return when (val content = runCatching { message.get("content") }.getOrNull()) {
                is String -> content.trim().ifEmpty { null }
                is JSONArray -> buildString {
                    for (index in 0 until content.length()) {
                        val part = content.optJSONObject(index) ?: continue
                        append(part.optString("text"))
                    }
                }.trim().ifEmpty { null }
                else -> null
            }
        }
    }
}
