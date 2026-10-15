package com.insta360.kmpsdk.demo.touchscene

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudTtsContractTest {
    @Test fun `cloud turn is busy immediately including synthesis preparation`() {
        val turn = CloudSpeechTurn()
        assertFalse(turn.isSpeaking())
        val token = turn.begin()
        assertTrue(turn.isSpeaking())
        assertTrue(turn.isCurrent(token))
        assertTrue(turn.finish(token))
        assertFalse(turn.isSpeaking())
    }

    @Test fun `stop rejects late synthesis results and completions`() {
        val turn = CloudSpeechTurn()
        val old = turn.begin()
        assertTrue(turn.cancel())
        assertFalse(turn.isCurrent(old))
        assertFalse(turn.finish(old))
        assertFalse(turn.isSpeaking())
    }

    @Test fun `old completion cannot clear a newer speech turn`() {
        val turn = CloudSpeechTurn()
        val old = turn.begin()
        turn.cancel()
        val current = turn.begin()
        assertFalse(turn.finish(old))
        assertTrue(turn.isSpeaking())
        assertTrue(turn.isCurrent(current))
        assertTrue(turn.finish(current))
    }

    @Test fun `synthesis retains verified CosyVoice model voice and WAV protocol`() {
        val body = JSONObject(CloudTtsProtocol.body("主体在画面中央。"))
        assertEquals("cosyvoice-v3-flash", body.getString("model"))
        val input = body.getJSONObject("input")
        assertEquals("主体在画面中央。", input.getString("text"))
        assertEquals("longanyang", input.getString("voice"))
        assertEquals("wav", input.getString("format"))
        assertEquals(24_000, input.getInt("sample_rate"))
        assertTrue(1_500L + CloudTtsFallback.PREPARATION_TIMEOUT_MS < 8_000L)
    }

    @Test fun `only official Beijing synthesis endpoint receives bearer credentials`() {
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer",
            CloudTtsProtocol.endpoint("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions").toString())
        listOf("https://api.deepseek.com/chat/completions", "https://sub.dashscope.aliyuncs.com/chat/completions",
            "http://dashscope.aliyuncs.com/chat/completions", "https://user:secret@dashscope.aliyuncs.com/chat/completions",
            "https://dashscope.aliyuncs.com:8443/chat/completions").forEach {
            assertTrue(runCatching { CloudTtsProtocol.endpoint(it) }.isFailure)
        }
    }

    @Test fun `signed OSS audio upgrades HTTPS without changing query and refuses foreign hosts`() {
        assertEquals("https://fixture.oss-cn-beijing.aliyuncs.com/test.wav?signature=fixture&expires=123",
            CloudTtsProtocol.audioUrl("""{"output":{"audio":{"url":"http://fixture.oss-cn-beijing.aliyuncs.com:80/test.wav?signature=fixture&expires=123"}}}"""))
        listOf("https://attacker.invalid/test.wav", "https://fixture.oss-cn-beijing.aliyuncs.com.attacker.invalid/test.wav",
            "https://fixture.maas.aliyuncs.com/test.wav", "https://user:secret@fixture.oss-cn-beijing.aliyuncs.com/test.wav",
            "https://fixture.oss-cn-beijing.aliyuncs.com:8443/test.wav").forEach {
            val body = JSONObject().put("output", JSONObject().put("audio", JSONObject().put("url", it))).toString()
            assertTrue(runCatching { CloudTtsProtocol.audioUrl(body) }.isFailure)
        }
    }
}
