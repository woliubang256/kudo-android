package com.kudo.android

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DohTransportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun MockWebServer.localUrl(path: String): HttpUrl = url(path).newBuilder().host("127.0.0.1").build()

    @Test fun http2CanWriteWhileItsReaderWaitsForAnotherStream() {
        val server = MockWebServer().apply { protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE) }
        val transport = DohTransport(BuiltInDohServer)
        val http = transport.configure(OkHttpClient.Builder()).protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).callTimeout(5, TimeUnit.SECONDS).build()
        try {
            server.enqueue(MockResponse().setBody("first").setBodyDelay(200, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("second"))
            val results = java.util.concurrent.CopyOnWriteArrayList<String>()
            val finished = CountDownLatch(2)
            repeat(2) { index -> http.newCall(Request.Builder().url(server.localUrl("/h2/$index")).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { finished.countDown() }
                override fun onResponse(call: Call, response: Response) {
                    response.use { if (it.protocol == Protocol.H2_PRIOR_KNOWLEDGE) results += it.body!!.string() }; finished.countDown()
                }
            }) }
            assertTrue(finished.await(6, TimeUnit.SECONDS))
            assertEquals(setOf("first", "second"), results.toSet())
        } finally { transport.close(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun gzipChunkedDownloadsAndConnectionReuseUseTheNativeSocket() {
        val server = MockWebServer()
        val transport = DohTransport(BuiltInDohServer)
        val http = transport.configure(OkHttpClient.Builder()).build()
        try {
            val compressed = Buffer()
            GzipSink(compressed).buffer().use { it.writeUtf8("gzip works") }
            server.enqueue(MockResponse().setBody(compressed).setHeader("Content-Encoding", "gzip"))
            val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(payload), 8192))
            http.newCall(Request.Builder().url(server.localUrl("/gzip")).build()).execute().use { assertEquals("gzip works", it.body!!.string()) }
            http.newCall(Request.Builder().url(server.localUrl("/stream")).build()).execute().use { assertArrayEquals(payload, it.body!!.bytes()) }
            assertEquals(0, server.takeRequest(3, TimeUnit.SECONDS)!!.sequenceNumber)
            assertEquals("The second request must reuse the same native connection", 1, server.takeRequest(3, TimeUnit.SECONDS)!!.sequenceNumber)
        } finally { transport.close(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun streamingUploadIsNotReplayedAfterTheResponseConnectionIsLost() {
        val server = MockWebServer()
        val transport = DohTransport(BuiltInDohServer)
        val http = transport.configure(OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS)).build()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = null
                override fun writeTo(sink: okio.BufferedSink) { repeat(64) { sink.write(ByteArray(8192) { 42 }) } }
            }
            assertTrue(runCatching { http.newCall(Request.Builder().url(server.localUrl("/write")).post(body).build()).execute().close() }.exceptionOrNull() is IOException)
            assertEquals(512L * 1024, server.takeRequest(3, TimeUnit.SECONDS)!!.bodySize)
            assertEquals(1, server.requestCount)
        } finally { transport.close(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun cancellingWhileAwaitingHeadersStopsNativeRead() {
        val server = MockWebServer()
        val transport = DohTransport(BuiltInDohServer)
        val http = transport.configure(OkHttpClient.Builder()).build()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = http.newCall(Request.Builder().url(server.localUrl("/poll")).build())
            val finished = CountDownLatch(1)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { finished.countDown() }
                override fun onResponse(call: Call, response: Response) { response.close(); finished.countDown() }
            })
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            call.cancel()
            assertTrue("Cancellation must not wait for the long-poll read timeout", finished.await(3, TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
        } finally { transport.close(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun selfSignedTlsCertificateIsRejectedBeforeHttp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false) }
        val transport = DohTransport(BuiltInDohServer)
        val http = transport.configure(OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS)).build()
        try {
            server.enqueue(MockResponse().setBody("must not be read"))
            val failure = runCatching { http.newCall(Request.Builder().url(server.url("/").newBuilder().host("127.0.0.1").build()).build()).execute().close() }.exceptionOrNull()
            assertTrue(failure is javax.net.ssl.SSLHandshakeException)
            assertEquals(0, server.requestCount)
        } finally { transport.close(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun vpnPausesAndRestoresRoutingWithoutChangingUserPreference() {
        val settings = DohSettings(instrumentation.context).apply { update(DohConfiguration()) }
        var snapshot = NetworkSnapshot(1)
        val network = DohNetwork(instrumentation.targetContext, settings) { snapshot }
        val server = MockWebServer()
        val socketKinds = mutableListOf<Boolean>()
        val base = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).eventListener(object : EventListener() {
            override fun connectionAcquired(call: Call, connection: Connection) { socketKinds += connection.socket().javaClass.name.contains("RustSocket") }
        }).build()
        val calls = network.client(base)
        fun read() {
            server.enqueue(MockResponse().setBody("ok"))
            calls.newCall(Request.Builder().url(server.localUrl("/")).build()).execute().use { assertEquals("ok", it.body!!.string()) }
        }
        try {
            read()
            settings.update(settings.configuration.value.copy(enabled = true)); read()
            snapshot = snapshot.copy(vpn = true); read()
            assertTrue(settings.configuration.value.enabled); assertFalse(network.state.value.effective)
            snapshot = snapshot.copy(vpn = false); read()
            snapshot = snapshot.copy(vpn = true); network.refresh()
            settings.update(settings.configuration.value.copy(enabled = false)); snapshot = snapshot.copy(vpn = false); read()
            assertEquals(listOf(false, true, false, true, false), socketKinds)
        } finally { network.close(); base.connectionPool.evictAll(); server.shutdown(); settings.update(DohConfiguration()) }
    }

    @Test fun disablingVpnAutoSwitchKeepsNativeRoutingForVpnAndSystemProxyAndPersistsTheChoice() {
        val settings = DohSettings(instrumentation.context).apply { update(DohConfiguration(enabled = true)) }
        val snapshot = java.util.concurrent.atomic.AtomicReference(NetworkSnapshot(1, vpn = true))
        val network = DohNetwork(instrumentation.targetContext, settings) { snapshot.get() }
        val server = MockWebServer()
        val socketKinds = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val base = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).eventListener(object : EventListener() {
            override fun connectionAcquired(call: Call, connection: Connection) {
                socketKinds += connection.socket().javaClass.name.contains("RustSocket")
            }
        }).build()
        val calls = network.client(base)
        fun read() {
            server.enqueue(MockResponse().setBody("ok"))
            calls.newCall(Request.Builder().url(server.localUrl("/")).build()).execute().use { assertEquals("ok", it.body!!.string()) }
        }
        try {
            read()
            settings.update(settings.configuration.value.copy(vpnAutoSwitch = false)); read()
            assertTrue(network.state.value.effective)
            assertFalse(DohSettings(instrumentation.context).configuration.value.vpnAutoSwitch)
            settings.update(settings.configuration.value.copy(vpnAutoSwitch = true)); read()
            snapshot.set(NetworkSnapshot(2, proxy = true)); read()
            assertEquals("系统代理已开启，DoH 暂停", network.state.value.summary)
            settings.update(settings.configuration.value.copy(vpnAutoSwitch = false)); read()
            assertTrue(network.state.value.effective)
            settings.update(settings.configuration.value.copy(enabled = false)); read()
            assertFalse(network.state.value.effective)
            assertEquals(listOf(false, true, false, false, true, false), socketKinds)
        } finally { network.close(); base.connectionPool.evictAll(); server.shutdown(); settings.update(DohConfiguration()) }
    }

    @Test fun productionClientsReadTheExistingSessionListAndAvatarOnDirectNetwork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dohProductionLive") == "true")
        val app = (instrumentation.targetContext.applicationContext as KudoApplication).container
        val original = app.network.settings.configuration.value
        val report = JSONObject()
        try {
            app.network.settings.update(original.copy(enabled = true)); app.network.refresh()
            assertTrue("Direct network required; VPN and system proxy must be disabled", app.network.state.value.effective)
            val session = app.client.get("/session/current.json")
            val user = session.optJSONObject("current_user")
            report.put("authenticated", (user?.optInt("id") ?: 0) > 0)
            val list = app.client.get("/latest.json")
            report.put("topics", list.optJSONObject("topic_list")?.optJSONArray("topics")?.length() ?: 0)
            assertTrue(report.getInt("topics") > 0)
            val template = user?.optString("avatar_template").orEmpty().ifBlank { list.optJSONArray("users")?.optJSONObject(0)?.optString("avatar_template").orEmpty() }
            val url = "https://linux.do/".toHttpUrl().resolve(template.replace("{size}", "48"))!!
            app.imageHttpClient.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(200, it.code)
                val bytes = it.body!!.bytes()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull(bitmap); bitmap?.recycle(); report.put("avatarDecoded", true)
            }
            report.put("echAccepted", app.network.state.value.echAccepted)
            assertTrue(app.network.state.value.echAccepted)
            // Session contents, Cookie values, usernames, and user IDs must never enter this report.
        } finally {
            File(instrumentation.targetContext.filesDir, "doh-production-result.json").writeText(report.toString(2))
            app.network.settings.update(original); app.network.refresh()
        }
    }
}
