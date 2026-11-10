package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiscourseClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DiscourseClient
    @Before fun setup() { server = MockWebServer(); server.start(); client = DiscourseClient(NoCookies, server.url("/")) }
    @After fun close() { server.shutdown() }

    @Test fun changingAccountDuringCsrfLoadingCannotSubmitInTheNewAccount() = runBlocking {
        val cookies = SessionCookieStore(object : CookieStorage {
            override fun get(url: String) = "_t=fixture"
            override fun set(url: String, cookie: String) {}
            override fun clear() {}
            override fun flush() {}
        })
        val readingBody = java.util.concurrent.CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) { readingBody.countDown() }
        }).build()
        val api = DiscourseClient(cookies, server.url("/"), http = http)
        server.enqueue(MockResponse().setBody("""{"csrf":"old-account-csrf"}""").setBodyDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody("{}"))
        val task = async(Dispatchers.IO) { runCatching { api.write("POST", "/posts.json", mapOf("raw" to "fixture")) } }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS) })
        assertTrue(withContext(Dispatchers.IO) { readingBody.await(3, java.util.concurrent.TimeUnit.SECONDS) })
        cookies.invalidate()
        assertTrue(task.await().isFailure)
        assertEquals("A request started in an earlier account must not post as the new account", 1, server.requestCount)
    }

    @Test fun anOldResponseCannotReplaceTheSessionEstablishedByTheWebView() = runBlocking {
        var value = "_t=old"
        val cookies = object : SessionCookies {
            override fun get(url: String) = value
            override fun set(url: String, cookie: String) { value = cookie.substringBefore(';') }
        }
        server.enqueue(MockResponse().addHeader("Set-Cookie", "_t=stale; Path=/; HttpOnly").setBody("{}"))
        val api = DiscourseClient(cookies, server.url("/"), route = { base ->
            Call.Factory { request ->
                val delegate = base.newCall(request)
                object : Call by delegate {
                    override fun enqueue(callback: Callback) = delegate.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) = callback.onFailure(call, e)
                        override fun onResponse(call: Call, response: Response) {
                            value = "_t=web-login"
                            callback.onResponse(call, response)
                        }
                    })
                }
            }
        })
        api.get("/session/current.json")
        assertEquals("_t=web-login", value)
    }

    @Test fun receivedRenewalIsSavedEvenWhenTheWaitingCoroutineWasCancelled() = runBlocking {
        val saved = java.util.concurrent.CopyOnWriteArrayList<String>()
        val cookies = object : SessionCookies {
            override fun get(url: String) = "_t=old"
            override fun set(url: String, cookie: String) { saved += cookie }
        }
        val waiting = Job()
        val delivered = java.util.concurrent.CountDownLatch(1)
        val responseCookie = "_t=renewed; Path=/; HttpOnly; Max-Age=3600; SameSite=Lax"
        server.enqueue(MockResponse().addHeader("Set-Cookie", responseCookie).setBody("{}"))
        val api = DiscourseClient(cookies, server.url("/"), route = { base ->
            Call.Factory { request ->
                val delegate = base.newCall(request)
                object : Call by delegate {
                    override fun enqueue(callback: Callback) = delegate.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) = callback.onFailure(call, e)
                        override fun onResponse(call: Call, response: Response) {
                            waiting.cancel()
                            try { callback.onResponse(call, response) } finally { delivered.countDown() }
                        }
                    })
                }
            }
        })
        val task = launch(waiting + Dispatchers.IO) { api.get("/session/current.json") }
        try {
            assertTrue(withContext(Dispatchers.IO) { delivered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            task.join()
            assertEquals(listOf(responseCookie), saved)
        } finally { waiting.cancel(); task.cancelAndJoin() }
    }

    @Test fun serverPaginationCannotSendSessionToAnotherOrigin() {
        listOf("https://attacker.example/latest.json", "//attacker.example/latest.json", "http://user:secret@localhost:${server.port}/").forEach {
            assertThrows(IllegalArgumentException::class.java) { client.resolve(it) }
        }
        assertEquals(server.url("/latest.json?page=2"), client.resolve("/latest.json?page=2"))
    }
    @Test fun writeFetchesCsrfAndIsNotRetriedAfterServerFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"csrf":"test-token"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"errors":["稍后重试"]}"""))
        val failure = runCatching { client.write("POST", "/posts.json", mapOf("raw" to "测试内容")) }.exceptionOrNull()
        assertEquals(FailureKind.SERVER, (failure as ApiFailure).kind)
        assertEquals(2, server.requestCount)
        assertEquals("/session/csrf", server.takeRequest().path)
        val post = server.takeRequest()
        assertEquals("test-token", post.getHeader("X-CSRF-Token"))
        assertEquals("POST", post.method)
        assertTrue(post.body.readUtf8().contains("raw="))
    }
    @Test fun challengeIsNotReportedAsEmptySuccessfulContent() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(403).setHeader("cf-mitigated", "challenge").setBody("<html>Verify</html>")) }
        val failure = runCatching { client.get("/latest.json") }.exceptionOrNull() as ApiFailure
        assertEquals(FailureKind.VERIFICATION, failure.kind)
        assertTrue(failure.message.contains("Cloudflare"))
        assertEquals(2, server.requestCount)
    }
    @Test fun htmlChallengeWithoutCloudflareHeaderIsReportedForManualVerification() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("<html><title>Just a moment...</title><script src='/cdn-cgi/challenge-platform/a.js'></script></html>")) }
        val failure = runCatching { client.get("/latest.json") }.exceptionOrNull() as ApiFailure
        assertEquals(FailureKind.VERIFICATION, failure.kind)
        assertTrue(failure.message.contains("手动完成验证"))
    }
    @Test fun challengeMarkersInsideJsonAreNotTreatedAsCloudflare() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"message\":\"turnstile challenge-platform\"}"))
        val result = client.get("/latest")
        assertEquals("turnstile challenge-platform", result.getString("message"))
    }
    @Test fun nonHtmlForbiddenResponseKeepsForbiddenMeaning() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        val failure = runCatching { client.get("/latest") }.exceptionOrNull() as ApiFailure
        assertEquals(FailureKind.FORBIDDEN, failure.kind)
    }
    @Test fun ordinaryHtml403AndLoginTurnstileAreNotCloudflareChallenges() = runBlocking {
        listOf("<html><title>Access denied</title></html>",
            "<html><title>Log in</title><script src='https://challenges.cloudflare.com/turnstile/v0/api.js'></script></html>",
            "<html><title>Article</title><pre>Just a moment... challenge-platform cf-chl-</pre></html>",
            "<html><title>Forum</title><script src='/cdn-cgi/challenge-platform/scripts/jsd/main.js'></script></html>").forEach { body ->
            server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "text/html").setBody(body))
            assertEquals(FailureKind.FORBIDDEN, (runCatching { client.get("/latest.json") }.exceptionOrNull() as ApiFailure).kind)
        }
        assertEquals(4, server.requestCount)
    }
    @Test fun malformedSuccessfulResponseIsAServerErrorInsteadOfVerification() = runBlocking {
        server.enqueue(MockResponse().setBody("upstream response unavailable"))
        assertEquals(FailureKind.SERVER, (runCatching { client.get("/latest.json") }.exceptionOrNull() as ApiFailure).kind)
        assertEquals(1, server.requestCount)
    }
    @Test fun transientChallengeRetriesTheGetWithTheUpdatedCookie() = runBlocking {
        var cookie = "initial=1"
        val cookies = object : SessionCookies {
            override fun get(url: String) = cookie
            override fun set(url: String, value: String) { cookie = value.substringBefore(';') }
        }
        val sharedClient = DiscourseClient(cookies, server.url("/"))
        server.enqueue(MockResponse().setResponseCode(403).setHeader("cf-mitigated", "challenge")
            .addHeader("Set-Cookie", "cf_clearance=fresh; Path=/").setBody("<html>Verify</html>"))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertTrue(sharedClient.get("/latest.json").getBoolean("ok"))
        assertEquals(2, server.requestCount)
        assertEquals("initial=1", server.takeRequest().getHeader("Cookie"))
        assertEquals("cf_clearance=fresh", server.takeRequest().getHeader("Cookie"))
    }
    @Test fun challengeOnAWriteDoesNotReplayTheMutation() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"csrf":"test-token"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setHeader("cf-mitigated", "challenge").setBody("<html>Verify</html>"))
        assertEquals(FailureKind.VERIFICATION, (runCatching { client.write("POST", "/posts.json", mapOf("raw" to "body")) }.exceptionOrNull() as ApiFailure).kind)
        assertEquals(2, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
    }
    @Test fun rateLimitPreservesRetryAfterAndDoesNotRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody("""{"errors":["慢一点"]}"""))
        val failure = runCatching { client.get("/latest.json") }.exceptionOrNull() as ApiFailure
        assertEquals(FailureKind.RATE_LIMIT, failure.kind)
        assertEquals(30, failure.retryAfter)
        assertEquals(1, server.requestCount)
    }
    @Test fun rateLimitStopsOtherEndpointsAndSessionChecksDuringCooldown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        server.enqueue(MockResponse().setBody("{}"))
        assertEquals(FailureKind.RATE_LIMIT, (runCatching { client.get("/latest.json") }.exceptionOrNull() as ApiFailure).kind)
        client.resetSession()
        val failure = runCatching { client.get("/site.json") }.exceptionOrNull()
        assertTrue("冷却期间分类请求必须在本地失败", failure is ApiFailure && failure.kind == FailureKind.RATE_LIMIT)
        assertEquals("其他接口不能继续加重限流", 1, server.requestCount)
    }
    @Test fun rateLimitStopsCsrfAndMutationsInsteadOfQueuingOrReplayingThem() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        server.enqueue(MockResponse().setBody("""{"csrf":"token"}"""))
        server.enqueue(MockResponse().setBody("{}"))
        runCatching { client.get("/latest.json") }
        val failure = runCatching { client.write("POST", "/posts.json", mapOf("raw" to "body")) }.exceptionOrNull()
        assertTrue("冷却中不能发 CSRF 或写入", failure is ApiFailure && failure.kind == FailureKind.RATE_LIMIT)
        assertEquals(1, server.requestCount)
    }
    @Test fun jsonCooldownExpiresAndRetryReportsTheRemainingSecondsWithoutNetwork() = runBlocking {
        var now = 0L
        val sharedClient = DiscourseClient(NoCookies, server.url("/"),
            cooldown = com.kudo.android.network.RequestCooldown({ now }))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"extras":{"wait_seconds":30}}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val first = runCatching { sharedClient.get("/latest.json") }.exceptionOrNull() as ApiFailure
        assertEquals(30, first.retryAfter)
        now = 12_100
        val retry = runCatching { sharedClient.get("/site.json") }.exceptionOrNull() as ApiFailure
        assertEquals(18, retry.retryAfter)
        assertTrue(retry.message.contains("18 秒"))
        assertEquals(1, server.requestCount)
        now = 30_000
        assertTrue(sharedClient.get("/site.json").getBoolean("ok"))
        assertEquals(2, server.requestCount)
    }
    @Test fun unauthorizedRequiresLogin() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertEquals(FailureKind.SIGN_IN, (runCatching { client.get("/notifications") }.exceptionOrNull() as ApiFailure).kind)
    }
    @Test fun discourseNotLoggedIn403RequiresLoginInsteadOfRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"errors":["您需要登录才能执行此操作。"],"error_type":"not_logged_in"}"""))
        assertEquals(FailureKind.SIGN_IN, (runCatching { client.get("/search.json") }.exceptionOrNull() as ApiFailure).kind)
    }
    @Test fun redirectDoesNotForwardRequestAutomatically() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/"))
        assertTrue(runCatching { client.get("/latest.json") }.isFailure)
        assertEquals(1, server.requestCount)
    }
    @Test fun categoryCanonicalRedirectFollowsOnlySameOriginGet() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/c/develop/4/l/latest.json"))
        server.enqueue(MockResponse().setBody("""{"topic_list":{"topics":[{"id":42,"title":"分类话题"}]}}"""))
        val page = CommunityRepository(client).topics(categoryId = 4)
        assertEquals(42, page.topics.single().id)
        assertEquals("/c/4/l/latest.json", server.takeRequest().path)
        assertEquals("/c/develop/4/l/latest.json", server.takeRequest().path)
    }
    @Test fun writeRedirectIsNeverReplayed() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"csrf":"test-token"}"""))
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/posts.json"))
        assertTrue(runCatching { client.write("POST", "/posts", mapOf("raw" to "测试")) }.isFailure)
        assertEquals(2, server.requestCount)
    }
    @Test fun sameOriginRedirectLoopStopsAfterThreeHops() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/loop")) }
        assertTrue(runCatching { client.get("/loop") }.isFailure)
        assertEquals(4, server.requestCount)
    }
}
