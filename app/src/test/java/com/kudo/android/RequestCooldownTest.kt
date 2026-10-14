package com.kudo.android

import com.kudo.android.network.RequestCooldown
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class RequestCooldownTest {
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
