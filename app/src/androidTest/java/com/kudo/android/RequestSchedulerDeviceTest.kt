package com.kudo.android

import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.network.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RequestSchedulerDeviceTest {
    @Test fun nativeHttp2RespectsBodyConcurrencyAndTheSharedSlidingWindow() {
        MockWebServer().use { server ->
            server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
            server.start()
            repeat(3) { server.enqueue(MockResponse().setBody("fixture")) }
            val clock = AtomicLong()
            val gate = RequestScheduler(RequestLimits(1, 2, 10), nowMillis = clock::get)
            val transport = DohTransport(BuiltInDohServer)
            val http = transport.configure(OkHttpClient.Builder().addNetworkInterceptor(RequestSchedulingInterceptor(gate)))
                .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).callTimeout(8, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(server.url("/").newBuilder().host("127.0.0.1").build()).build()
            val held = http.newCall(request).execute()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val second = executor.submit<Response> { http.newCall(request).execute() }
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
                assertEquals("fixture", held.body!!.string())
                second.get(2, TimeUnit.SECONDS).use { assertEquals("fixture", it.body!!.string()) }
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                val third = executor.submit<Response> { http.newCall(request).execute() }
                assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
                clock.set(10_000); gate.update(RequestLimits(1, 2, 10))
                third.get(2, TimeUnit.SECONDS).use { assertEquals("fixture", it.body!!.string()) }
                assertEquals(3, server.requestCount)
            } finally { held.close(); executor.shutdownNow(); transport.close(); http.connectionPool.evictAll() }
        }
    }

    @Test fun localCooldownDoesNotTurnTheDohStateIntoAConnectionFailure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settings = DohSettings(instrumentation.context).apply { update(DohConfiguration(enabled = true)) }
        val network = DohNetwork(instrumentation.targetContext, settings) { NetworkSnapshot(1) }
        val gate = RequestScheduler()
        val base = OkHttpClient.Builder().addNetworkInterceptor(RequestSchedulingInterceptor(gate)).build()
        MockWebServer().use { server ->
            server.start()
            val request = Request.Builder().url(server.url("/").newBuilder().host("127.0.0.1").build()).build()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(429).message("limit")
                .header("Retry-After", "30").body("{}".toResponseBody()).build().use { gate.cooldown.record(it) }
            try {
                assertTrue(runCatching { network.client(base).newCall(request).execute().close() }.exceptionOrNull() is RequestCoolingDown)
                assertNull(network.state.value.error)
                assertTrue(network.state.value.effective)
                assertEquals(0, server.requestCount)
            } finally { network.close(); base.connectionPool.evictAll(); settings.update(DohConfiguration()) }
        }
    }
}
