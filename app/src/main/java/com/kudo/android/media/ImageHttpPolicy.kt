package com.kudo.android.media

import com.kudo.android.data.SessionCookies
import com.kudo.android.data.SessionCookieSnapshot
import com.kudo.android.network.RequestCooldown
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

internal fun createImageHttpClient(cooldown: RequestCooldown, cookies: SessionCookies, userAgent: String,
    parallelism: Int = 4, retryDelayMillis: Long = 300,
    cookieOrigin: HttpUrl = "https://linux.do/".toHttpUrl()): OkHttpClient = OkHttpClient.Builder()
    .dispatcher(Dispatcher().apply { maxRequests = parallelism; maxRequestsPerHost = parallelism })
    .retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        val request = chain.request()
        val boundary = request.tag(SessionCookieSnapshot::class.java) ?: cookies.snapshot(cookieOrigin.toString())
        chain.proceed(request.newBuilder().tag(SessionCookieSnapshot::class.java, boundary).build())
    }
    .addInterceptor(ImageRequestPolicy(cooldown, parallelism, retryDelayMillis))
    .addNetworkInterceptor { chain ->
        val request = chain.request()
        checkCooldown(cooldown, request)
        val builder = request.newBuilder().removeHeader("Cookie")
        val sameOrigin = request.url.scheme == cookieOrigin.scheme && request.url.host == cookieOrigin.host && request.url.port == cookieOrigin.port
        val boundary = request.tag(SessionCookieSnapshot::class.java)!!
        val session = if (sameOrigin) cookies.snapshot(request.url.toString()) else null
        if (sameOrigin) {
            if (!cookies.isCurrent(boundary)) throw IOException("会话已切换，请重新加载")
            builder.header("Cookie", session!!.header).header("User-Agent", userAgent)
        }
        val response = chain.proceed(builder.build())
        try {
            if (session != null && !cookies.receive(request.url.toString(), response.headers("Set-Cookie"), session))
                throw IOException("会话已切换，请重新加载")
        } catch (e: Exception) { response.close(); throw e }
        if (response.code == 429) cooldown.record(response)
        // OkHttp can replay a GET on 503 Retry-After:0 even with connection retries disabled.
        if (response.code == 503 && response.header("Retry-After")?.trim() == "0")
            response.newBuilder().header("Retry-After", "1").build() else response
    }.build()

private class ImageCoolingDown(seconds: Int) : IOException("请求较频繁，请在 $seconds 秒后重试")
private fun checkCooldown(cooldown: RequestCooldown, request: Request) {
    cooldown.remainingSeconds(request.url).takeIf { it > 0 }?.let { throw ImageCoolingDown(it) }
}

private class ImageRequestPolicy(private val cooldown: RequestCooldown, parallelism: Int,
    private val retryDelayMillis: Long) : Interceptor {
    private val permits = Semaphore(parallelism, true)

    override fun intercept(chain: Interceptor.Chain): Response {
        checkCooldown(cooldown, chain.request())
        try {
            while (true) {
                if (chain.call().isCanceled()) throw IOException("Canceled")
                if (permits.tryAcquire(100, TimeUnit.MILLISECONDS)) break
                checkCooldown(cooldown, chain.request())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Image request interrupted").apply { initCause(e) }
        }
        val released = AtomicBoolean()
        fun release() { if (released.compareAndSet(false, true)) permits.release() }
        var handedOff = false
        try {
            val response = load(chain)
            val body = response.body ?: return response
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long = try {
                    super.read(sink, byteCount).also { if (it == -1L) release() }
                } catch (e: IOException) { release(); throw e }
                override fun close() { try { super.close() } finally { release() } }
            }.buffer()
            val wrapped = object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = source
            }
            val result = response.newBuilder().body(wrapped).build()
            handedOff = true
            return result
        } finally { if (!handedOff) release() }
    }

    private fun load(chain: Interceptor.Chain): Response {
        var retried = false
        while (true) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            try { return chain.proceed(chain.request()) }
            catch (e: IOException) {
                if (retried || chain.call().isCanceled() || chain.request().method !in listOf("GET", "HEAD") || !transient(e)) throw e
                retried = true
                var remaining = retryDelayMillis
                while (remaining > 0) {
                    if (chain.call().isCanceled()) throw IOException("Canceled")
                    val step = minOf(remaining, 100)
                    try { Thread.sleep(step) } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("Image retry interrupted").apply { initCause(e) }
                    }
                    remaining -= step
                }
            }
        }
    }

    private fun transient(failure: IOException): Boolean {
        if (failure is ImageCoolingDown || failure is SSLException || failure is ProtocolException ||
            (failure is InterruptedIOException && failure !is SocketTimeoutException)) return false
        if (failure.message.orEmpty().startsWith("DoH: ")) return failure.message in setOf(
                "DoH: connection_failed", "DoH: connect_refused", "DoH: connect_unreachable", "DoH: connect_reset",
                "DoH: connect_timeout", "DoH: connect_failed", "DoH: read_reset", "DoH: read_timeout", "DoH: read_eof",
                "DoH: write_reset", "DoH: write_broken_pipe", "DoH: write_timeout", "DoH: socket_timeout", "DoH: dns_timeout")
        return failure is SocketException || failure is SocketTimeoutException || failure is UnknownHostException ||
            failure is EOFException || failure.cause is EOFException
    }
}
