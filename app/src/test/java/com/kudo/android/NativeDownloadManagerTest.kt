package com.kudo.android

import com.kudo.android.media.filenameFromContentDisposition
import com.kudo.android.media.filenameFromUrl
import com.kudo.android.media.isDownloadUrl
import com.kudo.android.media.downloadRequest
import com.kudo.android.data.SessionCookies
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDownloadManagerTest {
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
