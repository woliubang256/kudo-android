package com.kudo.android

import com.kudo.android.media.filenameFromContentDisposition
import com.kudo.android.media.filenameFromUrl
import com.kudo.android.media.isDownloadUrl
import com.kudo.android.media.downloadRequest
import com.kudo.android.media.createDownloadHttpClient
import com.kudo.android.data.SessionCookies
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDownloadManagerTest {
    @Test fun downloadsSaveRedirectRenewalAndIgnoreForeignResponseCookies() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            var value = "_t=old"
            val saved = mutableListOf<Pair<String, String>>()
            val cookies = object : SessionCookies {
                override fun get(url: String) = value
                override fun set(url: String, cookie: String) { saved += url to cookie; value = cookie.substringBefore(';') }
            }
            val first = "_t=renewed; Path=/; HttpOnly; Max-Age=3600; SameSite=Lax"
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/file.zip").addHeader("Set-Cookie", first))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/file.zip")))
            other.enqueue(MockResponse().setBody("file").addHeader("Set-Cookie", "_t=foreign; Domain=linux.do; Path=/"))
            val http = createDownloadHttpClient(cookies, "fixture", cookieOrigin = server.url("/"))
            http.newCall(Request.Builder().url(server.url("/download")).build()).execute().use {
                assertEquals("file", it.body!!.string())
            }
            assertEquals(listOf(server.url("/download").toString() to first), saved)
            assertEquals("_t=old", server.takeRequest().getHeader("Cookie"))
            assertEquals("_t=renewed", server.takeRequest().getHeader("Cookie"))
            assertNull(other.takeRequest().getHeader("Cookie"))
        } }
    }
    @Test fun recognizesFileLinksAndDownloadParameters() {
        assertTrue(isDownloadUrl("https://linux.do/uploads/key-labyrinth-20261013.zip"))
        assertTrue(isDownloadUrl("https://files.example.test/get?id=7&download=1"))
        assertFalse(isDownloadUrl("https://linux.do/t/topic/3020259"))
        assertFalse(isDownloadUrl("javascript:download('x.zip')"))
        assertFalse(isDownloadUrl("https://user:pass@example.test/file.zip"))
    }

    @Test fun sanitizesUrlAndHeaderFilenames() {
        assertEquals("key-labyrinth-20261013.zip", filenameFromUrl("https://linux.do/files/key-labyrinth-20261013.zip"))
        assertEquals("report final.zip", filenameFromContentDisposition("attachment; filename*=UTF-8''report%20final.zip"))
        assertEquals("name_.zip", filenameFromContentDisposition("attachment; filename=\"bad:/name?.zip\""))
        assertNull(filenameFromContentDisposition("attachment"))
    }

    @Test fun credentialsOnlyApplyToCommunityHttpsOrigin() {
        var reads = 0
        val cookies = object : SessionCookies {
            override fun get(url: String): String { reads++; return "_t=fixture" }
            override fun set(url: String, cookie: String) {}
        }
        val origin = Request.Builder().url("https://linux.do/file.zip").header("Cookie", "untrusted").build()
        assertEquals("_t=fixture", downloadRequest(origin, cookies, "Fixture/1").header("Cookie"))
        listOf("https://cdn.example.test/file.zip", "https://linux.do.evil.test/file.zip", "https://linux.do:444/file.zip", "http://linux.do/file.zip").forEach { value ->
            val request = downloadRequest(origin.newBuilder().url(value).build(), cookies, "Fixture/1")
            assertNull(request.header("Cookie"))
            assertEquals("Fixture/1", request.header("User-Agent"))
        }
        assertEquals(1, reads)
    }

    @Test fun filenamesPreserveLiteralPlusAndOnlyDecodeOnce() {
        assertEquals("C++.zip", filenameFromUrl("https://linux.do/files/C%2B%2B.zip"))
        assertEquals("100%20.zip", filenameFromUrl("https://linux.do/files/100%2520.zip"))
        assertEquals("C++.zip", filenameFromContentDisposition("attachment; filename*=UTF-8'en'C++.zip"))
        assertEquals("100%20.zip", filenameFromContentDisposition("attachment; filename=\"100%20.zip\""))
    }
}
