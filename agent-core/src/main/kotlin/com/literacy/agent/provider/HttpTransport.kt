package com.literacy.agent.provider

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** HTTP 响应。 */
data class HttpResponse(val statusCode: Int, val body: String)

/**
 * 传输层抽象：真实实现用 OkHttp（Android 端同用），测试注入 Fake 实现
 * 验证请求构建 / 响应解析 / 错误处理，不依赖真实网络。
 */
interface HttpTransport {
    /** 发起 POST 请求，返回原始响应。网络异常时抛 ProviderException。 */
    fun post(url: String, body: String, headers: Map<String, String>): HttpResponse
}

/** OkHttp 实现（OkHttpTransport）。deepseek 为推理模型，响应可达 1-2 分钟，超时放宽。 */
class OkHttpTransport(
    private val timeoutSeconds: Long = 120,
) : HttpTransport {
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
        val requestBuilder = okhttp3.Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        client.newCall(requestBuilder.build()).execute().use { resp ->
            return HttpResponse(resp.code, resp.body?.string() ?: "")
        }
    }
}
