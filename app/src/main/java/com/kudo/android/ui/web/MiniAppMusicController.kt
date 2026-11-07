package com.kudo.android.ui.web

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.compose.runtime.*
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.kudo.android.media.MiniAppMusicService
import org.json.JSONObject

internal object MiniAppMusic {
    var current by mutableStateOf<MiniAppMusicController?>(null)
        private set
    fun changed(controller: MiniAppMusicController) {
        if (!controller.state.canControl) { if (current === controller) clear(controller); return }
        if (current !== controller) current?.command("pause")
        current = controller
        MiniAppMusicService.update(controller)
    }
    fun clear(controller: MiniAppMusicController) {
        if (current !== controller) return
        current = null
        MiniAppMusicService.clear(controller.context)
    }
    fun close(key: MiniAppTaskKey) { current?.takeIf { it.key == key }?.close() }
}

/** Only the official main frame gets a native listener. The opaque app has a DOM-only adapter. */
internal class MiniAppMusicController(private val view: WebView, val request: MiniAppTaskRequest, private val valid: () -> Boolean) {
    val key get() = request.key
    val context: Context get() = view.context.applicationContext
    var state by mutableStateOf(MiniAppMusicState())
        private set
    var hidden by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var closing = false
    private var closed = false
    private var generation = 0
    private var script: ScriptHandler? = null
    private val supported = key.supportsMusic() && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    init {
        if (key.supportsMusic() && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addWebMessageListener(view, "KudoMiniAppMusic", setOf("https://linux.do")) { _, message, origin, main, _ ->
                if (closed || !valid() || !main || origin != Uri.parse("https://linux.do")) return@addWebMessageListener
                val data = message.data?.takeIf { it.length <= 4096 }?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: return@addWebMessageListener
                if (data.optString("type") != "kudo-mini-music-v1" || data.optString("appId") != key.appId ||
                    data.optString("url") != view.url || MiniAppLocation.parse(view.url.orEmpty())?.id != key.appId ||
                    data.opt("playing") !is Boolean || data.opt("canControl") !is Boolean) return@addWebMessageListener
                val next = MiniAppMusicState(data.optString("title").take(200), data.optString("artist").take(200),
                    data.getBoolean("playing"), data.getBoolean("canControl"), data.optBoolean("canSkip"))
                if (!state.playing && next.playing) hidden = false
                if (closing && !next.playing) { hidden = true; closing = false }
                state = next; busy = false; error = null; generation++
                MiniAppMusic.changed(this)
            }
            // Wildcard permits the opaque-origin runtime, but the asset immediately gates exact URLs.
            // It has no native message object; only the verified official top frame relays state.
            val code = view.context.assets.open("mini-app-music.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
            script = WebViewCompat.addDocumentStartJavaScript(view, code, setOf("*"))
        }
    }

    fun command(action: String) {
        if (closed || !supported || !valid() || !state.canControl || busy ||
            action !in setOf("play", "pause", "previous", "next") ||
            MiniAppLocation.parse(view.url.orEmpty())?.id != key.appId) return
        busy = true; error = null
        val token = ++generation
        view.evaluateJavascript("window.__kudoMiniMusicCommand?.(${JSONObject.quote(action)})", null)
        view.postDelayed({ if (!closed && generation == token) { busy = false; closing = false; error = "播放器暂无响应" } }, 4000)
    }

    fun hideAndPause() {
        if (!state.playing) { hidden = true; return }
        if (busy) return
        closing = true
        command("pause")
    }

    fun invalidate() {
        state = MiniAppMusicState(); busy = false; hidden = false; closing = false; generation++
        MiniAppMusic.clear(this)
    }

    fun close() {
        if (closed) return
        closed = true
        // Teardown overrides a pending UI command. Pause the original player before
        // removing its bridge, and reject all delayed state reports from this point.
        if (supported && valid() && state.canControl && MiniAppLocation.parse(view.url.orEmpty())?.id == key.appId)
            runCatching { view.evaluateJavascript("window.__kudoMiniMusicCommand?.('pause')", null) }
        invalidate()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) script?.remove()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(view, "KudoMiniAppMusic")
    }
}
