package com.kudo.android.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class FailureKind { SIGN_IN, VERIFICATION, FORBIDDEN, NOT_FOUND, RATE_LIMIT, NETWORK, SERVER }
class ApiFailure(val kind: FailureKind, override val message: String, val retryAfter: Int? = null) : IOException(message)

internal fun com.kudo.android.network.RequestCoolingDown.apiFailure() = ApiFailure(FailureKind.RATE_LIMIT, message.orEmpty(), seconds)

internal const val CloudflareVerificationMessage = "检测到 Cloudflare 网页验证，请打开 Linux.do 手动完成验证后重试"

internal fun isHtmlResponse(response: Response, body: String): Boolean {
    val contentType = response.header("Content-Type").orEmpty()
    val trimmed = body.trimStart()
    return contentType.substringBefore(';').trim().equals("text/html", ignoreCase = true) ||
        trimmed.startsWith("<!doctype html", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)
}

internal fun isCloudflareChallenge(response: Response, body: String): Boolean {
    if (response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true) return true
    if (!isHtmlResponse(response, body)) return false
    val page = Jsoup.parse(body)
    val scripts = page.select("script")
    val bootstrap = scripts.any { it.data().contains("window._cf_chl_opt", ignoreCase = true) }
    val platform = scripts.any { it.attr("src").contains("/cdn-cgi/challenge-platform/", ignoreCase = true) }
    return bootstrap || (platform && (page.title().equals("Just a moment...", ignoreCase = true) || page.getElementById("challenge-form") != null))
}

interface SessionCookies {
    fun get(url: String): String
    fun set(url: String, cookie: String)
}

