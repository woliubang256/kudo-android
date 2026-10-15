package com.kudo.android.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TrustMetric(val label: String, val value: String, val progress: Float?, val met: Boolean)
data class TrustReport(val title: String, val subtitle: String, val badge: String, val status: String,
    val hint: String, val metrics: List<TrustMetric>)

internal fun parseTrustReport(html: String): TrustReport {
    val document = Jsoup.parse(html)
    val card = document.selectFirst("div.card") ?: throw ApiFailure(FailureKind.VERIFICATION, "等级数据暂不可用，请在应用内网页完成认证后重试")
    fun variable(style: String, key: String): Double? = Regex("(?:^|;)\\s*${Regex.escape(key)}\\s*:\\s*([0-9.]+)")
        .find(style)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    fun number(value: Double?) = value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "—"
    val metrics = buildList {
        card.select(".tl3-ring, .tl3-bar-item").forEach { node ->
            val ring = node.hasClass("tl3-ring")
            val label = node.selectFirst(if (ring) ".tl3-ring-label" else ".tl3-bar-label")?.text().orEmpty()
            val indicator = node.selectFirst(if (ring) ".tl3-ring-circle" else ".tl3-bar-fill")
            val current = variable(indicator?.attr("style").orEmpty(), "--val")
            val max = variable(indicator?.attr("style").orEmpty(), "--max")
            val value = node.selectFirst(".tl3-bar-nums")?.text()?.takeIf { it.isNotBlank() }
                ?: "${number(current)} / ${number(max)}"
            if (label.isNotBlank()) add(TrustMetric(label, value,
                if (current != null && max != null && max > 0) (current / max).toFloat().coerceIn(0f, 1f) else null,
                indicator?.hasClass("met") == true))
        }
        card.select(".tl3-quota-card").forEach { node ->
            val label = node.selectFirst(".tl3-quota-label")?.text().orEmpty()
            if (label.isNotBlank()) add(TrustMetric(label, node.selectFirst(".tl3-quota-nums")?.text().orEmpty(), null, !node.hasClass("unmet")))
        }
        card.select(".tl3-veto-item").forEach { node ->
            val met = !node.hasClass("unmet")
            val face = node.selectFirst(if (met) ".tl3-veto-front" else ".tl3-veto-back") ?: node
            val label = face.selectFirst(".tl3-veto-label")?.text().orEmpty()
            val value = listOf(".tl3-veto-value", ".tl3-veto-desc").mapNotNull { face.selectFirst(it)?.text()?.takeIf(String::isNotBlank) }.joinToString(" · ")
            if (label.isNotBlank()) add(TrustMetric(label, value, null, met))
        }
    }
    val title = card.selectFirst(".card-title")?.text().orEmpty()
    if (title.isBlank() && metrics.isEmpty()) throw ApiFailure(FailureKind.SERVER, "等级页面结构已变化，请使用应用内网页查看")
    val emptyText = if (card.hasClass("empty-state")) card.select("p").map { it.text() }.filter(String::isNotBlank).joinToString("\n") else ""
    return TrustReport(title.ifBlank { "等级要求" }, card.selectFirst(".card-subtitle")?.text().orEmpty(),
        card.selectFirst(".badge")?.text().orEmpty(), card.selectFirst(".status-met, .status-unmet")?.text().orEmpty(),
        emptyText.ifBlank { card.selectFirst(".text-hint")?.text().orEmpty() }, metrics)
}

/** A separate origin: never relax DiscourseClient's same-origin session boundary. */
class ConnectRepository(private val cookies: SessionCookies, private val userAgent: String,
    private val endpoint: HttpUrl = "https://connect.linux.do/".toHttpUrl(),
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(35, TimeUnit.SECONDS).build(),
    route: (OkHttpClient) -> Call.Factory = { it }) {
    private val calls = route(http)
    suspend fun load(): TrustReport {
        val request = Request.Builder().url(endpoint).header("User-Agent", userAgent).header("Accept", "text/html")
            .apply { cookies.get(endpoint.toString()).takeIf(String::isNotBlank)?.let { header("Cookie", it) } }.build()
        val call = calls.newCall(request)
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(
                    if (e is com.kudo.android.network.RequestCoolingDown) e.apiFailure()
                    else ApiFailure(FailureKind.NETWORK, "无法连接等级页面，请稍后重试")) }
                override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
            })
        }
        response.use {
            it.headers("Set-Cookie").forEach { cookie -> cookies.set(endpoint.toString(), cookie) }
            val source = it.body?.source() ?: throw ApiFailure(FailureKind.SERVER, "等级页面没有返回数据")
            if (source.request(2L * 1024 * 1024 + 1)) throw ApiFailure(FailureKind.SERVER, "等级页面返回的数据过大")
            val html = source.readUtf8()
            if (isCloudflareChallenge(it, html)) throw ApiFailure(FailureKind.VERIFICATION, CloudflareVerificationMessage)
            if (it.isRedirect || it.code == 401) throw ApiFailure(FailureKind.SIGN_IN, "等级页面需要认证，请先在应用内网页打开")
            if (it.code == 429) throw ApiFailure(FailureKind.RATE_LIMIT, "请求较频繁，请稍后重试",
                com.kudo.android.network.RequestCooldown().record(it, html))
            if (!it.isSuccessful) throw ApiFailure(FailureKind.SERVER, "等级页面暂时不可用（${it.code}）")
            return parseTrustReport(html)
        }
    }
}
