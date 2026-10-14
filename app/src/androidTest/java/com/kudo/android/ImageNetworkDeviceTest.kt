package com.kudo.android

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.*
import com.kudo.android.data.*
import com.kudo.android.media.createImageHttpClient
import com.kudo.android.media.imageLoadErrorText
import com.kudo.android.network.DohTransport
import com.kudo.android.network.RequestCooldown
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.TimeUnit

class ImageNetworkDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cookies = object : SessionCookies {
        override fun get(url: String) = ""
        override fun set(url: String, cookie: String) {}
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff46c598.toInt()) }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray()
        }
    }
    private fun request(url: HttpUrl) = ImageRequest.Builder(context).data(url.toString()).build()

    @Test fun nativeImageRetryUsesTheNextAddressAfterATcpConnectionFailure() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val addresses = listOf("127.0.0.2", "127.0.0.1").map {
                InetAddress.getByAddress("image.fixture.invalid", InetAddress.getByName(it).address)
            }
            val transport = DohTransport(BuiltInDohServer, addressLookup = object : Dns {
                override fun lookup(hostname: String) = addresses
            })
            val attempts = Collections.synchronizedList(mutableListOf<String>())
            val http = transport.configure(createImageHttpClient(RequestCooldown(), cookies, "fixture", retryDelayMillis = 0)
                .newBuilder()).connectTimeout(1, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) {
                        attempts += address.address.hostAddress.orEmpty()
                    }
                }).build()
            val loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }.build()
            try {
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png())))
                val url = server.url("/image.png").newBuilder().host("image.fixture.invalid").build()
                val result = loader.execute(request(url))
                assertTrue("Expected recovery, got $result; attempts=$attempts", result is SuccessResult)
                assertEquals(listOf("127.0.0.2", "127.0.0.1"), attempts.toList())
                assertEquals(1, server.requestCount)
            } finally { loader.shutdown(); transport.close(); http.connectionPool.evictAll() }
        }
    }

    @Test fun coilHandles429AndRecoversOverTheNativeTransportWhileCachedImagesRemainAvailable() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = DohTransport(BuiltInDohServer)
            var now = 0L
            val gate = RequestCooldown({ now })
            val http = transport.configure(createImageHttpClient(gate, cookies, "fixture", parallelism = 1)
                .newBuilder()).callTimeout(5, TimeUnit.SECONDS).build()
            val loader = ImageLoader.Builder(context).diskCache(null)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }.build()
            val url = server.url("/image.png").newBuilder().host("127.0.0.1").build()
            try {
                server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
                assertTrue(loader.execute(request(url)) is ErrorResult)
                assertTrue(loader.execute(request(url)) is ErrorResult)
                assertEquals(1, server.requestCount)
                now = 30_000
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png())))
                val loaded = loader.execute(request(url)) as SuccessResult
                assertEquals(4, loaded.image.width)
                assertEquals(2, server.requestCount)
                Response.Builder().request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1)
                    .code(429).message("limit").header("Retry-After", "30").build().let { gate.record(it, "{}") }
                val cached = loader.execute(request(url)) as SuccessResult
                assertEquals(DataSource.MEMORY_CACHE, cached.dataSource)
                assertTrue(loader.execute(request(url.newBuilder().encodedPath("/not-cached.png").build())) is ErrorResult)
                val sameOrigin = DiscourseClient(cookies, url.resolve("/")!!, cooldown = gate)
                assertEquals(FailureKind.RATE_LIMIT, (runCatching { sameOrigin.get("/site.json") }.exceptionOrNull() as ApiFailure).kind)
                assertEquals(2, server.requestCount)
            } finally { loader.shutdown(); transport.close(); http.connectionPool.evictAll() }
        }
    }

    @Test fun nativeFailedAddressesKeepOneReadRetryAndNeverReplayWrites() {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val addresses = listOf("127.0.0.2", "127.0.0.3").map {
                InetAddress.getByAddress("image.fixture.invalid", InetAddress.getByName(it).address)
            }
            for (write in listOf(false, true)) {
                val transport = DohTransport(BuiltInDohServer, addressLookup = object : Dns {
                    override fun lookup(hostname: String) = addresses
                })
                val attempts = Collections.synchronizedList(mutableListOf<String>())
                val http = transport.configure(createImageHttpClient(RequestCooldown(), cookies, "fixture", retryDelayMillis = 0)
                    .newBuilder()).connectTimeout(1, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
                    .eventListener(object : EventListener() {
                        override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) {
                            attempts += address.address.hostAddress.orEmpty()
                        }
                    }).build()
                try {
                    val url = server.url("/image.png").newBuilder().host("image.fixture.invalid").build()
                    val request = Request.Builder().url(url).apply { if (write) post(FormBody.Builder().add("fixture", "test").build()) }.build()
                    val failure = runCatching { http.newCall(request).execute().close() }.exceptionOrNull()
                    assertNotNull(failure)
                    assertEquals(if (write) listOf("127.0.0.2") else listOf("127.0.0.2", "127.0.0.3"), attempts.toList())
                    assertEquals("DoH connect_refused · 目标拒绝连接", imageLoadErrorText(failure!!))
                    assertEquals(0, server.requestCount)
                } finally { transport.close(); http.connectionPool.evictAll() }
            }
        }
    }

    @Test fun parallelCoilDecodingReleasesAllDownloadPermitsOnTheNativeTransport() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = DohTransport(BuiltInDohServer)
            val http = transport.configure(createImageHttpClient(RequestCooldown(), cookies, "fixture", parallelism = 2)
                .newBuilder()).callTimeout(5, TimeUnit.SECONDS).build()
            val loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }.build()
            try {
                repeat(8) { server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png()))) }
                withTimeout(10_000) {
                    (1..8).map { index -> async(Dispatchers.IO) {
                        val url = server.url("/image-$index.png").newBuilder().host("127.0.0.1").build()
                        loader.execute(request(url))
                    } }.awaitAll().forEach { assertTrue(it is SuccessResult) }
                }
                assertEquals(8, server.requestCount)
            } finally { loader.shutdown(); transport.close(); http.connectionPool.evictAll() }
        }
    }
}
