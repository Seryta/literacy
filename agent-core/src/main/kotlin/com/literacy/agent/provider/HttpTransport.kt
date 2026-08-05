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

    // review-09 P1-13：在途 Call 引用 + 取消标记——同步 execute() 只能通过 cancel() 中断
    // review-09 S2：单飞行约束——同一时间只有一个在途请求（App 串行 submit 队列保证）；
    // 若未来允许并发请求，activeCall 需改为并发容器（如 CopyOnWriteArraySet<Call>）
    @Volatile private var activeCall: okhttp3.Call? = null
    @Volatile private var cancelled = false

    /** 取消当前在途请求（离页取消；取消后拒绝新请求且不重试）。 */
    fun cancelActive() {
        cancelled = true
        activeCall?.cancel()
    }

    override fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
        // 已取消（离页）：拒绝新请求（retryable=false，避免 HttpLlmProvider 重试续跑）
        if (cancelled) throw ProviderException("传输已取消（页面已离开）", retryable = false)
        val requestBuilder = okhttp3.Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        val call = client.newCall(requestBuilder.build())
        // 残余修复：登记 activeCall 后二次检查取消——cancelActive 可能发生在检查 cancelled 与
        // 赋值 activeCall 之间（取消线程此时看不到 Call）；登记后再检查，取消则立即 cancel 不执行
        activeCall = call
        if (cancelled) {
            call.cancel()
            activeCall = null
            throw ProviderException("传输已取消（页面已离开）", retryable = false)
        }
        try {
            call.execute().use { resp ->
                return HttpResponse(resp.code, resp.body?.string() ?: "")
            }
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            // 取消导致的 IOException（Canceled）按非可重试处理——不触发超时重试续跑
            if (cancelled) throw ProviderException("传输已取消（页面已离开）", retryable = false)
            throw e
        } finally {
            if (activeCall === call) activeCall = null
        }
    }
}
