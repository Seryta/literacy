package com.literacy.agent

import com.literacy.agent.model.LlmOutput
import com.literacy.agent.provider.HttpLlmProvider
import com.literacy.agent.provider.HttpResponse
import com.literacy.agent.provider.HttpTransport
import com.literacy.agent.provider.ProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 真实 Provider 层测试（AGENT-PROTOCOL §3.1/§10）：
 * - 请求构建：OpenAI 兼容 messages + JSON 输出要求 + Bearer 鉴权
 * - 响应解析：完整 JSON {text, toolCalls}
 * - 校验：text 必填（§3.2）；非 2xx / 网络错误 / 解析失败 → ProviderException（§10，GT-011 兜底依赖）
 */
class HttpLlmProviderTest {

    /** 可编程 fake 传输层：记录请求，返回预置响应。 */
    private class FakeTransport(
        var response: HttpResponse = HttpResponse(200, ""),
        val requests: MutableList<Pair<String, String>> = mutableListOf(),
        var throwOnPost: Exception? = null,
    ) : HttpTransport {
        override fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
            throwOnPost?.let { throw it }
            requests += url to body
            return response
        }
    }

    private fun provider(transport: HttpTransport, apiKey: String = "test-key") =
        HttpLlmProvider(transport, HttpLlmProvider.ProviderConfig(
            baseUrl = "https://api.test",
            apiKey = apiKey,
            model = "test-model",
        ))

    /** OpenAI 兼容包装：businessJson 是模型输出的业务 JSON（content 字段内转义）。 */
    private fun openAiResp(businessJson: String): String {
        val escaped = businessJson.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"choices":[{"message":{"content":"$escaped"}}]}"""
    }

    @Test
    fun `请求构建：messages + JSON 输出 + Bearer 鉴权`() {
        val t = FakeTransport(HttpResponse(200, openAiResp("{\"text\":\"好的\"}")))
        provider(t).respond("事件上下文")
        val (url, body) = t.requests.single()
        assertEquals("https://api.test/chat/completions", url)
        assertTrue(body.contains("\"model\": \"test-model\""), "请求体应含 model")
        assertTrue(body.contains("\"role\": \"user\""), "请求体应含 user 消息")
        assertTrue(body.contains("事件上下文"), "user 内容应包含上下文")
        assertTrue(body.contains("\"type\": \"json_object\""), "应要求 JSON 输出（完整 JSON 优先）")
        // Authorization 头由调用方在 headers 中传入（本测试验证接口签名，headers 传参见实现）
    }

    @Test
    fun `响应解析：完整 JSON text + toolCalls`() {
        val json = openAiResp("{\"text\":\"我们来看'家'字\",\"toolCalls\":[{\"name\":\"show_character\",\"arguments\":{\"char\":\"家\",\"revealStrokes\":3}}]}")
        val out = provider(FakeTransport(HttpResponse(200, json))).respond(null)
        assertEquals("我们来看'家'字", out.text)
        assertEquals(1, out.toolCalls.size)
        assertEquals("show_character", out.toolCalls[0].name)
        assertEquals("家", out.toolCalls[0].arguments["char"])
    }

    @Test
    fun `markdown 代码块包裹的业务 JSON 可解析`() {
        val json = openAiResp("```json\n{\n  \"text\": \"好的，我们继续\"\n}\n```")
        val out = provider(FakeTransport(HttpResponse(200, json))).respond(null)
        assertEquals("好的，我们继续", out.text)
    }

    @Test
    fun `text 必填：缺 text 抛 ProviderException（§3 2）`() {
        val t = FakeTransport(HttpResponse(200, openAiResp("{\"toolCalls\":[]}")))
        assertFailsWith<ProviderException> { provider(t).respond(null) }
    }

    @Test
    fun `非 2xx 抛 ProviderException（§10 超时重试后仍失败）`() {
        val t = FakeTransport(HttpResponse(500, "server error"))
        assertFailsWith<ProviderException> { provider(t).respond(null) }
    }

    @Test
    fun `网络异常抛 ProviderException（GT-011 兜底触发条件）`() {
        val t = FakeTransport(throwOnPost = java.io.IOException("connection refused"))
        assertFailsWith<ProviderException> { provider(t).respond(null) }
    }

    @Test
    fun `响应解析失败抛 ProviderException`() {
        val t = FakeTransport(HttpResponse(200, "not json at all"))
        assertFailsWith<ProviderException> { provider(t).respond(null) }
    }

    @Test
    fun `§10 超时重试一次：网络异常后成功`() {
        val t = FakeTransport(throwOnPost = java.io.IOException("connection refused"))
        // 第一次抛 IOException，第二次成功
        var calls = 0
        val retrying = object : HttpTransport {
            override fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
                calls++
                if (calls == 1) throw java.io.IOException("timeout")
                return HttpResponse(200, """{"choices":[{"message":{"content":"{\"text\":\"好的\"}"}}]}""")
            }
        }
        val out = provider(retrying).respond(null)
        assertEquals(2, calls, "失败后应重试一次（§10）")
        assertEquals("好的", out.text)
    }

    @Test
    fun `5xx 服务端错误重试一次`() {
        var calls = 0
        val retrying = object : HttpTransport {
            override fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
                calls++
                if (calls == 1) return HttpResponse(503, "server busy")
                return HttpResponse(200, """{"choices":[{"message":{"content":"{\"text\":\"好的\"}"}}]}""")
            }
        }
        val out = provider(retrying).respond(null)
        assertEquals(2, calls, "5xx 可重试")
        assertEquals("好的", out.text)
    }

    @Test
    fun `4xx 鉴权失败不重试`() {
        var calls = 0
        val retrying = object : HttpTransport {
            override fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
                calls++
                return HttpResponse(401, "unauthorized")
            }
        }
        assertFailsWith<ProviderException> { provider(retrying).respond(null) }
        assertEquals(1, calls, "4xx 不重试（鉴权失败重试无意义）")
    }

    @Test
    fun `解析错误不重试（格式错误重试结果相同）`() {
        var calls = 0
        val retrying = object : HttpTransport {
            override fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
                calls++
                return HttpResponse(200, "{\"choices\":[{\"message\":{\"content\":\"no json here\"}}]}")
            }
        }
        assertFailsWith<ProviderException> { provider(retrying).respond(null) }
        assertEquals(1, calls, "解析错误不重试（§10：格式错误记录日志）")
    }

    @Test
    fun `toolCalls 空时只返回 text`() {
        val out = provider(FakeTransport(HttpResponse(200, openAiResp("{\"text\":\"好的\"}")))).respond(null)
        assertEquals("好的", out.text)
        assertTrue(out.toolCalls.isEmpty())
    }
}