class DiscourseClient(
    private val cookies: SessionCookies,
    val baseUrl: HttpUrl = "https://linux.do/".toHttpUrl(),
    private val userAgent: () -> String = { "Kudo/0.1 Android" },
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    route: (OkHttpClient) -> Call.Factory = { it },
    val cooldown: com.kudo.android.network.RequestCooldown = com.kudo.android.network.RequestCooldown(),
) {
    private val calls = route(http)
    private var csrf: String? = null
    private val csrfMutex = Mutex()
    fun resetSession() { csrf = null }

    fun resolve(path: String): HttpUrl {
        val url = baseUrl.resolve(path) ?: throw IllegalArgumentException("无效的社区地址")
        require(url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port && url.username.isEmpty() && url.password.isEmpty()) { "拒绝向社区以外的地址发送会话" }
        return url
    }

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject = request("GET", path, query)
    suspend fun getArray(path: String): JSONArray = request("GET", path, arrayResponse = true).getJSONArray("items")
    internal suspend fun getCollection(path: String): JSONObject = request("GET", path, collectionResponse = true)
    internal suspend fun siteSettingsHtml(): String = request("GET", "/latest", htmlResponse = true).getString("html")
    internal suspend fun tagIcons(): Map<String, TagIconStyle> =
        parseTagIconSettings(request("GET", "/latest", htmlResponse = true).getString("html"))
    internal suspend fun sharedSessionKey(): String = Jsoup.parse(request("GET", "/latest", htmlResponse = true).getString("html"))
        .selectFirst("meta[name=shared_session_key]")?.attr("content")?.takeIf { it.isNotBlank() }
        ?: throw ApiFailure(FailureKind.SIGN_IN, "推送会话暂不可用")
    suspend fun write(method: String, path: String, fields: Map<String, String> = emptyMap(), body: RequestBody? = null): JSONObject {
        val token = csrfMutex.withLock {
            csrf ?: get("/session/csrf").optString("csrf").takeIf { it.isNotBlank() && it != "null" }
                ?.also { csrf = it } ?: throw ApiFailure(FailureKind.SIGN_IN, "登录状态已失效，请重新登录")
        }
        return request(method, path, body = body ?: FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build(), token = token)
    }

    private suspend fun request(method: String, path: String, query: Map<String, String> = emptyMap(), body: RequestBody? = null, token: String? = null, redirects: Int = 0, arrayResponse: Boolean = false, htmlResponse: Boolean = false, collectionResponse: Boolean = false, verificationAttempt: Int = 0): JSONObject {
        val url = resolve(path).newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        cooldown.remainingSeconds(url).takeIf { it > 0 }?.let { throw rateLimitFailure(it) }
        val request = Request.Builder().url(url).method(method, body)
            .header("Accept", if (htmlResponse) "text/html" else "application/json")
            .header("User-Agent", userAgent()).header("Referer", baseUrl.toString())
            .apply {
                if (!htmlResponse) header("X-Requested-With", "XMLHttpRequest")
                cookies.get(url.toString()).takeIf(String::isNotBlank)?.let { header("Cookie", it) }
                token?.let { header("X-CSRF-Token", it); header("Origin", baseUrl.toString().trimEnd('/')) }
            }.build()
        val response = try { calls.newCall(request).await() } catch (e: IOException) {
            if (e is com.kudo.android.network.RequestCoolingDown) throw e.apiFailure()
            throw ApiFailure(FailureKind.NETWORK, if (method == "GET") {
                if (e.message.orEmpty().contains("DoH:")) com.kudo.android.network.dohErrorMessage(e)
                else "网络连接失败，请检查网络后重试"
            } else "请求结果尚未确认，请刷新确认后再操作，以免重复提交")
        }
        response.use {
            it.headers("Set-Cookie").forEach { cookie -> cookies.set(url.toString(), cookie) }
            if (method == "GET" && it.code in setOf(301, 302, 303, 307, 308) && redirects < 3) {
                val target = it.header("Location")?.let(url::resolve)
                if (target != null) {
                    resolve(target.toString())
                    if (target.encodedPath.trimEnd('/') == "/404") throw ApiFailure(FailureKind.NOT_FOUND, "内容不存在或暂无权限")
                    it.close()
                    return request("GET", target.toString(), redirects = redirects + 1, arrayResponse = arrayResponse, htmlResponse = htmlResponse, collectionResponse = collectionResponse, verificationAttempt = verificationAttempt)
                }
            }
            val text = it.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            val reason = json?.optJSONArray("errors")?.strings()?.joinToString("\n")?.take(500)
                ?: json?.text("error")?.takeIf(String::isNotBlank)?.take(500)
            val notFound = it.code == 404 || url.encodedPath.trimEnd('/') == "/404" || json?.stringField("error_type") == "not_found"
            when {
                it.code == 401 || (it.code == 403 && json?.text("error_type") == "not_logged_in") -> throw ApiFailure(FailureKind.SIGN_IN, "请先登录 Linux.do")
                it.code == 429 -> throw rateLimitFailure(cooldown.record(it, text), reason)
                isCloudflareChallenge(it, text) -> {
                    if (method == "GET" && verificationAttempt == 0) {
                        it.close()
                        delay(600)
                        return request("GET", url.toString(), redirects = redirects, arrayResponse = arrayResponse,
                            htmlResponse = htmlResponse, collectionResponse = collectionResponse, verificationAttempt = 1)
                    }
                    throw ApiFailure(FailureKind.VERIFICATION, CloudflareVerificationMessage)
                }
                notFound -> throw ApiFailure(FailureKind.NOT_FOUND, "内容不存在或暂无权限")
                it.code == 403 -> { csrf = null; throw ApiFailure(FailureKind.FORBIDDEN, reason ?: "当前账号没有权限，或登录状态需要刷新") }
                !it.isSuccessful -> throw ApiFailure(FailureKind.SERVER, reason ?: "社区暂时无法处理请求（${it.code}）")
                htmlResponse -> return JSONObject().put("html", text)
                it.code == 204 || text.isBlank() -> return JSONObject()
                arrayResponse && it.isSuccessful -> {
                    val array = runCatching { JSONArray(text) }.getOrNull()
                        ?: throw ApiFailure(FailureKind.SERVER, "服务器未返回话题跟踪列表")
                    return JSONObject().put("items", array)
                }
                json == null && collectionResponse -> return JSONObject().put("items", runCatching { JSONArray(text) }.getOrNull()
                    ?: throw ApiFailure(FailureKind.SERVER, "社区返回的数据格式异常，请稍后重试"))
                json == null -> throw ApiFailure(FailureKind.SERVER, "社区返回的数据格式异常，请稍后重试")
                json.optBoolean("success", true).not() -> throw ApiFailure(FailureKind.SERVER, reason ?: "操作未成功，请稍后重试")
                else -> return json
            }
        }
    }

    private fun rateLimitFailure(seconds: Int, reason: String? = null) = ApiFailure(FailureKind.RATE_LIMIT,
        "${reason ?: "请求较频繁"}\n请在 ${seconds} 秒后重试", seconds)
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
    })
}
