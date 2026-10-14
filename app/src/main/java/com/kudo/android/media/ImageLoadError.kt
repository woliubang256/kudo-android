package com.kudo.android.media

import coil3.network.HttpException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private val cooldownMessage = Regex("^请求较频繁，请在 ([0-9]{1,10}) 秒后重试$")
private val dohCode = Regex("^DoH: ([a-z][a-z0-9_]{0,63})$")

/** Only expose response codes, exception types and controlled reasons, never raw URLs or credentials. */
internal fun imageLoadErrorText(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    causes.filterIsInstance<HttpException>().firstOrNull()?.let {
        val reason = when (it.response.code) {
            401 -> "需要登录后访问"
            403 -> "服务器拒绝访问"
            404 -> "图片不存在"
            408 -> "请求超时"
            429 -> "请求频繁，请稍后重试"
            500 -> "服务器内部错误"
            502 -> "网关响应异常"
            503 -> "服务暂不可用"
            504 -> "网关响应超时"
            else -> "图片请求未成功"
        }
        return "HTTP ${it.response.code} · $reason"
    }
    for (cause in causes) {
        cooldownMessage.matchEntire(cause.message.orEmpty())?.let {
            return "COOLDOWN · 请求较频繁，请在 ${it.groupValues[1]} 秒后重试"
        }
        dohCode.matchEntire(cause.message.orEmpty())?.let {
            val code = it.groupValues[1]
            val reason = when (code) {
                "socket_timeout" -> "连接或读取超时"
                "dns_timeout" -> "DoH 查询超时"
                "bootstrap_lookup_failed" -> "无法解析 DoH 服务器"
                "doh_status_or_content_type" -> "DoH 服务器响应无效"
                "ech_config_missing" -> "服务器未提供 ECH 配置"
                "ech_config_invalid" -> "ECH 配置无效"
                "ech_rejected" -> "服务器拒绝 ECH"
                "tls_handshake_or_certificate_failed", "tls_negotiation_mismatch" -> "TLS 握手或证书校验失败"
                "tls_record_invalid" -> "TLS 数据读取失败"
                "native_unavailable" -> "原生网络组件不可用"
                "profile_closed" -> "网络配置已切换，请重试"
                "connection_failed" -> "网络连接失败"
                "connect_refused" -> "目标拒绝连接"
                "connect_unreachable" -> "目标网络不可达"
                "connect_reset" -> "建连时连接被重置"
                "connect_timeout" -> "建立连接超时"
                "connect_failed" -> "建立连接失败"
                "read_reset" -> "读取时连接被重置"
                "read_timeout" -> "读取图片超时"
                "read_eof" -> "图片传输提前结束"
                "read_failed" -> "读取网络数据失败"
                "write_reset" -> "发送时连接被重置"
                "write_broken_pipe" -> "发送时连接已断开"
                "write_timeout" -> "发送请求超时"
                "write_failed" -> "发送网络数据失败"
                else -> "原生网络请求失败"
            }
            return "DoH $code · $reason"
        }
    }
    val cause = causes.asReversed().firstOrNull {
        it is UnknownHostException || it is SocketTimeoutException || it is SSLException ||
            it is SocketException || it is EOFException || it is InterruptedIOException
    } ?: causes.last()
    val reason = when (cause) {
        is UnknownHostException -> "域名解析失败"
        is SocketTimeoutException -> "连接或读取超时"
        is SSLException -> "TLS 握手或证书校验失败"
        is ConnectException -> "无法建立网络连接"
        is SocketException -> "网络连接中断"
        is EOFException -> "图片响应提前结束"
        is InterruptedIOException -> if (cause.message == "timeout") "请求超时" else "图片请求被中断"
        else -> when {
            cause.message.orEmpty().startsWith("Unable to create a decoder") ||
                cause.message.orEmpty().startsWith("BitmapFactory returned a null bitmap") ||
                cause.message == "无法读取 AVIF 动画" -> "图片解码失败或格式不受支持"
            cause.message == "AVIF 动画尺寸过大" -> "图片尺寸超过解码限制"
            cause is IOException -> "图片读取失败"
            else -> "图片加载失败，原因未识别"
        }
    }
    return "${cause.javaClass.simpleName.ifBlank { "UnknownError" }.take(64)} · $reason"
}
