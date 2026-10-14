package com.kudo.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID

data class DohServer(val id: String, val name: String, val url: String, val bootstrap: List<String> = emptyList()) {
    val builtIn: Boolean get() = BuiltInDohServers.any { it.id == id }
}

val BuiltInDohServer = DohServer("linuxdo-community", "社区 DoH", "https://edge.1molchuan.top/linuxdo")
val ShihuangDohServer = DohServer("shihuang-doh", "始皇DoH", "https://kudo.ddd.oaifree.com/query-dns")
val BuiltInDohServers = listOf(BuiltInDohServer, ShihuangDohServer)

data class DohConfiguration(val enabled: Boolean = false, val selectedId: String = BuiltInDohServer.id,
    val customServers: List<DohServer> = emptyList(), val vpnAutoSwitch: Boolean = true) {
    val servers: List<DohServer> get() = BuiltInDohServers + customServers
    val selected: DohServer get() = servers.firstOrNull { it.id == selectedId } ?: BuiltInDohServer
    fun select(id: String): DohConfiguration = copy(selectedId = servers.firstOrNull { it.id == id }?.id ?: BuiltInDohServer.id)
    fun saveServer(server: DohServer): DohConfiguration {
        require(!server.builtIn) { "内置服务器不能修改" }
        val validated = validateDohServer(server.name, server.url, server.bootstrap.joinToString(","), server.id)
        return copy(customServers = customServers.filterNot { it.id == server.id } + validated)
    }
    fun removeServer(id: String): DohConfiguration {
        require(BuiltInDohServers.none { it.id == id }) { "内置服务器不能删除" }
        return copy(customServers = customServers.filterNot { it.id == id },
            selectedId = if (selectedId == id) BuiltInDohServer.id else selectedId)
    }
}

fun validateDohServer(name: String, rawUrl: String, rawBootstrap: String = "", id: String = UUID.randomUUID().toString()): DohServer {
    require(name.trim().isNotEmpty() && name.trim().length <= 80) { "请输入 1～80 字的服务器名称" }
    val url = rawUrl.trim().toHttpUrlOrNull()
    require(rawUrl.trim().length <= 2048 && url != null && url.isHttps && url.username.isEmpty() &&
        url.password.isEmpty() && url.fragment == null) { "请输入不含账号或片段的 HTTPS DoH 地址" }
    val bootstrap = rawBootstrap.split(Regex("[,，\\s]+")).filter(String::isNotBlank).distinct()
    require(bootstrap.size <= 8 && bootstrap.all(::isBootstrapIp)) { "引导地址只能填写 IP，最多 8 个，以逗号分隔" }
    require(id.isNotBlank() && id.length <= 100) { "服务器标识无效" }
    return DohServer(id, name.trim(), url.toString(), bootstrap)
}

private fun isBootstrapIp(value: String): Boolean {
    if (':' in value) return '%' !in value && '[' !in value && ']' !in value &&
        runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
    return value.split('.').let { parts -> parts.size == 4 && parts.all {
        it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && (it == "0" || !it.startsWith('0')) &&
            (it.toIntOrNull() ?: -1) in 0..255
    } }
}

internal fun encodeDohConfiguration(value: DohConfiguration): String = JSONObject().put("version", 1)
    .put("enabled", value.enabled).put("selectedId", value.selected.id).put("vpnAutoSwitch", value.vpnAutoSwitch)
    .put("servers", JSONArray().apply { value.customServers.forEach { server ->
        put(JSONObject().put("id", server.id).put("name", server.name).put("url", server.url)
            .put("bootstrap", JSONArray(server.bootstrap)))
    } }).toString()

internal fun decodeDohConfiguration(raw: String?): DohConfiguration = runCatching {
    val json = JSONObject(raw.orEmpty())
    require(json.getInt("version") == 1)
    val list = json.optJSONArray("servers") ?: JSONArray()
    val servers = (0 until list.length().coerceAtMost(100)).mapNotNull { index -> runCatching {
        val item = list.getJSONObject(index)
        require(BuiltInDohServers.none { it.id == item.getString("id") })
        val ips = item.optJSONArray("bootstrap") ?: JSONArray()
        validateDohServer(item.getString("name"), item.getString("url"),
            (0 until ips.length()).joinToString(",") { ips.getString(it) }, item.getString("id"))
    }.getOrNull() }.distinctBy { it.id }
    DohConfiguration(json.optBoolean("enabled", false), customServers = servers,
        vpnAutoSwitch = json.opt("vpnAutoSwitch") as? Boolean ?: true).select(json.optString("selectedId"))
}.getOrDefault(DohConfiguration())

class DohSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kudo_network", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(decodeDohConfiguration(prefs.getString("doh", null)))
    val configuration = state.asStateFlow()
    fun update(value: DohConfiguration) {
        val normalized = decodeDohConfiguration(encodeDohConfiguration(value))
        prefs.edit().putString("doh", encodeDohConfiguration(normalized)).apply()
        state.value = normalized
    }
}
