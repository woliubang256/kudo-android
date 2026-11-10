package com.kudo.android

import com.kudo.android.data.*
import com.kudo.android.media.createDownloadHttpClient
import com.kudo.android.media.createImageHttpClient
import com.kudo.android.network.RequestCooldown
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionTransportTest {
    private val clients: List<(SessionCookies, HttpUrl) -> OkHttpClient> = listOf(
        { cookies, origin -> createImageHttpClient(RequestCooldown(), cookies, "fixture", cookieOrigin = origin) },
        { cookies, origin -> createDownloadHttpClient(cookies, "fixture", cookieOrigin = origin) },
    )
    private fun cookies() = SessionCookieStore(object : CookieStorage {
        private var value = "_t=old"
        override fun get(url: String) = value
        override fun set(url: String, cookie: String) { value = cookie.substringBefore(';') }
        override fun clear() { value = "" }
        override fun flush() {}
    })

    @Test fun imageAndDownloadResponsesCannotRestoreAnEarlierLogin() {
        for (create in clients) MockWebServer().use { server ->
            server.start()
            val cookies = cookies()
            val http = create(cookies, server.url("/"))
            server.enqueue(MockResponse().setHeadersDelay(250, TimeUnit.MILLISECONDS)
                .addHeader("Set-Cookie", "_t=stale; Path=/").setBody("file"))
            val worker = Executors.newSingleThreadExecutor()
            try {
                val response = worker.submit { http.newCall(Request.Builder().url(server.url("/file")).build()).execute().use {} }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                cookies.invalidate()
                cookies.set(server.url("/").toString(), "_t=new; Path=/")
                val failure = runCatching { response.get(3, TimeUnit.SECONDS) }.exceptionOrNull()
                assertEquals("_t=new", cookies.get(server.url("/").toString()))
                assertTrue(failure?.cause is IOException)
            } finally { worker.shutdownNow(); http.connectionPool.evictAll() }
        }
    }

    @Test fun redirectsCannotStartACommunityRequestInAnotherAccountGeneration() {
        for (create in clients) MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            val cookies = cookies()
            val http = create(cookies, server.url("/"))
            other.enqueue(MockResponse().setHeadersDelay(250, TimeUnit.MILLISECONDS)
                .setResponseCode(302).setHeader("Location", server.url("/private-file")))
            server.enqueue(MockResponse().setBody("file"))
            val worker = Executors.newSingleThreadExecutor()
            try {
                val response = worker.submit { http.newCall(Request.Builder().url(other.url("/redirect")).build()).execute().use {} }
                val foreign = other.takeRequest(3, TimeUnit.SECONDS)
                assertNotNull(foreign)
                assertNull(foreign!!.getHeader("Cookie"))
                cookies.invalidate()
                cookies.set(server.url("/").toString(), "_t=new; Path=/")
                val failure = runCatching { response.get(3, TimeUnit.SECONDS) }.exceptionOrNull()
                assertEquals(0, server.requestCount)
                assertTrue(failure?.cause is IOException)
            } finally { worker.shutdownNow(); http.connectionPool.evictAll() }
        } }
    }
}
