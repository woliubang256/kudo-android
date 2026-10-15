package com.kudo.android.network

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.kudo.android.data.DohConfiguration
import com.kudo.android.data.DohServer
import com.kudo.android.data.DohSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.IdentityHashMap
import kotlin.coroutines.resumeWithException

data class DohNetworkState(val enabled: Boolean = false, val vpn: Boolean = false, val proxy: Boolean = false,
    val echAccepted: Boolean = false, val error: String? = null, val vpnAutoSwitch: Boolean = true) {
    val pausedByVpnOrProxy: Boolean get() = vpnAutoSwitch && (vpn || proxy)
    val effective: Boolean get() = enabled && !pausedByVpnOrProxy
    val summary: String get() = when {
        !enabled -> "已关闭"
        vpnAutoSwitch && vpn -> "VPN 已开启，DoH 暂停"
        vpnAutoSwitch && proxy -> "系统代理已开启，DoH 暂停"
        error != null -> error
        echAccepted -> "DoH + ECH 已连接"
        else -> "已开启，等待连接"
    }
}

internal data class NetworkSnapshot(val id: Long = 0, val vpn: Boolean = false, val proxy: Boolean = false)

/** Shares routing policy, never cookie jars or authenticated interceptors. */
class DohNetwork internal constructor(context: Context, val settings: DohSettings,
    private val snapshotOverride: (() -> NetworkSnapshot)? = null) : Closeable {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var closed = false
    private var routeKey: Pair<DohConfiguration, NetworkSnapshot>? = null
    private var generation = 0L
    private var transport: DohTransport? = null
    private val clients = IdentityHashMap<OkHttpClient, OkHttpClient>()
    private val checks = mutableSetOf<DohTransport>()
    private val current = MutableStateFlow(DohNetworkState())
    val state = current.asStateFlow()
    private val refreshTask = Runnable { refresh() }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scheduleRefresh() }
        override fun onLost(network: Network) { scheduleRefresh() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { scheduleRefresh() }
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) { scheduleRefresh() }
    }
    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scheduleRefresh() }
        override fun onLost(network: Network) { scheduleRefresh() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { scheduleRefresh() }
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) { scheduleRefresh() }
    }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { refresh() }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
    init {
        refresh()
        if (snapshotOverride == null) {
            // NetworkRequest defaults to NOT_VPN; removing it is essential for observing VPN arrival.
            val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            connectivity.registerNetworkCallback(request, callback)
            connectivity.registerDefaultNetworkCallback(defaultCallback)
            (app as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        }
        scope.launch { settings.configuration.collect { refresh() } }
    }
    private fun scheduleRefresh() { handler.removeCallbacks(refreshTask); handler.post(refreshTask) }
    @Suppress("DEPRECATION")
    private fun snapshot(): NetworkSnapshot {
        snapshotOverride?.let { return it() }
        val active = connectivity.activeNetwork
        val vpn = connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        return NetworkSnapshot(active?.networkHandle ?: 0, vpn, connectivity.defaultProxy != null)
    }
    fun refresh() {
        val configuration = settings.configuration.value
        val snapshot = snapshot()
        synchronized(lock) {
            if (closed) return
            val key = configuration to snapshot
            if (key == routeKey) return
            routeKey = key
            generation++
            transport?.close(); transport = null
            checks.toList().forEach { it.close() }; checks.clear()
            clients.forEach { (base, routed) -> base.connectionPool.evictAll(); routed.connectionPool.evictAll() }
            clients.clear()
            current.value = DohNetworkState(configuration.enabled, snapshot.vpn, snapshot.proxy,
                vpnAutoSwitch = configuration.vpnAutoSwitch)
        }
    }
    fun client(base: OkHttpClient): Call.Factory = object : Call.Factory {
        override fun newCall(request: Request): Call {
            refresh() // Also covers startup/foreground races before the first request.
            val selected = synchronized(lock) {
                if (closed) throw IOException("DoH: profile_closed")
                if (!current.value.effective) base else clients.getOrPut(base) {
                    val epoch = generation
                    val native = transport ?: try {
                        DohTransport(settings.configuration.value.selected) { host, accepted ->
                            if (host == "linux.do") synchronized(lock) {
                                if (epoch == generation) current.value = current.value.copy(echAccepted = accepted, error = null)
                            }
                        }.also { transport = it }
                    } catch (e: LinkageError) {
                        current.value = current.value.copy(error = "原生网络组件不可用")
                        throw IOException("DoH: native_unavailable", e)
                    }
                    native.configure(base.newBuilder()).addInterceptor { chain ->
                        try { chain.proceed(chain.request()) } catch (e: IOException) {
                            if (e !is RequestCoolingDown && !chain.call().isCanceled()) synchronized(lock) {
                                if (epoch == generation) current.value = current.value.copy(error = dohErrorMessage(e), echAccepted = false)
                            }
                            throw e
                        }
                    }.build()
                }
            }
            return selected.newCall(request)
        }
    }
    /** No credentials, no Cookie writes, and only a GET to the community's public list. */
    suspend fun check(server: DohServer): String = withContext(Dispatchers.IO) {
        refresh()
        if (state.value.pausedByVpnOrProxy) return@withContext "请先关闭 VPN 或系统代理，再检测直连"
        var accepted = false
        val probe = DohTransport(server) { host, ech -> if (host == "linux.do") accepted = ech }
        synchronized(lock) { checks += probe }
        try {
            val http = probe.configure(OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)).build()
            val call = http.newCall(Request.Builder().url("https://linux.do/latest.json").header("User-Agent", "Kudo-DoH-Check/1").build())
            try {
                val response = suspendCancellableCoroutine<okhttp3.Response> { continuation ->
                    continuation.invokeOnCancellation { call.cancel(); probe.close() }
                    call.enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                        override fun onResponse(call: Call, response: okhttp3.Response) {
                            continuation.resume(response) { _, value, _ -> value.close() }
                        }
                    })
                }
                response.use {
                    val source = it.body?.source()
                    if (source?.request(2L * 1024 * 1024 + 1) == true) "DNS 成功 · ECH 已接受 · 站点响应过大"
                    else dohCheckResult(it, source?.readUtf8().orEmpty(), accepted)
                }
            } finally { http.connectionPool.evictAll() }
        } catch (e: IOException) { currentCoroutineContext().ensureActive(); dohErrorMessage(e) }
        finally { synchronized(lock) { checks -= probe }; probe.close() }
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            transport?.close(); transport = null
            checks.toList().forEach { it.close() }; checks.clear()
            clients.values.forEach { it.connectionPool.evictAll() }; clients.clear()
        }
        scope.cancel(); handler.removeCallbacks(refreshTask)
        if (snapshotOverride == null) {
            connectivity.unregisterNetworkCallback(callback); connectivity.unregisterNetworkCallback(defaultCallback)
            (app as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
        }
    }
}

