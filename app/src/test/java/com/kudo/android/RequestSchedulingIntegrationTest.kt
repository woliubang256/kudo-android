package com.kudo.android

import com.kudo.android.data.*
import com.kudo.android.media.createImageHttpClient
import com.kudo.android.network.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RequestSchedulingIntegrationTest {
    @Test fun apiAndImagesShareTheWindowAndConfigurationChangesReleaseTheWaitingImage() = runBlocking {
        MockWebServer().use { server ->
            server.start(); repeat(2) { server.enqueue(MockResponse().setBody("{}")) }; server.enqueue(MockResponse().setBody("image"))
            val gate = RequestScheduler(RequestLimits(3, 2, 10))
            fun route(base: OkHttpClient): Call.Factory = base.newBuilder().addNetworkInterceptor(RequestSchedulingInterceptor(gate)).build()
            val api = DiscourseClient(NoCookies, server.url("/"), route = ::route, cooldown = gate.cooldown)
            val media = route(createImageHttpClient(gate.cooldown, NoCookies, "fixture"))
            api.get("/1"); api.get("/2")
            repeat(2) { server.takeRequest(1, TimeUnit.SECONDS) }
            val pending = async(Dispatchers.IO) { media.newCall(Request.Builder().url(server.url("/image")).build()).execute().use { it.body!!.string() } }
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            gate.update(RequestLimits(3, 3, 10))
            assertEquals("image", withTimeout(2000) { pending.await() })
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun a429AbortsAQueuedMutationWithoutReplayingItAndKeepsTheRateLimitError() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val clock = AtomicLong()
            val gate = RequestScheduler(RequestLimits(3, 2, 10), nowMillis = clock::get)
            fun route(base: OkHttpClient): Call.Factory = base.newBuilder().addNetworkInterceptor(RequestSchedulingInterceptor(gate)).build()
            val api = DiscourseClient(NoCookies, server.url("/"), route = ::route, cooldown = gate.cooldown)
            server.enqueue(MockResponse().setBody("""{"csrf":"fixture"}"""))
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody("{}"))
            api.write("POST", "/first")
            repeat(2) { server.takeRequest(1, TimeUnit.SECONDS) }
            val pending = async(Dispatchers.IO) { runCatching { api.write("POST", "/queued") }.exceptionOrNull() }
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            // Another in-flight caller can report 429 while this mutation waits for local budget.
            OkHttpClient.Builder().build().newCall(Request.Builder().url(server.url("/inflight")).build()).execute().use { gate.cooldown.record(it) }
            val failure = withTimeout(2000) { pending.await() } as? ApiFailure
            assertEquals(FailureKind.RATE_LIMIT, failure?.kind)
            assertEquals(30, failure?.retryAfter)
            clock.set(20_000); gate.update(RequestLimits.Turbo)
            assertEquals(3, server.requestCount)
            assertEquals("/inflight", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        }
    }

    @Test fun cancellationOfAnApiWaitingForBudgetDoesNotSendItLater() = runBlocking {
        MockWebServer().use { server ->
            server.start(); repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
            val gate = RequestScheduler(RequestLimits(3, 2, 10))
            val api = DiscourseClient(NoCookies, server.url("/"), route = { it.newBuilder()
                .addNetworkInterceptor(RequestSchedulingInterceptor(gate)).build() })
            api.get("/1"); api.get("/2")
            val waiting = launch(Dispatchers.IO) { api.get("/cancelled") }
            delay(100); waiting.cancelAndJoin()
            gate.update(RequestLimits(3, 3, 10))
            assertEquals(2, server.requestCount)
            api.get("/next")
            assertEquals(3, server.requestCount)
        }
    }
}
