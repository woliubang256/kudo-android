package com.kudo.android.ui.web

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

internal enum class MiniAppPermissionKind(val wire: String, val title: String, val deny: String, val allow: String) {
    AUDIO("audio", "允许播放声音？", "静音进入", "开启声音"),
    VIDEO("video", "允许播放视频？", "取消", "仅本次允许"),
    FULLSCREEN("fullscreen", "允许进入全屏？", "取消", "仅本次允许"),
}

internal class MiniAppPermissionRequest(val kind: MiniAppPermissionKind, val preview: String?,
    private val choice: (Boolean?) -> Unit) {
    var completed by mutableStateOf(false)
        private set
    var choosing by mutableStateOf(false)
        private set
    fun select(allow: Boolean) {
        if (completed || choosing) return
        choosing = true
        choice(allow)
    }
    fun cancel() {
        if (completed) return
        completed = true
        choice(null)
    }
    fun finish() { completed = true }
}

/** Adapts identified site dialogs; the website still owns confirmation, execution and errors. */
internal class MiniAppPermissionBridge(
    private val view: WebView, private val active: () -> Boolean,
    private val show: (MiniAppPermissionRequest) -> Unit,
) {
    private data class Pending(val document: String, val id: String, val url: String, val appId: String,
        val request: MiniAppPermissionRequest)
    private var pending: Pending? = null
    private var script: ScriptHandler? = null
    private var closed = false
    private val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private val code by lazy { view.context.assets.open("mini-app-permission.js").bufferedReader(Charsets.UTF_8).use { it.readText() } }

    init {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, "KudoMiniAppPermission", setOf("https://linux.do")) { _, message, origin, main, _ ->
                if (closed || !main || origin != "https://linux.do".toUri()) return@addWebMessageListener
                val data = message.data?.takeIf { it.length <= 4096 }?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: return@addWebMessageListener
                val doc = data.optString("document").takeIf { it.length in 1..64 } ?: return@addWebMessageListener
                val id = data.optString("id").takeIf { it.length in 1..64 } ?: return@addWebMessageListener
                val url = data.optString("url")
                val appId = data.optString("appId")
                val kind = MiniAppPermissionKind.entries.find { it.wire == data.optString("kind") } ?: return@addWebMessageListener
                val preview = data.optString("preview")
                if (preview.length > 200 || (kind == MiniAppPermissionKind.VIDEO && preview.isBlank()) ||
                    (kind != MiniAppPermissionKind.VIDEO && preview.isNotEmpty())) return@addWebMessageListener
                val old = pending
                if (data.optString("type") == "finished") {
                    if (old != null && old.document == doc && old.id == id && old.url == url && old.appId == appId &&
                        old.request.kind == kind) {
                        pending = null; old.request.finish()
                    }
                    return@addWebMessageListener
                }
                if (data.optString("type") != "request" || url != view.url || MiniAppLocation.parse(url)?.id != appId ||
                    appId.isEmpty()) return@addWebMessageListener
                if (old != null) return@addWebMessageListener
                lateinit var p: Pending
                val request = MiniAppPermissionRequest(kind, preview.takeIf { it.isNotEmpty() }) { allow ->
                    if (allow == null) {
                        if (pending === p) { pending = null; command(p, "cancel") }
                    } else if (valid(p)) {
                        // Relay only this explicit choice to its original button so the site's
                        // browser activation path is preserved, without relaxing media settings.
                        command(p, if (allow) "allow" else "deny") { result -> relay(p, result) }
                    } else { pending = null; p.request.finish(); command(p, "cancel") }
                }
                p = Pending(doc, id, url, appId, request)
                pending = p
                if (!active()) request.cancel() else { command(p, "accepted"); show(request) }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                script = WebViewCompat.addDocumentStartJavaScript(view, code, setOf("https://linux.do"))
        }
    }

    fun install() { if (supported && !closed) view.evaluateJavascript(code, null) }
    fun invalidate() { pending?.request?.cancel() }
    fun close() {
        invalidate(); closed = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) script?.remove()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.removeWebMessageListener(view, "KudoMiniAppPermission")
    }

    private fun valid(p: Pending) = !closed && pending === p && !p.request.completed && active() && view.url == p.url
    private fun command(p: Pending, command: String, callback: ((String) -> Unit)? = null) {
        if (closed) return
        view.evaluateJavascript("window.__kudoMiniAppPermission?.(${JSONObject.quote(p.document)}, ${JSONObject.quote(p.id)}, ${JSONObject.quote(command)})", callback)
    }
    private fun relay(p: Pending, result: String) {
        if (!valid(p)) { p.request.cancel(); return }
        val point = runCatching {
            val a = JSONArray(result)
            require(a.length() == 4)
            val x = a.getDouble(0); val y = a.getDouble(1)
            val width = a.getDouble(2); val height = a.getDouble(3)
            require(listOf(x, y, width, height).all { it.isFinite() } && width > 0 && height > 0 &&
                x >= 0 && y >= 0 && x < width && y < height && view.width > 0 && view.height > 0 && view.isShown)
            (x * view.width / width).toFloat() to (y * view.height / height).toFloat()
        }.getOrNull()
        if (point == null) { command(p, "fallback"); return }
        val down = SystemClock.uptimeMillis()
        fun dispatch(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        dispatch(MotionEvent.ACTION_DOWN)
        view.postDelayed({ if (!closed) dispatch(if (valid(p)) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL) }, 40)
    }
}
