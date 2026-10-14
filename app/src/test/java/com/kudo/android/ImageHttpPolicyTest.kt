package com.kudo.android

import com.kudo.android.data.*
import com.kudo.android.media.createImageHttpClient
import com.kudo.android.network.RequestCooldown
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

class ImageHttpPolicyTest {
    private fun image(server: MockWebServer, path: String = "/image.png") = Request.Builder().url(server.url(path)).build()
    private fun http(gate: RequestCooldown = RequestCooldown(), parallelism: Int = 4) =
        createImageHttpClient(gate, NoCookies, "fixture", parallelism, retryDelayMillis = 0)

    @Test fun apiRateLimitPreventsImageRequestsUntilCooldownExpires() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var now = 0L
            val gate = RequestCooldown({ now })
            val api = DiscourseClient(NoCookies, server.url("/"), cooldown = gate)
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
            server.enqueue(MockResponse().setBody("image"))
            runCatching { api.get("/latest.json") }
            val http = http(gate)
            assertTrue(runCatching { http.newCall(image(server)).execute().use {} }.exceptionOrNull() is IOException)
            assertEquals(1, server.requestCount)
            now = 30_000
            http.newCall(image(server)).execute().use { assertEquals("image", it.body!!.string()) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun imageRateLimitPreventsApiRequestsButDoesNotBlockAnotherOrigin() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            val gate = RequestCooldown()
            val http = http(gate)
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"wait_seconds":30}"""))
            server.enqueue(MockResponse().setBody("{}"))
            other.enqueue(MockResponse().setBody("other"))
            http.newCall(image(server)).execute().use { assertEquals(429, it.code) }
            val api = DiscourseClient(NoCookies, server.url("/"), cooldown = gate)
            assertEquals(FailureKind.RATE_LIMIT, (runCatching { api.get("/site.json") }.exceptionOrNull() as? ApiFailure)?.kind)
            assertEquals(1, server.requestCount)
            http.newCall(image(other)).execute().use { assertEquals("other", it.body!!.string()) }
        } }
    }

    @Test fun redirectsCheckTheDestinationCooldownAndStripForeignCookies() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            val gate = RequestCooldown()
            Response.Builder().request(image(other)).protocol(Protocol.HTTP_1_1).code(429).message("limit")
                .header("Retry-After", "30").body("{}".toResponseBody()).build().use { gate.record(it, "{}") }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/image.png")))
            other.enqueue(MockResponse().setBody("other"))
            assertTrue(runCatching { http(gate).newCall(image(server).newBuilder().header("Cookie", "secret=1").build()).execute().use {} }
                .exceptionOrNull() is IOException)
            assertEquals(1, server.requestCount)
            assertNull(server.takeRequest().getHeader("Cookie"))
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun openResponseBodiesCountTowardsTheConcurrencyLimitAndClosingReleasesIt() {
        MockWebServer().use { server ->
            server.start(); repeat(3) { server.enqueue(MockResponse().setBody("image")) }
            val http = http(parallelism = 2)
            val first = http.newCall(image(server, "/1")).execute()
            val second = http.newCall(image(server, "/2")).execute()
            val executor = Executors.newSingleThreadExecutor()
            val third = executor.submit<Response> { http.newCall(image(server, "/3")).execute() }
            try {
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertNull("响应体未释放时不能再下载第三张", server.takeRequest(250, TimeUnit.MILLISECONDS))
                first.close()
                third.get(3, TimeUnit.SECONDS).use { assertEquals("image", it.body!!.string()) }
            } finally {
                first.close(); second.close()
                runCatching { third.get(3, TimeUnit.SECONDS).close() }
                executor.shutdownNow()
            }
        }
    }

    @Test fun cancellingAWaitingImageDoesNotUseTheNetworkOrLeakAPermit() {
        MockWebServer().use { server ->
            server.start(); repeat(3) { server.enqueue(MockResponse().setBody("image")) }
            val http = http(parallelism = 1)
            val held = http.newCall(image(server)).execute()
            val call = http.newCall(image(server, "/cancelled"))
            val executor = Executors.newSingleThreadExecutor()
            val started = java.util.concurrent.CountDownLatch(1)
            val waiting = executor.submit<Response> { started.countDown(); call.execute() }
            try {
                assertTrue(started.await(1, TimeUnit.SECONDS))
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))
                assertFalse("第二张图片应等待响应体许可证", waiting.isDone)
                call.cancel()
                val failure = runCatching { waiting.get(2, TimeUnit.SECONDS) }.exceptionOrNull()
                assertTrue(failure is ExecutionException && failure.cause is IOException)
                assertEquals(1, server.requestCount)
                held.close()
                http.newCall(image(server, "/next")).execute().use { assertTrue(it.isSuccessful) }
            } finally { held.close(); call.cancel(); executor.shutdownNow() }
        }
    }

    @Test fun eofAndBodyReadFailureReleasePermitsBeforeTheCallerClosesTheResponse() {
        MockWebServer().use { server ->
            server.start()
            val http = http(parallelism = 1).newBuilder().callTimeout(2, TimeUnit.SECONDS).build()
            server.enqueue(MockResponse().setBody("image"))
            server.enqueue(MockResponse().setBody("next"))
            http.newCall(image(server)).execute().use { held ->
                held.body!!.source().readAll(okio.Buffer())
                http.newCall(image(server, "/next")).execute().use { assertEquals("next", it.body!!.string()) }
            }
            server.enqueue(MockResponse().setBody("broken image").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setBody("recovered"))
            http.newCall(image(server, "/broken")).execute().use { held ->
                assertTrue(runCatching { held.body!!.source().readAll(okio.Buffer()) }.exceptionOrNull() is IOException)
                http.newCall(image(server, "/after-error")).execute().use { assertEquals("recovered", it.body!!.string()) }
            }
        }
    }

    @Test fun certificateProtocolAndUnsafeRequestFailuresAreNotRetried() {
        MockWebServer().use { server ->
            server.start()
            for (failure in listOf(javax.net.ssl.SSLHandshakeException("certificate"),
                java.net.ProtocolException("protocol"), java.io.InterruptedIOException("interrupted"))) {
                val attempts = AtomicInteger()
                val http = http().newBuilder().addNetworkInterceptor { attempts.incrementAndGet(); throw failure }.build()
                assertTrue(runCatching { http.newCall(image(server)).execute() }.exceptionOrNull() is IOException)
                assertEquals(1, attempts.get())
            }
            val attempts = AtomicInteger()
            val http = http().newBuilder().addNetworkInterceptor { attempts.incrementAndGet(); throw SocketException("reset") }.build()
            val post = image(server).newBuilder().post("body".toRequestBody()).build()
            assertTrue(runCatching { http.newCall(post).execute() }.exceptionOrNull() is IOException)
            assertEquals(1, attempts.get())
        }
    }

    @Test fun nativeConnectionErrorsRetryOnceButNativeProfileAndTlsErrorsDoNot() {
        MockWebServer().use { server ->
            server.start()
            for (code in listOf("connection_failed", "connect_refused", "connect_unreachable", "connect_reset",
                    "connect_timeout", "connect_failed", "read_reset", "read_timeout", "read_eof",
                    "write_reset", "write_broken_pipe", "write_timeout", "socket_timeout", "dns_timeout")) {
                val attempts = AtomicInteger()
                val http = http().newBuilder().addNetworkInterceptor { chain ->
                    if (attempts.incrementAndGet() == 1) throw IOException("DoH: $code")
                    chain.proceed(chain.request())
                }.build()
                server.enqueue(MockResponse().setBody("image"))
                http.newCall(image(server)).execute().use { assertEquals("image", it.body!!.string()) }
                assertEquals(code, 2, attempts.get())
                http.connectionPool.evictAll()
            }
            for (code in listOf("profile_closed", "tls_record_invalid", "native_unavailable", "read_failed", "write_failed")) {
                val errors = AtomicInteger()
                val failing = http().newBuilder().addNetworkInterceptor { errors.incrementAndGet(); throw IOException("DoH: $code") }.build()
                assertTrue(runCatching { failing.newCall(image(server)).execute() }.exceptionOrNull() is IOException)
                assertEquals(1, errors.get())
            }
        }
    }

    @Test fun aTransientConnectionFailureGetsOnlyOneRetryAndHttpErrorsAreNeverReplayed() {
        MockWebServer().use { server ->
            server.start()
            val attempts = AtomicInteger()
            val http = http().newBuilder().addNetworkInterceptor { chain ->
                if (attempts.incrementAndGet() == 1) throw SocketException("connection reset")
                chain.proceed(chain.request())
            }.build()
            server.enqueue(MockResponse().setBody("image"))
            http.newCall(image(server)).execute().use { assertEquals("image", it.body!!.string()) }
            assertEquals(2, attempts.get())
            val failingAttempts = AtomicInteger()
            val failing = http().newBuilder().addNetworkInterceptor {
                failingAttempts.incrementAndGet(); throw SocketException("connection reset")
            }.build()
            assertTrue(runCatching { failing.newCall(image(server)).execute() }.exceptionOrNull() is IOException)
            assertEquals(2, failingAttempts.get())
            for (code in listOf(403, 408, 429, 503)) {
                val before = server.requestCount
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "0"))
                server.enqueue(MockResponse().setBody("unexpected retry"))
                http().newCall(image(server)).execute().use { assertEquals(code, it.code) }
                assertEquals(before + 1, server.requestCount)
                // Consume the sentinel only after proving the original request did not replay.
                http().newCall(image(server, "/sentinel")).execute().close()
            }
        }
    }

    @Test fun closedNativeProfilesDoNotRetryThroughSocketOrDnsExceptionTypes() {
        MockWebServer().use { server ->
            server.start()
            for (failure in listOf(SocketException("DoH: profile_closed"), java.net.UnknownHostException("DoH: profile_closed"))) {
                val attempts = AtomicInteger()
                val http = http().newBuilder().addNetworkInterceptor { attempts.incrementAndGet(); throw failure }.build()
                assertTrue(runCatching { http.newCall(image(server)).execute() }.exceptionOrNull() is IOException)
                assertEquals(1, attempts.get())
                http.connectionPool.evictAll()
            }
            assertEquals(0, server.requestCount)
        }
    }
}
