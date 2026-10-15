package com.kudo.android

import com.kudo.android.network.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RequestSchedulerTest {
    private val url = "https://linux.do/latest.json".toHttpUrl()

    @Test fun slidingWindowCountsStartsAcrossPathsAndUsesTheExactExpiryBoundary() {
        val clock = AtomicLong()
        val gate = RequestScheduler(RequestLimits(3, 2, 3), nowMillis = clock::get)
        repeat(2) { gate.acquire(url) { false }.close() }
        val executor = Executors.newSingleThreadExecutor()
        val pending = executor.submit<java.io.Closeable> { gate.acquire(url.resolve("/other")!!) { false } }
        try {
            assertTrue(runCatching { pending.get(100, TimeUnit.MILLISECONDS) }.exceptionOrNull() is java.util.concurrent.TimeoutException)
            clock.set(2999); gate.update(RequestLimits(3, 2, 3))
            assertFalse(pending.isDone)
            clock.set(3000); gate.update(RequestLimits(3, 2, 3))
            pending.get(1, TimeUnit.SECONDS).close()
        } finally { executor.shutdownNow() }
    }

    @Test fun enlargedWindowRetainsEarlierStartsAndUpdatingLimitsWakesWaiters() {
        val clock = AtomicLong()
        val gate = RequestScheduler(RequestLimits(3, 2, 1), nowMillis = clock::get)
        repeat(2) { gate.acquire(url) { false }.close() }
        clock.set(1500)
        gate.acquire(url) { false }.close()
        gate.update(RequestLimits(3, 2, 10))
        val executor = Executors.newSingleThreadExecutor()
        val pending = executor.submit<java.io.Closeable> { gate.acquire(url) { false } }
        try {
            assertTrue(runCatching { pending.get(150, TimeUnit.MILLISECONDS) }.exceptionOrNull() is java.util.concurrent.TimeoutException)
            gate.update(RequestLimits(3, 4, 10))
            pending.get(1, TimeUnit.SECONDS).close()
        } finally { executor.shutdownNow() }
    }

    @Test fun cancellingTheFirstWaiterDoesNotBlockTheNextOrSendAnything() {
        val gate = RequestScheduler(RequestLimits(1, 30, 1))
        val held = gate.acquire(url) { false }
        val cancelled = AtomicBoolean()
        val executor = Executors.newFixedThreadPool(2)
        val first = executor.submit<java.io.Closeable> { gate.acquire(url) { cancelled.get() } }
        try {
            assertTrue(runCatching { first.get(100, TimeUnit.MILLISECONDS) }.exceptionOrNull() is java.util.concurrent.TimeoutException)
            val next = executor.submit<java.io.Closeable> { gate.acquire(url) { false } }
            cancelled.set(true)
            assertTrue(runCatching { first.get(1, TimeUnit.SECONDS) }.exceptionOrNull()?.cause is IOException)
            held.close(); held.close()
            next.get(1, TimeUnit.SECONDS).close()
        } finally { held.close(); executor.shutdownNow() }
    }

    @Test fun originsAreIsolatedAndLoweringConcurrencyDoesNotCancelActiveRequests() {
        val gate = RequestScheduler(RequestLimits(2, 30, 1))
        val held = List(2) { gate.acquire(url) { false } }
        gate.update(RequestLimits(1, 30, 1))
        gate.acquire("https://connect.linux.do/".toHttpUrl()) { false }.close()
        val executor = Executors.newSingleThreadExecutor()
        val next = executor.submit<java.io.Closeable> { gate.acquire(url) { false } }
        try {
            held[0].close()
            assertTrue(runCatching { next.get(100, TimeUnit.MILLISECONDS) }.exceptionOrNull() is java.util.concurrent.TimeoutException)
            held[1].close()
            next.get(1, TimeUnit.SECONDS).close()
        } finally { held.forEach { it.close() }; executor.shutdownNow() }
    }

    @Test fun independentHttpClientsShareResponseBodyConcurrencyAndReleaseAtEofOrClose() {
        MockWebServer().use { server ->
            server.start(); repeat(4) { server.enqueue(MockResponse().setBody("body")) }
            val gate = RequestScheduler(RequestLimits(1, 30, 1))
            fun client() = OkHttpClient.Builder().addNetworkInterceptor(RequestSchedulingInterceptor(gate)).build()
            val api = client(); val media = client()
            fun request(path: String) = Request.Builder().url(server.url(path)).build()
            val first = api.newCall(request("/api")).execute()
            val executor = Executors.newSingleThreadExecutor()
            val pending = executor.submit<Response> { media.newCall(request("/image")).execute() }
            try {
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
                first.body!!.string()
                pending.get(2, TimeUnit.SECONDS).use { assertEquals("body", it.body!!.string()) }
                api.newCall(request("/next")).execute().close()
            } finally { first.close(); executor.shutdownNow(); api.connectionPool.evictAll(); media.connectionPool.evictAll() }
        }
    }

    @Test fun serverCooldownAbortsQueuedRequestsInsteadOfReleasingThemAfterTheWait() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("held"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody("{}"))
            val cooldown = RequestCooldown()
            val gate = RequestScheduler(RequestLimits(1, 30, 1), cooldown)
            val http = OkHttpClient.Builder().addNetworkInterceptor(RequestSchedulingInterceptor(gate)).build()
            val request = Request.Builder().url(server.url("/")).build()
            val held = http.newCall(request).execute()
            val executor = Executors.newSingleThreadExecutor()
            val pending = executor.submit<Response> { http.newCall(request).execute() }
            try {
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(429).message("limit").body("{}".toResponseBody()).build().use { cooldown.record(it, "{}") }
                val failure = runCatching { pending.get(1, TimeUnit.SECONDS) }.exceptionOrNull()?.cause
                assertTrue(failure is RequestCoolingDown)
                assertEquals(1, server.requestCount)
            } finally { held.close(); executor.shutdownNow(); http.connectionPool.evictAll() }
        }
    }

    @Test fun redirectsConsumeTheDestinationWindowAndAnEmptyBodyDoesNotHoldConcurrency() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            val clock = AtomicLong()
            val gate = RequestScheduler(RequestLimits(1, 2, 3), nowMillis = clock::get)
            val http = OkHttpClient.Builder().addNetworkInterceptor(RequestSchedulingInterceptor(gate)).build()
            repeat(2) { other.enqueue(MockResponse().setBody("")); http.newCall(Request.Builder().url(other.url("/")).build()).execute().close() }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/image")))
            other.enqueue(MockResponse().setBody("image"))
            val executor = Executors.newSingleThreadExecutor()
            val pending = executor.submit<Response> { http.newCall(Request.Builder().url(server.url("/")).build()).execute() }
            try {
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                Thread.sleep(150)
                assertEquals(2, other.requestCount)
                clock.set(3000); gate.update(RequestLimits(1, 2, 3))
                pending.get(2, TimeUnit.SECONDS).use { assertEquals("image", it.body!!.string()) }
            } finally { executor.shutdownNow(); http.connectionPool.evictAll() }
        } }
    }

    @Test fun failureWhileReadingTheBodyReleasesConcurrencyWithoutReplayingTheRequest() {
        MockWebServer().use { server ->
            val listenAddress = InetAddress.getLoopbackAddress()
            server.start(listenAddress, 0)
            server.enqueue(MockResponse().setBody("broken body").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setBody("next"))
            val http = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(2, TimeUnit.SECONDS)
                .addNetworkInterceptor(RequestSchedulingInterceptor(RequestScheduler(RequestLimits(1, 30, 1)))).build()
            val request = Request.Builder().url(server.url("/").newBuilder()
                .host(requireNotNull(listenAddress.hostAddress)).build()).build()
            http.newCall(request).execute().use { assertTrue(runCatching { it.body!!.string() }.exceptionOrNull() is IOException) }
            http.newCall(request).execute().use { assertEquals("next", it.body!!.string()) }
            assertEquals(2, server.requestCount)
            http.connectionPool.evictAll()
        }
    }
}
