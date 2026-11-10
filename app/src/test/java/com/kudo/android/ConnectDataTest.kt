package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ConnectDataTest {
    @Test fun receivedCookiesSurviveCancellationBeforeConnectResponseDelivery() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val saved = java.util.concurrent.CopyOnWriteArrayList<String>()
            val cookies = object : SessionCookies {
                override fun get(url: String) = "connect=old"
                override fun set(url: String, cookie: String) { saved += cookie }
            }
            val waiting = Job()
            val delivered = java.util.concurrent.CountDownLatch(1)
            server.enqueue(MockResponse().addHeader("Set-Cookie", "connect=renewed; Path=/; HttpOnly").setBody("<html></html>"))
            val repository = ConnectRepository(cookies, "fixture", server.url("/"), route = { base ->
                Call.Factory { request ->
                    val delegate = base.newCall(request)
                    object : Call by delegate {
                        override fun enqueue(responseCallback: Callback) = delegate.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: java.io.IOException) = responseCallback.onFailure(call, e)
                            override fun onResponse(call: Call, response: Response) {
                                waiting.cancel()
                                try { responseCallback.onResponse(call, response) } finally { delivered.countDown() }
                            }
                        })
                    }
                }
            })
            val task = launch(waiting + Dispatchers.IO) { repository.load() }
            try {
                assertTrue(withContext(Dispatchers.IO) { delivered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                task.join()
                assertEquals(listOf("connect=renewed; Path=/; HttpOnly"), saved)
            } finally { waiting.cancel(); task.cancelAndJoin() }
        }
    }
    @Test fun parsesServerConditionsWithoutHardcodedThresholds() {
        val report = parseTrustReport("""<div class="card"><h2 class="card-title">等级评估</h2><div class="badge">进行中</div>
            <div class="card-subtitle">最近 100 天</div>
            <div class="tl3-ring"><span class="tl3-ring-label">访问天数</span><div class="tl3-ring-circle met" style="--val:82;--max:50"></div></div>
            <div class="tl3-bar-item"><span class="tl3-bar-label">已读帖子</span><span class="tl3-bar-nums">1,200 / 2,000</span><div class="tl3-bar-fill" style="--val:1200;--max:2000"></div></div>
            <div class="tl3-quota-card unmet"><span class="tl3-quota-label">标记</span><span class="tl3-quota-nums">2 / 1</span></div>
            <div class="tl3-veto-item unmet"><div class="tl3-veto-back"><span class="tl3-veto-label">账号状态</span><span class="tl3-veto-value">未达标</span></div></div>
            <div class="status-unmet">尚未满足条件</div><div class="text-hint">以网站结果为准</div></div>""")
        assertEquals(4, report.metrics.size)
        assertEquals("82 / 50", report.metrics[0].value)
        assertEquals(1f, report.metrics[0].progress)
        assertEquals(.6f, report.metrics[1].progress)
        assertFalse(report.metrics[2].met)
        assertEquals("未达标", report.metrics[3].value)
    }
    @Test fun lowLevelEmptyStateKeepsWebsiteExplanationRatherThanInventingRequirements() {
        val report = parseTrustReport("""<div class="card empty-state"><h2 class="card-title">当前 1 级</h2><p>该等级没有周期评估</p></div>""")
        assertTrue(report.metrics.isEmpty())
        assertEquals("该等级没有周期评估", report.hint)
    }
    @Test fun redirectsRequireWebAuthenticationAndDoNotForwardCookies() = runBlocking {
        val server = MockWebServer()
        val origins = mutableListOf<String>()
        val cookies = object : SessionCookies {
            override fun get(url: String): String { origins += url; return "scoped=session" }
            override fun set(url: String, cookie: String) {}
        }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://outside.test/auth"))
            try { ConnectRepository(cookies, "fixture", server.url("/")).load(); fail("认证跳转不能当作等级数据") }
            catch (e: ApiFailure) { assertEquals(FailureKind.SIGN_IN, e.kind) }
            assertEquals(1, server.requestCount)
            assertEquals(listOf(server.url("/").toString()), origins)
        } finally { server.shutdown() }
    }

    @Test fun cloudflareChallengeRequiresManualWebVerification() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(403)
                .setHeader("cf-mitigated", "challenge")
                .setBody("<html>Just a moment...</html>"))
            try {
                ConnectRepository(NoCookies, "fixture", server.url("/")).load()
                fail("Cloudflare challenge must not be parsed as a server error")
            } catch (e: ApiFailure) {
                assertEquals(FailureKind.VERIFICATION, e.kind)
                assertTrue(e.message.contains("手动完成验证"))
            }
        } finally { server.shutdown() }
    }
}
