package com.kudo.android.network

import okhttp3.HttpUrl
import okhttp3.Response
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Shared by API and media callers; deadlines use a monotonic clock and never queue requests. */
class RequestCooldown(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val deadlines = mutableMapOf<String, Long>()

    @Synchronized
    fun remainingSeconds(url: HttpUrl): Int {
        val key = origin(url)
        val remaining = (deadlines[key] ?: return 0) - nowMillis()
        if (remaining <= 0) { deadlines.remove(key); return 0 }
        return ((remaining + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    @Synchronized
    fun record(response: Response, body: String? = null): Int {
        require(response.code == 429)
        val json = runCatching { JSONObject(body ?: response.peekBody(16_384).string()) }.getOrNull()
        val jsonDelay = listOf(json?.opt("wait_seconds"), json?.optJSONObject("extras")?.opt("wait_seconds"))
            .mapNotNull { seconds(it?.toString()) }.maxOrNull()
        val header = response.header("Retry-After")
        val headerDelay = seconds(header) ?: httpDate(header)?.let { retryAt ->
            val reference = httpDate(response.header("Date")) ?: wallTimeMillis()
            ((retryAt - reference).coerceAtLeast(0) + 999).div(1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val delay = listOfNotNull(headerDelay, jsonDelay).maxOrNull()?.coerceAtLeast(1) ?: 15
        val now = nowMillis()
        deadlines.entries.removeAll { it.value <= now }
        val key = origin(response.request.url)
        deadlines[key] = maxOf(deadlines[key] ?: now, now + delay.toLong() * 1000)
        return remainingSeconds(response.request.url)
    }

    private fun seconds(value: String?): Int? = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    private fun httpDate(value: String?): Long? = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()
    private fun origin(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"
}
