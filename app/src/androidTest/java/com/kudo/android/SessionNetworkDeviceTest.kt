package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.network.DohNetwork
import com.kudo.android.network.NetworkSnapshot
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Loopback only; simulates route state without changing the device's VPN or proxy. */
class SessionNetworkDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val preferenceName = "session-route-fixture-${UUID.randomUUID()}"
    private val fixtureHost = "session-network-fixture.invalid"
    private val server = MockWebServer()
    private val cookies = AndroidSessionCookies()
    private val snapshot = AtomicReference(NetworkSnapshot(1))
    private lateinit var origin: HttpUrl
    private lateinit var network: DohNetwork
    private lateinit var http: OkHttpClient

    @Before fun setup() {
        instrumentation.runOnMainSync { CookieManager.getInstance().setAcceptCookie(true) }
        server.start()
        origin = server.url("/").newBuilder().host(fixtureHost).build()
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(preferenceName, mode)
        }
        val settings = DohSettings(context).apply { update(DohConfiguration(enabled = false)) }
        network = DohNetwork(target, settings) { snapshot.get() }
        http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    check(hostname == fixtureHost)
                    return listOf(InetAddress.getByName("127.0.0.1"))
                }
            }).build()
        cookies.set(origin.toString(), "_t=fixture-0; Path=/; HttpOnly; Max-Age=3600")
    }

    @After fun cleanup() {
        if (::origin.isInitialized) cookies.set(origin.toString(), "_t=; Path=/; Max-Age=0")
        if (::network.isInitialized) network.close()
        server.shutdown()
        target.deleteSharedPreferences(preferenceName)
    }

    @Test fun renewedCookiesSurviveSystemRouteVpnAndProxyStateChanges() = runBlocking {
        val api = DiscourseClient(cookies, origin, http = http, route = network::client)
        val states = listOf(NetworkSnapshot(1), NetworkSnapshot(2, vpn = true),
            NetworkSnapshot(3, proxy = true), NetworkSnapshot(4))
        for ((index, state) in states.withIndex()) {
            snapshot.set(state)
            server.enqueue(MockResponse().setBody("{}").addHeader("Set-Cookie",
                "_t=fixture-${index + 1}; Path=/; HttpOnly; Max-Age=3600"))
            api.get("/fixture.json")
            assertEquals("_t=fixture-$index", server.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Cookie"))
            assertEquals(state.vpn, network.state.value.vpn)
            assertEquals(state.proxy, network.state.value.proxy)
            assertEquals("_t=fixture-${index + 1}", AndroidSessionCookies().get(origin.toString()))
        }
    }

    @Test fun authenticationReadsRecoverWithTheRenewedCookieAcrossRouteChanges() = runBlocking {
        var now = 0L
        val api = DiscourseClient(cookies, origin, http = http, route = network::client, authRecoveryClock = { now })
        val states = listOf(NetworkSnapshot(1), NetworkSnapshot(2, vpn = true),
            NetworkSnapshot(3, proxy = true), NetworkSnapshot(4))
        for ((index, state) in states.withIndex()) {
            now = index * 30_000L
            snapshot.set(state)
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"current_user":{"id":7,"username":"fixture"}}""")
                .addHeader("Set-Cookie", "_t=fixture-${index + 1}; Path=/; HttpOnly; Max-Age=3600"))
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            assertTrue(api.get("/private-fixture.json").getBoolean("ok"))
            assertEquals("/private-fixture.json", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
            assertEquals("/session/current.json", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
            val retry = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("/private-fixture.json", retry.path)
            assertEquals("_t=fixture-${index + 1}", retry.getHeader("Cookie"))
            assertEquals("_t=fixture-${index + 1}", AndroidSessionCookies().get(origin.toString()))
        }
        assertEquals(12, server.requestCount)
    }

    @Test fun aRevokedCookieStopsWithoutAProbeOrReadReplay() = runBlocking {
        val api = DiscourseClient(cookies, origin, http = http, route = network::client)
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}")
            .addHeader("Set-Cookie", "_t=; Path=/; Max-Age=0"))
        val failure = runCatching { api.get("/private-fixture.json") }.exceptionOrNull()
        assertEquals(FailureKind.SIGN_IN, (failure as? ApiFailure)?.kind)
        assertEquals(1, server.requestCount)
        assertEquals("", AndroidSessionCookies().get(origin.toString()))
    }

    @Test fun anInconclusiveProbePreservesCookiesAndItsErrorDuringCooldown() = runBlocking {
        val api = DiscourseClient(cookies, origin, http = http, route = network::client)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error_type":"not_logged_in"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        repeat(2) {
            val failure = runCatching { api.get("/private-fixture.json") }.exceptionOrNull()
            assertEquals(FailureKind.SERVER, (failure as? ApiFailure)?.kind)
        }
        assertEquals(3, server.requestCount)
        assertEquals("_t=fixture-0", AndroidSessionCookies().get(origin.toString()))
    }

    @Test fun malformedCsrfNeverSubmitsOrClaimsThatLoginExpired() = runBlocking {
        val api = DiscourseClient(cookies, origin, http = http, route = network::client)
        server.enqueue(MockResponse().setBody("{}"))
        val failure = runCatching { api.write("POST", "/posts.json", mapOf("raw" to "fixture")) }.exceptionOrNull()
        assertEquals(FailureKind.SERVER, (failure as? ApiFailure)?.kind)
        assertEquals(1, server.requestCount)
        assertEquals("/session/csrf", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
        assertEquals("_t=fixture-0", AndroidSessionCookies().get(origin.toString()))
    }

    @Test fun receivedRenewalPersistsEvenWhenTheAndroidCoroutineIsCancelled() = runBlocking {
        val waiting = Job()
        val delivered = CountDownLatch(1)
        val api = DiscourseClient(cookies, origin, http = http, route = { base ->
            val routed = network.client(base)
            Call.Factory { request ->
                val delegate = routed.newCall(request)
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
        server.enqueue(MockResponse().setBody("{}").addHeader("Set-Cookie",
            "_t=fixture-renewed; Path=/; HttpOnly; Max-Age=3600"))
        val task = launch(waiting + Dispatchers.IO) { api.get("/fixture.json") }
        try {
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            task.join()
            assertEquals("_t=fixture-renewed", AndroidSessionCookies().get(origin.toString()))
        } finally { waiting.cancel(); task.cancelAndJoin() }
    }
}
