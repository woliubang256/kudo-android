package com.kudo.android

import coil3.network.HttpException
import coil3.network.NetworkResponse
import com.kudo.android.media.imageLoadErrorText
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ImageLoadErrorTest {
    @Test fun httpFailureKeepsTheActualStatusEvenWhenWrapped() {
        assertEquals("HTTP 503 · 服务暂不可用", imageLoadErrorText(IOException("wrapper", HttpException(NetworkResponse(code = 503)))))
        assertEquals("HTTP 429 · 请求频繁，请稍后重试", imageLoadErrorText(HttpException(NetworkResponse(code = 429))))
        assertEquals("HTTP 418 · 图片请求未成功", imageLoadErrorText(HttpException(NetworkResponse(code = 418))))
    }

    @Test fun transportFailuresShowTypesAndReasonsWithoutInventingHttpCodes() {
        assertEquals("SocketTimeoutException · 连接或读取超时", imageLoadErrorText(SocketTimeoutException("read timed out")))
        assertEquals("UnknownHostException · 域名解析失败", imageLoadErrorText(IOException("wrapper", UnknownHostException("private.example"))))
        assertEquals("SSLHandshakeException · TLS 握手或证书校验失败", imageLoadErrorText(SSLHandshakeException("certificate secret")))
        assertEquals("SSLHandshakeException · TLS 握手或证书校验失败",
            imageLoadErrorText(SSLHandshakeException("certificate secret").apply { initCause(IllegalArgumentException("private certificate")) }))
        assertEquals("EOFException · 图片响应提前结束", imageLoadErrorText(EOFException("private URL")))
        assertEquals("InterruptedIOException · 请求超时", imageLoadErrorText(InterruptedIOException("timeout")))
    }

    @Test fun nativeFailuresPreserveSafeCodesIncludingWrappedFailures() {
        assertEquals("DoH connection_failed · 网络连接失败", imageLoadErrorText(IOException("DoH: connection_failed")))
        assertEquals("DoH dns_timeout · DoH 查询超时", imageLoadErrorText(IOException("wrapper", UnknownHostException("DoH: dns_timeout"))))
        assertEquals("DoH ech_rejected · 服务器拒绝 ECH", imageLoadErrorText(IOException("DoH: ech_rejected")))
        assertEquals("DoH profile_closed · 网络配置已切换，请重试", imageLoadErrorText(IOException("DoH: profile_closed")))
        assertEquals("DoH new_safe_code · 原生网络请求失败", imageLoadErrorText(IOException("DoH: new_safe_code")))
    }

    @Test fun nativeIoFailuresIdentifyStageAndReasonWithoutRawMessages() {
        val cases = mapOf(
            "connect_refused" to "目标拒绝连接", "connect_unreachable" to "目标网络不可达",
            "connect_reset" to "建连时连接被重置", "connect_timeout" to "建立连接超时", "connect_failed" to "建立连接失败",
            "read_reset" to "读取时连接被重置", "read_timeout" to "读取图片超时",
            "read_eof" to "图片传输提前结束", "read_failed" to "读取网络数据失败",
            "write_reset" to "发送时连接被重置", "write_broken_pipe" to "发送时连接已断开",
            "write_timeout" to "发送请求超时", "write_failed" to "发送网络数据失败"
        )
        for ((code, reason) in cases) {
            assertEquals("DoH $code · $reason", imageLoadErrorText(IOException("DoH: $code")))
            assertEquals("IOException · 图片读取失败", imageLoadErrorText(IOException("DoH: $code private URL Cookie: secret")))
        }
    }

    @Test fun localCooldownRetainsRemainingSecondsWithoutClaimingANew429Response() {
        assertEquals("COOLDOWN · 请求较频繁，请在 12 秒后重试", imageLoadErrorText(IOException("请求较频繁，请在 12 秒后重试")))
    }

    @Test fun decoderFailuresNeverExposeTheImageUrlOrData() {
        assertEquals("IllegalStateException · 图片解码失败或格式不受支持",
            imageLoadErrorText(IllegalStateException("Unable to create a decoder for https://private.example/image?token=secret")))
        assertEquals("IOException · 图片尺寸超过解码限制", imageLoadErrorText(IOException("AVIF 动画尺寸过大")))
    }

    @Test fun unknownFailureHidesRawUrlsCookiesAndMessages() {
        val raw = "https://user:secret@private.example/a?signature=secret Cookie: session=secret\nAuthorization: secret"
        assertEquals("IllegalStateException · 图片加载失败，原因未识别", imageLoadErrorText(IllegalStateException(raw)))
        assertEquals("IOException · 图片读取失败", imageLoadErrorText(IOException("DoH: connection_failed $raw")))
        assertEquals("IOException · 图片读取失败", imageLoadErrorText(IOException("请求较频繁，请在 12 秒后重试 $raw")))
    }

    @Test fun cyclicCauseChainsAreBounded() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertEquals("IOException · 图片读取失败", imageLoadErrorText(first))
    }
}
