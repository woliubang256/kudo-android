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

internal class MiniAppAudioRequest(private val choice: (Boolean?) -> Unit) {
    var completed by mutableStateOf(false)
        private set
    var choosing by mutableStateOf(false)
        private set
    fun select(muted: Boolean) {
        if (completed || choosing) return
        choosing = true
        choice(muted)
    }
    fun cancel() {
        if (completed) return
        completed = true
        choice(null)
    }
    fun finish() { completed = true }
}

/** Adapts only the official audio dialog; the website still owns both callbacks and playback. */
internal class MiniAppAudioPermission(
    private val view: WebView, private val active: () -> Boolean,
    private val show: (MiniAppAudioRequest) -> Unit,
) {
    private data class Pending(val document: String, val id: String, val url: String, val appId: String,
        val request: MiniAppAudioRequest)
    private var pending: Pending? = null
    private var script: ScriptHandler? = null
    private var closed = false
    private val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private val code by lazy { view.context.assets.open("mini-app-audio.js").bufferedReader(Charsets.UTF_8).use { it.readText() } }

    init {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, "KudoMiniAppAudio", setOf("https://linux.do")) { _, message, origin, main, _ ->
                if (closed || !main || origin != "https://linux.do".toUri()) return@addWebMessageListener
                val data = message.data?.takeIf { it.length <= 2048 }?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: return@addWebMessageListener
                val doc = data.optString("document").takeIf { it.length in 1..64 } ?: return@addWebMessageListener
                val id = data.optString("id").takeIf { it.length in 1..64 } ?: return@addWebMessageListener
                val url = data.optString("url")
                val appId = data.optString("appId")
                val old = pending
                if (data.optString("type") == "finished") {
                    if (old != null && old.document == doc && old.id == id && old.url == url && old.appId == appId) {
                        pending = null; old.request.finish()
                    }
                    return@addWebMessageListener
                }
                if (data.optString("type") != "request" || url != view.url || MiniAppLocation.parse(url)?.id != appId ||
                    appId.isEmpty()) return@addWebMessageListener
                if (old != null) return@addWebMessageListener
                lateinit var p: Pending
                val request = MiniAppAudioRequest { muted ->
                    if (muted == null) {
                        if (pending === p) { pending = null; command(p, "cancel") }
                    } else if (valid(p)) {
                        // Relay only this explicit choice to its original button so the site's
                        // browser activation path is preserved, without relaxing media settings.
                        command(p, if (muted) "muted" else "enabled") { result -> relay(p, result) }
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
            WebViewCompat.removeWebMessageListener(view, "KudoMiniAppAudio")
    }

    private fun valid(p: Pending) = !closed && pending === p && !p.request.completed && active() && view.url == p.url
    private fun command(p: Pending, command: String, callback: ((String) -> Unit)? = null) {
        if (closed) return
        view.evaluateJavascript("window.__kudoMiniAppAudio?.(${JSONObject.quote(p.document)}, ${JSONObject.quote(p.id)}, ${JSONObject.quote(command)})", callback)
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
        view.postDelayed({ dispatch(if (valid(p)) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL) }, 40)
    }
}
