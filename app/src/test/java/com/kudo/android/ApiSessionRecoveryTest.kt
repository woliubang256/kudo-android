package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ApiSessionRecoveryTest {
    private class Cookies(@Volatile var header: String = "_t=fixture") : SessionCookies {
        @Volatile private var generation = 0L
        override fun get(url: String) = header
        override fun set(url: String, cookie: String) {
            if (cookie.substringBefore('=') == "_t") header = if (cookie.contains("Max-Age=0")) "" else cookie.substringBefore(';')
        }
        override fun snapshot(url: String) = SessionCookieSnapshot(header, generation)
        override fun isCurrent(snapshot: SessionCookieSnapshot) = snapshot.generation == generation
        override fun invalidate() { generation++ }
    }

    private fun validSession() = MockResponse().setBody("""{"current_user":{"id":7,"username":"fixture"}}""")
    private fun unauthorized(status: Int = 401) = MockResponse().setResponseCode(status).setBody("""{"error_type":"not_logged_in"}""")

    @Test fun anAuthenticationSignalIsVerifiedBeforeRetryingTheReadOnce() = runBlocking {
        for (status in listOf(401, 403)) MockWebServer().use { server ->
            server.start()
            server.enqueue(unauthorized(status))
            server.enqueue(validSession())
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            val result = runCatching { DiscourseClient(Cookies(), server.url("/")).get("/private.json", mapOf("q" to "fixture")) }
            assertTrue("A valid session should recover the read: ${result.exceptionOrNull()}", result.isSuccess)
            assertTrue(result.getOrThrow().getBoolean("ok"))
            assertEquals(3, server.requestCount)
            assertEquals("/private.json?q=fixture", server.takeRequest().path)
            assertEquals("/session/current.json", server.takeRequest().path)
            assertEquals("/private.json?q=fixture", server.takeRequest().path)
        }
    }

    @Test fun aConfirmedGuestDoesNotReplayTheFailedReadOrRecurseIntoSessionChecks() = runBlocking {
        for ((status, body) in listOf(200 to """{"current_user":null}""", 401 to "{}", 404 to "{}")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(unauthorized())
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                val failure = runCatching { DiscourseClient(Cookies(), server.url("/")).get("/private.json") }.exceptionOrNull()
                assertEquals(FailureKind.SIGN_IN, (failure as? ApiFailure)?.kind)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun aSecondAuthenticationFailureStopsWithoutAnotherProbeOrRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(unauthorized())
            server.enqueue(validSession())
            server.enqueue(unauthorized())
            val failure = runCatching { DiscourseClient(Cookies(), server.url("/")).get("/private.json") }.exceptionOrNull()
            assertEquals(FailureKind.SIGN_IN, (failure as? ApiFailure)?.kind)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun inconclusiveSessionResponsesKeepTheirErrorInsteadOfAskingForLogin() = runBlocking {
        for ((status, body) in listOf(503 to "{}", 200 to "{}", 200 to """{"current_user":[]}""",
            200 to """{"current_user":{"id":0,"username":"fixture"}}""", 200 to """{"current_user":{"id":7,"username":{}}}""")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(unauthorized())
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                val failure = runCatching { DiscourseClient(Cookies(), server.url("/")).get("/private.json") }.exceptionOrNull()
                assertEquals(FailureKind.SERVER, (failure as? ApiFailure)?.kind)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun guestsAndExplicitlyDeletedCredentialsDoNotTriggerRecovery() = runBlocking {
        for (header in listOf("", "_t=fixture")) MockWebServer().use { server ->
            server.start()
            server.enqueue(unauthorized().addHeader("Set-Cookie", "_t=; Path=/; Max-Age=0"))
            val cookies = Cookies(header)
            val failure = runCatching { DiscourseClient(cookies, server.url("/")).get("/private.json") }.exceptionOrNull()
            assertEquals(FailureKind.SIGN_IN, (failure as? ApiFailure)?.kind)
            assertEquals(1, server.requestCount)
            assertEquals("", cookies.header)
        }
    }

    @Test fun permissionAndRateLimitErrorsDoNotStartSessionChecks() = runBlocking {
        for ((status, kind) in listOf(403 to FailureKind.FORBIDDEN, 429 to FailureKind.RATE_LIMIT)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
                val failure = runCatching { DiscourseClient(Cookies(), server.url("/")).get("/private.json") }.exceptionOrNull()
                assertEquals(kind, (failure as? ApiFailure)?.kind)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun writesAndCsrfEndpointsNeverEnterAutomaticSessionRecovery() = runBlocking {
        for (csrfSucceeds in listOf(true, false)) MockWebServer().use { server ->
            server.start()
            if (csrfSucceeds) server.enqueue(MockResponse().setBody("""{"csrf":"fixture"}"""))
            server.enqueue(unauthorized())
            val failure = runCatching { DiscourseClient(Cookies(), server.url("/")).write("POST", "/posts.json") }.exceptionOrNull()
            assertEquals(FailureKind.SIGN_IN, (failure as? ApiFailure)?.kind)
            assertEquals(if (csrfSucceeds) 2 else 1, server.requestCount)
        }
    }

    @Test fun concurrentAuthenticationSignalsShareOneProbe() = runBlocking {
        MockWebServer().use { server ->
            val requests = ConcurrentHashMap<String, AtomicInteger>()
            val wave = CountDownLatch(4)
            val probes = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/session/current.json") {
                        probes.incrementAndGet()
                        return validSession()
                    }
                    val number = requests.computeIfAbsent(request.path!!) { AtomicInteger() }.incrementAndGet()
                    if (number == 1) {
                        wave.countDown()
                        check(wave.await(5, TimeUnit.SECONDS))
                        return unauthorized()
                    }
                    return MockResponse().setBody("""{"ok":true}""")
                }
            }
            server.start()
            val client = DiscourseClient(Cookies(), server.url("/"))
            val results = (1..4).map { id -> async(Dispatchers.IO) { runCatching { client.get("/private-$id.json") } } }.awaitAll()
            assertTrue("All reads should recover", results.all { it.isSuccess && it.getOrThrow().getBoolean("ok") })
            assertEquals(1, probes.get())
            assertEquals(9, server.requestCount)
        }
    }

    @Test fun aRecentSuccessfulProbeIsReusedForTheSameCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(unauthorized())
            server.enqueue(validSession())
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(unauthorized())
            server.enqueue(MockResponse().setBody("{}"))
            val client = DiscourseClient(Cookies(), server.url("/"))
            val first = runCatching { client.get("/one.json") }
            val second = runCatching { client.get("/two.json") }
            assertTrue(first.isSuccess && second.isSuccess)
            assertEquals(5, server.requestCount)
            val paths = List(5) { server.takeRequest().path }
            assertEquals(1, paths.count { it == "/session/current.json" })
        }
    }

    @Test fun theProbeCooldownExpiresAndChangedCredentialsBypassIt() = runBlocking {
        MockWebServer().use { server ->
            val requests = ConcurrentHashMap<String, AtomicInteger>()
            val probes = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/session/current.json") { probes.incrementAndGet(); return validSession() }
                    return if (requests.computeIfAbsent(request.path!!) { AtomicInteger() }.incrementAndGet() == 1) unauthorized()
                    else MockResponse().setBody("{}")
                }
            }
            server.start()
            val now = AtomicLong()
            val cookies = Cookies()
            val client = DiscourseClient(cookies, server.url("/"), authRecoveryClock = now::get)
            client.get("/one.json")
            now.set(29_999)
            client.get("/two.json")
            assertEquals(1, probes.get())
            now.set(30_000)
            client.get("/three.json")
            assertEquals(2, probes.get())
            cookies.header = "_t=renewed"
            client.get("/four.json")
            assertEquals(3, probes.get())
        }
    }

    @Test fun anInconclusiveProbeIsCooledDownButDoesNotPreventLaterRecovery() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(unauthorized())
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            server.enqueue(unauthorized())
            server.enqueue(unauthorized())
            server.enqueue(validSession())
            server.enqueue(MockResponse().setBody("{}"))
            val now = AtomicLong()
            val client = DiscourseClient(Cookies(), server.url("/"), authRecoveryClock = now::get)
            repeat(2) {
                val failure = runCatching { client.get("/private-$it.json") }.exceptionOrNull()
                assertEquals(FailureKind.SERVER, (failure as? ApiFailure)?.kind)
            }
            assertEquals(3, server.requestCount)
            now.set(30_000)
            client.get("/later.json")
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun aRenewalFromTheProbeIsUsedByTheRetryAndItsSharedResult() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(unauthorized())
            server.enqueue(validSession().addHeader("Set-Cookie", "_t=renewed; Path=/; HttpOnly"))
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(unauthorized())
            server.enqueue(MockResponse().setBody("{}"))
            val cookies = Cookies()
            val client = DiscourseClient(cookies, server.url("/"))
            client.get("/one.json")
            client.get("/two.json")
            assertEquals(5, server.requestCount)
            val requests = List(5) { server.takeRequest() }
            assertEquals("_t=renewed", cookies.header)
            requests.drop(2).forEach { assertEquals("_t=renewed", it.getHeader("Cookie")) }
            assertEquals(1, requests.count { it.path == "/session/current.json" })
        }
    }

    @Test fun aPreviousAccountsProbeCannotRetryAReadOrSeedTheNextAccountsCache() = runBlocking {
        MockWebServer().use { server ->
            val bodyStarted = CountDownLatch(1)
            val releaseBody = CountDownLatch(1)
            val http = okhttp3.OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun responseBodyStart(call: okhttp3.Call) {
                    if (call.request().url.encodedPath == "/session/current.json") {
                        bodyStarted.countDown()
                        check(releaseBody.await(5, TimeUnit.SECONDS))
                    }
                }
            }).build()
            server.start()
            server.enqueue(unauthorized())
            server.enqueue(validSession())
            val cookies = Cookies()
            val client = DiscourseClient(cookies, server.url("/"), http = http)
            val oldRead = async(Dispatchers.IO) { runCatching { client.get("/old.json") } }
            try {
                assertTrue(withContext(Dispatchers.IO) { bodyStarted.await(5, TimeUnit.SECONDS) })
                client.invalidateCookieRequests()
                cookies.header = "_t=new-account"
                releaseBody.countDown()
                assertEquals(FailureKind.NETWORK, (oldRead.await().exceptionOrNull() as? ApiFailure)?.kind)
                assertEquals(2, server.requestCount)
            } finally { releaseBody.countDown(); oldRead.cancelAndJoin() }
            server.enqueue(unauthorized())
            server.enqueue(validSession())
            server.enqueue(MockResponse().setBody("{}"))
            client.get("/new.json")
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun cancellingTheProbeOwnerDoesNotPoisonTheNextRecovery() = runBlocking {
        MockWebServer().use { server ->
            val requests = ConcurrentHashMap<String, AtomicInteger>()
            val probes = AtomicInteger()
            val probeStarted = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/session/current.json") {
                        if (probes.incrementAndGet() == 1) {
                            probeStarted.countDown()
                            return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        }
                        return validSession()
                    }
                    return if (requests.computeIfAbsent(request.path!!) { AtomicInteger() }.incrementAndGet() == 1) unauthorized()
                    else MockResponse().setBody("{}")
                }
            }
            server.start()
            val client = DiscourseClient(Cookies(), server.url("/"))
            val first = async(Dispatchers.IO) { client.get("/one.json") }
            try {
                assertTrue(withContext(Dispatchers.IO) { probeStarted.await(5, TimeUnit.SECONDS) })
            } finally { first.cancelAndJoin() }
            client.get("/two.json")
            assertEquals(2, probes.get())
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun aProbeChallengeRemainsAVerificationErrorAndIsNotRepeatedDuringCooldown() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(unauthorized())
            repeat(2) { server.enqueue(MockResponse().setResponseCode(403).addHeader("cf-mitigated", "challenge").setBody("<html>verify</html>")) }
            server.enqueue(unauthorized())
            val client = DiscourseClient(Cookies(), server.url("/"))
            repeat(2) {
                val failure = runCatching { client.get("/private-$it.json") }.exceptionOrNull()
                assertEquals(FailureKind.VERIFICATION, (failure as? ApiFailure)?.kind)
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun redirectsAndChallengeRetriesCannotResetTheAuthenticationRetryBudget() = runBlocking {
        for (redirect in listOf(true, false)) MockWebServer().use { server ->
            server.start()
            server.enqueue(unauthorized())
            server.enqueue(validSession())
            server.enqueue(if (redirect) MockResponse().setResponseCode(302).addHeader("Location", "/target.json")
                else MockResponse().setResponseCode(403).addHeader("cf-mitigated", "challenge").setBody("<html>verify</html>"))
            server.enqueue(unauthorized())
            val failure = runCatching { DiscourseClient(Cookies(), server.url("/")).get("/private.json") }.exceptionOrNull()
            assertEquals(FailureKind.SIGN_IN, (failure as? ApiFailure)?.kind)
            assertEquals(4, server.requestCount)
        }
    }
}
