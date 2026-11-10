package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import android.os.StrictMode
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.DohConfiguration
import com.kudo.android.data.DohSettings
import com.kudo.android.network.DohNetwork
import com.kudo.android.network.NetworkSnapshot
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

/** Real platform TLS on loopback, isolated preferences, and no device network changes. */
class DohNetworkCleanupDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun onMainWithNetworkGuard(action: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            val original = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(original).detectNetwork().penaltyDeathOnNetwork().build())
            try { action() } catch (error: Throwable) { failure.set(error) }
            finally { StrictMode.setThreadPolicy(original) }
        }
        failure.get()?.let { throw AssertionError("Route changes must not perform TLS I/O on the main thread", it) }
    }

    private inner class Fixture(private val blockFirstClose: Boolean = false) : AutoCloseable {
        private val target = instrumentation.targetContext
        private val prefix = "doh-cleanup-test-${UUID.randomUUID()}"
        private val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("$prefix-$name", mode)
        }
        private val preferences = context.getSharedPreferences("kudo_network", Context.MODE_PRIVATE)
        val settings = DohSettings(context).apply { update(DohConfiguration(enabled = true)) }
        val snapshot = AtomicReference(NetworkSnapshot(1, vpn = true))
        private val cleanupExecutor = Executors.newSingleThreadExecutor()
        private val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val network = if (blockFirstClose) DohNetwork(target, settings, cleanupDispatcher) { snapshot.get() }
            else DohNetwork(target, settings) { snapshot.get() }
        val closedThreads = CopyOnWriteArrayList<Thread>()
        val socketClosed = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        private val firstClose = AtomicBoolean(true)
        private val sockets = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun close() {
                    closedThreads += Thread.currentThread()
                    if (blockFirstClose && firstClose.compareAndSet(true, false)) {
                        closeStarted.countDown()
                        check(allowClose.await(10, TimeUnit.SECONDS)) { "Fixture close was not released" }
                    }
                    try { super.close() } finally { socketClosed.countDown() }
                }
            }
            override fun createSocket(host: String, port: Int): Socket = error("Unexpected connected socket factory overload")
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected bind")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("Unexpected connected socket factory overload")
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected bind")
        }
        private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply {
            protocols = listOf(Protocol.HTTP_1_1)
            useHttps(serverTls.sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val base = OkHttpClient.Builder().socketFactory(sockets)
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1)).proxy(Proxy.NO_PROXY)
            .callTimeout(5, TimeUnit.SECONDS).build()
        val calls = network.client(base)
        val request = Request.Builder().url(server.url("/").newBuilder().host("127.0.0.1").build()).build()

        fun read() {
            server.enqueue(MockResponse().setBody("ok"))
            calls.newCall(request).execute().use { assertEquals("ok", it.body!!.string()) }
        }

        fun assertClosedOffMain() {
            assertTrue("Retired TLS sockets must be closed", socketClosed.await(5, TimeUnit.SECONDS))
            assertTrue(closedThreads.isNotEmpty())
            assertFalse("Socket cleanup must never run on the UI thread", closedThreads.any { it === Looper.getMainLooper().thread })
        }

        fun awaitCleanup() { cleanupExecutor.submit {}.get(5, TimeUnit.SECONDS) }

        override fun close() {
            allowClose.countDown()
            network.close()
            if (blockFirstClose) awaitCleanup()
            cleanupDispatcher.close()
            base.connectionPool.evictAll()
            server.shutdown()
            preferences.edit().clear().commit()
        }
    }

    @Test fun refreshOnMainClosesIdleTlsConnectionsOffMain() {
        Fixture().use { fixture ->
            fixture.read()
            fixture.snapshot.set(NetworkSnapshot(1))
            // Construct a routed client without sending a request or trusting the fixture certificate in Rust.
            fixture.calls.newCall(fixture.request)
            fixture.snapshot.set(NetworkSnapshot(2, vpn = true))
            onMainWithNetworkGuard { fixture.network.refresh() }
            assertTrue(fixture.network.state.value.pausedByVpnOrProxy)
            assertTrue(fixture.settings.configuration.value.enabled)
            fixture.assertClosedOffMain()
        }
    }

    @Test fun closeOnMainStillDrainsCleanupAndRejectsNewCalls() {
        Fixture().use { fixture ->
            fixture.read()
            onMainWithNetworkGuard { fixture.network.close(); fixture.network.close() }
            fixture.assertClosedOffMain()
            assertTrue(runCatching { fixture.calls.newCall(fixture.request) }.exceptionOrNull() is java.io.IOException)
        }
    }

    @Test fun blockedOldCleanupCannotBlockRequestsOrEvictTheNewPool() {
        Fixture(blockFirstClose = true).use { fixture ->
            fixture.read()
            assertEquals(0, fixture.server.takeRequest(5, TimeUnit.SECONDS)!!.sequenceNumber)
            fixture.snapshot.set(NetworkSnapshot(2, vpn = true))
            onMainWithNetworkGuard { fixture.network.refresh() }
            assertTrue("The old socket cleanup must start", fixture.closeStarted.await(5, TimeUnit.SECONDS))
            // Keep old cleanup blocked while the next generation establishes an independent connection.
            fixture.read()
            assertEquals(0, fixture.server.takeRequest(5, TimeUnit.SECONDS)!!.sequenceNumber)
            fixture.allowClose.countDown()
            fixture.awaitCleanup()
            fixture.read()
            assertEquals("Old cleanup must leave the new idle connection reusable", 1,
                fixture.server.takeRequest(5, TimeUnit.SECONDS)!!.sequenceNumber)
            fixture.assertClosedOffMain()
        }
    }
}
