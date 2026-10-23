package com.kudo.android.network

import org.json.JSONObject

data class EdgeSelectionState(val phase: String = "idle", val ip: String? = null,
    val latencyMs: Long? = null, val checkedAt: Long? = null) {
    val busy: Boolean get() = phase in setOf("working", "verifying", "racing")
    val summary: String get() = when (phase) {
        "working", "racing" -> "正在优选可用节点"
        "verifying" -> "正在验证上次节点"
        "ready" -> "本地优选已就绪"
        "fallback" -> "优选暂不可用，使用所选 DoH"
        else -> "等待连接"
    }
}

internal fun decodeEdgeSelectionState(raw: String): EdgeSelectionState = runCatching {
    require(raw.length <= 8192)
    val json = JSONObject(raw)
    val phase = json.optString("phase").takeIf { it in setOf("idle", "verifying", "racing", "ready", "fallback", "off") } ?: "idle"
    val winner = json.optJSONObject("winner")
    val ip = winner?.optString("ip")?.takeIf { it.length in 2..45 && it.all { char -> char in "0123456789abcdefABCDEF:." } }
    EdgeSelectionState(phase, ip, winner?.optLong("latency_ms")?.takeIf { it in 0..60_000 },
        winner?.optLong("checked_at")?.takeIf { it > 0 })
}.getOrDefault(EdgeSelectionState())
