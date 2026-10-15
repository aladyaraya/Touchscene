package com.insta360.kmpsdk.demo.touchscene

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.insta360.kmpsdk.demo.BuildConfig
import com.insta360.kmpsdk.demo.raw.Yuv420Frame
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 冻结帧远程 AI 描述，走阿里百炼 OpenAI 兼容协议（chat/completions，qwen-vl 视觉模型）。
 *
 * 手机相机输入直接使用系统默认网络（Wi-Fi / 蜂窝 / VPN），不申请相机热点专用路由。
 *
 * 凭据：endpoint/key/model 通过 gradle 属性 `touchscene.ai.endpoint` /
 * `touchscene.ai.key` / `touchscene.ai.model` 注入 BuildConfig，不进版本库；
 * 未配置或请求失败时回退 [fallback] 本地描述。
 */
class RemoteAiSceneDescriber(
    context: Context,
    private val endpoint: String = BuildConfig.TOUCHSCENE_AI_ENDPOINT,
    private val apiKey: String = BuildConfig.TOUCHSCENE_AI_KEY,
    private val model: String = BuildConfig.TOUCHSCENE_AI_MODEL,
    private val fallback: SceneDescriber,
    private val ethernetWaitMs: Long = 1_500L,
    private val cellularWaitMs: Long = 3_000L,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 15_000,
) : SceneDescriber {

    val isConfigured: Boolean get() = endpoint.isNotBlank() && apiKey.isNotBlank()

    override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription {
        if (!isConfigured) return fallback.describe(frame, map)
        return runCatching { describeRemote(frame) }
            .onFailure { Timber.w(it, "remote AI description failed, using fallback") }
            .getOrElse { fallback.describe(frame, map) }
    }

    private fun describeRemote(frame: Yuv420Frame): SceneDescription {
        val url = checkedBailianUrl(endpoint)
        val bitmap = frame.toRgbDebugBitmap(frame.width, frame.height)
        val jpegBase64 = try {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
        val body = buildRequestBody(model, jpegBase64)
        val response = post(url, body)
        val text = parseResponseText(response)?.takeIf { it.isNotBlank() }
            ?: error("remote AI response had no description text")
        return SceneDescription(text, frame.sourceTimestampMs, "bailian-$model", subject = text)
    }

    private fun post(url: URL, body: String): String {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) error("remote AI HTTP $code")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val JPEG_QUALITY = 80
        private const val MAX_TOKENS = 220
        internal const val BAILIAN_HOST_SUFFIX = ".maas.aliyuncs.com"
        internal const val BAILIAN_BEIJING_HOST = "dashscope.aliyuncs.com"
        private const val PROMPT =
            "请根据照片输出简体中文 JSON，只包含一个 description 字段。" +
                "用一句简短中文描述：主要主体是什么、在画面什么位置、大约占多大，供盲人摄影者判断构图。"

        internal fun checkedBailianUrl(endpoint: String): URL = URL(endpoint).also {
            require(it.protocol == "https" && it.port in listOf(-1, 443) &&
                (it.host == BAILIAN_BEIJING_HOST || it.host.endsWith(BAILIAN_HOST_SUFFIX)) &&
                it.userInfo == null && it.query == null && it.ref == null) {
                "remote AI endpoint must use an approved HTTPS Bailian host"
            }
        }

        /** 百炼 OpenAI 兼容 chat/completions 请求体；jpegBase64 不带 data: 前缀。 */
        internal fun buildRequestBody(model: String, jpegBase64: String): String =
            JSONObject()
                .put("model", model)
                .put("enable_thinking", false)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("max_tokens", MAX_TOKENS)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user").put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", PROMPT))
                                .put(
                                    JSONObject().put("type", "image_url").put(
                                        "image_url",
                                        JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64"),
                                    ),
                                ),
                        ),
                    ),
                )
                .toString()

        /**
         * 从 chat/completions 响应取描述文本。message.content 可能是字符串，
         * 也可能是分段数组；按 response_format 约定优先取 JSON 的 description
         * 字段，拿不到 JSON 时退回原始文本，保证语音播报不为空。
         */
        internal fun parseResponseText(body: String): String? {
            val message = runCatching {
                JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            }.getOrElse { return null }
            val content = when (val raw = runCatching { message.get("content") }.getOrNull()) {
                is JSONArray -> buildString {
                    for (i in 0 until raw.length()) {
                        val part = raw.optJSONObject(i) ?: continue
                        if (part.optString("type") == "text") append(part.optString("text"))
                    }
                }
                is String -> raw
                else -> return null
            }.trim()
            if (content.isEmpty()) return null
            val description = runCatching { JSONObject(content).optString("description").trim() }
                .getOrDefault("")
            return description.ifEmpty { content }
        }

    }
}
