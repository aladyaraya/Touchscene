package com.insta360.kmpsdk.demo.touchscene

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** API-shape fixtures only. No key, real audio/photo or HTTP request is used by these tests. */
class BailianCompatibilityTest {
    private val chatEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    private val nativeEndpoint = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"

    @Test fun `official Beijing host and original workspace host are trusted`() {
        assertEquals("dashscope.aliyuncs.com", RemoteAiSceneDescriber.checkedBailianUrl(chatEndpoint).host)
        val workspace = "https://workspace-fixture.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
        assertEquals(workspace, RemoteAiSceneDescriber.checkedBailianUrl(workspace).toString())
        assertEquals(443, RemoteAiSceneDescriber.checkedBailianUrl("https://dashscope.aliyuncs.com:443/compatible-mode/v1/chat/completions").port)
    }

    @Test fun `HTTP arbitrary subdomains credentials query fragments and non HTTPS port are refused`() {
        listOf("http://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            "https://sub.dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            "https://dashscope.aliyuncs.com.attacker.invalid/compatible-mode/v1/chat/completions",
            "https://workspace.maas.aliyuncs.com.attacker.invalid/chat/completions",
            "https://user:secret@dashscope.aliyuncs.com/chat/completions",
            "https://dashscope.aliyuncs.com:8443/chat/completions",
            "https://workspace.maas.aliyuncs.com:8443/chat/completions",
            "$chatEndpoint?key=fixture", "$chatEndpoint#fragment").forEach {
            assertTrue("Untrusted endpoint was accepted", runCatching { RemoteAiSceneDescriber.checkedBailianUrl(it) }.isFailure)
        }
    }

    @Test fun `new vision model retains original prompt original JPEG data and protocol`() {
        val body = JSONObject(RemoteAiSceneDescriber.buildRequestBody("qwen3.5-flash", "ORIGINAL_JPEG_FIXTURE"))
        assertEquals("qwen3.5-flash", body.getString("model"))
        assertFalse(body.getBoolean("enable_thinking"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("请根据照片输出简体中文 JSON，只包含一个 description 字段。" +
            "用一句简短中文描述：主要主体是什么、在画面什么位置、大约占多大，供盲人摄影者判断构图。",
            content.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,ORIGINAL_JPEG_FIXTURE", content.getJSONObject(1)
            .getJSONObject("image_url").getString("url"))
    }

    @Test fun `qwen audio ASR uses native input messages WAV 16k parameters and non streaming header`() {
        val model = "qwen-audio-3.0-asr-flash"
        val body = JSONObject(RemoteAsrTranscriber.buildRequestBody(model, "WAV_FIXTURE"))
        assertEquals(model, body.getString("model"))
        assertFalse(body.has("messages"))
        assertFalse(body.has("asr_options"))
        val message = body.getJSONObject("input").getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val audio = message.getJSONArray("content").getJSONObject(0)
        assertEquals("input_audio", audio.getString("type"))
        assertEquals("data:audio/wav;base64,WAV_FIXTURE", audio.getJSONObject("input_audio").getString("data"))
        assertEquals("wav", body.getJSONObject("parameters").getString("format"))
        assertEquals("16000", body.getJSONObject("parameters").getString("sample_rate"))
        assertEquals(mapOf("X-DashScope-SSE" to "disable"), RemoteAsrTranscriber.additionalHeaders(model))
    }

    @Test fun `ASR converts compatible vision endpoint to native endpoint without altering original legacy model`() {
        val model = "qwen-audio-3.0-asr-flash"
        assertEquals(nativeEndpoint, RemoteAsrTranscriber.requestUrl(chatEndpoint, model).toString())
        assertEquals(nativeEndpoint, RemoteAsrTranscriber.requestUrl(nativeEndpoint, model).toString())
        assertEquals(chatEndpoint, RemoteAsrTranscriber.requestUrl(chatEndpoint, "qwen3-asr-flash").toString())
        assertTrue(RemoteAsrTranscriber.additionalHeaders("qwen3-asr-flash").isEmpty())
    }

    @Test fun `native ASR reads nested output text and sentence text`() {
        assertEquals("拍照", RemoteAsrTranscriber.parseResponseText("""{"output":{"text":" 拍照 "}}"""))
        assertEquals("停止摄影", RemoteAsrTranscriber.parseResponseText("""{"output":{"sentence":{"text":"停止摄影"}}}"""))
        assertNull(RemoteAsrTranscriber.parseResponseText("""{"output":{"text":"  "}}"""))
        assertNull(RemoteAsrTranscriber.parseResponseText("""{"code":"InvalidApiKey","message":"fixture"}"""))
        assertNull(RemoteAsrTranscriber.parseResponseText("not json"))
    }

    @Test fun `original ASR OpenAI request and response remain compatible`() {
        val body = JSONObject(RemoteAsrTranscriber.buildRequestBody("qwen3-asr-flash", "QUJD"))
        assertFalse(body.getBoolean("stream"))
        assertEquals("zh", body.getJSONObject("asr_options").getString("language"))
        assertEquals("拍照", RemoteAsrTranscriber.parseResponseText("""{"choices":[{"message":{"content":"拍照"}}]}"""))
    }
}
