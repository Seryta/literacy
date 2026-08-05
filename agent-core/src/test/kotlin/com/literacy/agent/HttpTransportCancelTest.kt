package com.literacy.agent

import com.literacy.agent.provider.OkHttpTransport
import com.literacy.agent.provider.ProviderException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * review-09 W7：P1-13 OkHttpTransport.cancelActive 取消在途请求。
 * 同步 execute() 只能通过 Call.cancel() 中断；取消后：
 * - 在途 post 抛 ProviderException(retryable=false)（不得触发 HttpLlmProvider 超时重试续跑）
 * - 后续新请求被拒绝（页面已离开，不留后台副作用）
 */
class HttpTransportCancelTest {

    @Test
    fun `cancelActive 取消在途请求并抛非可重试 ProviderException（P1-13）`() {
        // daemon 线程池：测试结束不等待 handler 挂起线程，避免拖慢套件
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        // 请求挂起不响应：给 cancel 制造时间窗
        server.createContext("/hang") { exchange ->
            try { Thread.sleep(8_000) } catch (e: InterruptedException) {}
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val transport = OkHttpTransport(timeoutSeconds = 60)
            val port = server.address.port
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<String> {
                    transport.post("http://127.0.0.1:$port/hang", "{}", emptyMap()).body
                }
                Thread.sleep(300)   // 等请求已发出（挂起中）
                transport.cancelActive()
                val e = try {
                    future.get(10, TimeUnit.SECONDS)
                    fail("取消后 post 应抛异常，却正常返回")
                } catch (ex: java.util.concurrent.ExecutionException) {
                    ex.cause
                }
                assertTrue(e is ProviderException, "取消应映射为 ProviderException，实际 ${e?.javaClass?.simpleName}")
                assertEquals("传输已取消（页面已离开）", e.message)
                assertTrue(!e.retryable, "取消导致的失败不可重试（避免 HttpLlmProvider 重试续跑）")
            } finally {
                executor.shutdownNow()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `cancelActive 后新请求被拒绝（非可重试，P1-13）`() {
        val transport = OkHttpTransport()
        transport.cancelActive()
        val e = try {
            transport.post("http://127.0.0.1:1/x", "{}", emptyMap())
            fail("离页后新请求应被拒绝")
        } catch (ex: ProviderException) {
            ex
        }
        assertTrue(!e.retryable, "离页后新请求不得触发重试续跑")
    }
}