internal fun dohCheckResult(response: okhttp3.Response, body: String, accepted: Boolean): String = when {
    !accepted -> "DNS 成功，ECH 未协商"
    com.kudo.android.data.isCloudflareChallenge(response, body) -> "DNS 成功 · ECH 已接受 · 站点要求网页验证"
    !response.isSuccessful -> "DNS 成功 · ECH 已接受 · HTTP ${response.code}"
    runCatching { org.json.JSONObject(body).has("topic_list") }.getOrDefault(false) -> "DNS 成功 · ECH 已接受 · 站点访问成功"
    else -> "DNS 成功 · ECH 已接受 · 站点未返回话题数据"
}

internal fun dohErrorMessage(error: IOException): String = when {
    error.message.orEmpty().contains("ech_config_missing") -> "DNS 查询成功，但服务器未提供 ECH 配置"
    error.message.orEmpty().contains("ech_rejected") -> "ECH 被拒绝，已清除缓存，请重试"
    error.message.orEmpty().contains("ech_config_invalid") -> "服务器返回的 ECH 配置无效"
    error.message.orEmpty().contains("doh_status_or_content_type") -> "DoH 服务器未返回标准 DNS 数据"
    error.message.orEmpty().contains("dns_timeout") -> "DoH 查询超时，请检查服务器或网络"
    error.message.orEmpty().contains("bootstrap_lookup_failed") -> "无法解析 DoH 服务器，可填写引导 IP"
    error.message.orEmpty().contains("tls_handshake_or_certificate_failed") -> "TLS 握手或证书校验失败"
    error.message.orEmpty().contains("native_unavailable") -> "原生网络组件不可用"
    else -> "DoH + ECH 连接失败，请检查服务器或网络"
}
