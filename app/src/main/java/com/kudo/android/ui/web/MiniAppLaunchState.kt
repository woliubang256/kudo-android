package com.kudo.android.ui.web

import org.json.JSONObject

internal enum class MiniAppLaunchPhase { LOADING, PERMISSION, READY, ERROR }

/** A snapshot of the official loader, scoped to one exact top-level document. */
internal data class MiniAppLaunchState(
    val url: String,
    val phase: MiniAppLaunchPhase = MiniAppLaunchPhase.LOADING,
    val stage: String = "加载中…",
    val progress: Float? = null,
    val detail: String = "",
    val retryable: Boolean = false,
) {
    val coversPage get() = phase == MiniAppLaunchPhase.LOADING || phase == MiniAppLaunchPhase.ERROR

    companion object {
        fun parse(data: JSONObject, currentUrl: String): MiniAppLaunchState? {
            val appId = MiniAppLocation.parse(currentUrl)?.id ?: return null
            if (data.optString("type") != "launch" || data.optString("url") != currentUrl || data.optString("appId") != appId) return null
            val phase = when (data.optString("phase")) {
                "loading" -> MiniAppLaunchPhase.LOADING
                "permission" -> MiniAppLaunchPhase.PERMISSION
                "ready" -> MiniAppLaunchPhase.READY
                "error" -> MiniAppLaunchPhase.ERROR
                else -> return null
            }
            val progress = (data.opt("progress") as? Number)?.toDouble()
                ?.takeIf { phase == MiniAppLaunchPhase.LOADING && it.isFinite() && it in 0.0..1.0 }?.toFloat()
            val stage = (data.opt("stage") as? String)?.trim()?.take(160)?.takeIf(String::isNotEmpty)
                ?: if (phase == MiniAppLaunchPhase.ERROR) "小程序启动失败" else "加载中…"
            return MiniAppLaunchState(currentUrl, phase, stage, progress,
                if (phase == MiniAppLaunchPhase.ERROR) (data.opt("detail") as? String)?.trim()?.take(1024).orEmpty() else "",
                phase == MiniAppLaunchPhase.ERROR && data.opt("retryable") == true)
        }
    }
}
