package com.kudo.android

import com.kudo.android.network.RequestCooldown
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RequestCooldownTest {
    @Test fun readingASlowRateLimitBodyDoesNotBlockOtherRequestsCheckingCooldown() {
        val gate = RequestCooldown({ 0 })
        val reading = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val source = object : Source {
            private var sent = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (sent) return -1
                reading.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS))
                sent = true
                return sink.writeUtf8("""{"wait_seconds":30}""").size
            }
            override fun timeout() = Timeout.NONE
            override fun close() {}
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        val slow = response().newBuilder().body(body).build()
        val recording = executor.submit<Int> { slow.use { gate.record(it) } }
        try {
            assertTrue("The response body must be in flight", reading.await(2, TimeUnit.SECONDS))
            val query = executor.submit<Int> { gate.remainingSeconds(url) }
            assertEquals("A response body read must not hold the shared cooldown monitor", 0,
                query.get(1, TimeUnit.SECONDS))
        } finally {
            releaseRead.countDown()
            try { assertEquals(30, recording.get(2, TimeUnit.SECONDS)) }
            finally { executor.shutdownNow() }
        }
        assertEquals(30, gate.remainingSeconds(url))
    }

    private val url = "https://linux.do/latest.json".toHttpUrl()
    private fun response(retry: String? = null, body: String = "{}", date: String? = null) = Response.Builder()
        .request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_2).code(429).message("limited")
        .body(body.toResponseBody()).apply {
            retry?.let { header("Retry-After", it) }; date?.let { header("Date", it) }
        }.build()

    @Test fun delayUsesTheLongestServerValueAndCannotBeShortenedByAnotherResponse() {
        var now = 0L
        val gate = RequestCooldown({ now })
        response("10", """{"wait_seconds":30}""").use { assertEquals(30, gate.record(it)) }
        now = 1000
        response("2").use { assertEquals(29, gate.record(it)) }
        now = 30_000
        assertEquals(0, gate.remainingSeconds(url))
    }

    @Test fun datesUseServerDateToAvoidDeviceClockSkewAndWallClockChangesDoNotAffectExpiry() {
        var now = 0L
        var wall = 0L
        val gate = RequestCooldown({ now }, { wall })
        response("Wed, 21 Oct 2015 07:28:30 GMT", date = "Wed, 21 Oct 2015 07:28:00 GMT").use {
            assertEquals(30, gate.record(it))
        }
        wall = Long.MAX_VALUE
        now = 29_001
        assertEquals(1, gate.remainingSeconds(url))
        now = 30_000
        assertEquals(0, gate.remainingSeconds(url))
    }

    @Test fun invalidWaitsFallBackAndZeroStillPausesWithoutConsumingTheBody() {
        listOf(null, "invalid", "-100").forEach { invalid ->
            val gate = RequestCooldown({ 0 })
            response(invalid, """{"wait_seconds":-1}""").use { assertEquals(15, gate.record(it)) }
        }
        val gate = RequestCooldown({ 0 })
        response("0", """{"extras":{"wait_seconds":2}}""").use {
            assertEquals(2, gate.record(it))
            assertTrue(it.body!!.string().contains("wait_seconds"))
        }
    }

    @Test fun originsAreIsolatedAndConcurrentResponsesKeepTheLongestDeadline() {
        val gate = RequestCooldown({ 0 })
        val executor = Executors.newFixedThreadPool(4)
        try {
            (1..30).map { seconds -> executor.submit { response(seconds.toString()).use { gate.record(it) } } }
                .forEach { it.get() }
        } finally { executor.shutdownNow() }
        assertEquals(30, gate.remainingSeconds("https://linux.do/site.json".toHttpUrl()))
        listOf("http://linux.do/", "https://linux.do:8443/", "https://cdn.linux.do/", "https://ping.ldstatic.com/")
            .forEach { assertEquals(0, gate.remainingSeconds(it.toHttpUrl())) }
    }
}
