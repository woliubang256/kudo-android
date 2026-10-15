package com.kudo.android.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** Install as a network interceptor so redirects and existing safe retries each consume a start. */
internal class RequestSchedulingInterceptor(private val scheduler: RequestScheduler) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val permit = scheduler.acquire(chain.request().url) { chain.call().isCanceled() }
        var handedOff = false
        try {
            val response = chain.proceed(chain.request())
            if (response.code == 429) scheduler.cooldown.record(response)
            val body = response.body ?: return response
            if (body.contentLength() == 0L) return response
            var remaining = body.contentLength()
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long = try {
                    super.read(sink, byteCount).also {
                        if (it == -1L) permit.close()
                        else if (remaining >= 0) { remaining -= it; if (remaining <= 0) permit.close() }
                    }
                } catch (e: IOException) { permit.close(); throw e }
                override fun close() { try { super.close() } finally { permit.close() } }
            }.buffer()
            val wrapped = object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = source
            }
            return response.newBuilder().body(wrapped).build().also { handedOff = true }
        } finally { if (!handedOff) permit.close() }
    }
}
