package com.kudo.android.ui.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

internal class MiniAppClipboardRequest(
    val write: Boolean, val text: String?, private val reply: (String?, String?) -> Unit,
) {
    private var completed = false
    fun cancel() = finish(null, "USER_CANCELLED")
    fun allow(context: Context) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (write) {
            manager.setPrimaryClip(ClipData.newPlainText("小程序", text.orEmpty()))
            finish(null, null)
        } else {
            val value = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
            if (value.toByteArray(Charsets.UTF_8).size > 4096) finish(null, "INVALID_ARGUMENTS") else finish(value, null)
        }
    }
    private fun finish(value: String?, error: String?) {
        if (completed) return
        completed = true
        reply(value, error)
    }
}

/** Only the trusted site and its same-origin frame host receive the message object. */
internal class MiniAppWebBridge(
    private val view: WebView, private val active: () -> Boolean,
    private val clipboard: (MiniAppClipboardRequest) -> Unit,
    private val presentation: (Boolean) -> Unit = {},
) {
    private val pendingInfo = mutableMapOf<String, Pair<String, CompletableDeferred<MiniAppInfo>>>()
    private var script: ScriptHandler? = null
    private var presentationScript: ScriptHandler? = null
    private val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private var closed = false
    private val compatibility by lazy {
        view.context.assets.open("mini-app-compat.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    private val presentationCode by lazy {
        view.context.assets.open("mini-app-presentation.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    val canReportPresentation get() = supported

    init {
        if (supported) {
            WebViewCompat.addWebMessageListener(view, "KudoMiniApp", setOf("https://linux.do")) { _, message, origin, mainFrame, reply ->
                val location = MiniAppLocation.parse(view.url.orEmpty())
                if (closed || origin != Uri.parse("https://linux.do") || location == null)
                    return@addWebMessageListener
                val data = message.data?.takeIf { it.length <= 32768 }?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: return@addWebMessageListener
                if (data.optString("type") == "presentation") {
                    if (mainFrame && location.id != null && data.optString("appId") == location.id &&
                        data.optString("url") == view.url && data.opt("active") is Boolean) presentation(data.getBoolean("active"))
                    return@addWebMessageListener
                }
                if (!active()) return@addWebMessageListener
                when (data.optString("type")) {
                    "info" -> if (mainFrame) {
                        val pending = pendingInfo.remove(data.optString("id")) ?: return@addWebMessageListener
                        runCatching { MiniAppInfo.parse(data.getJSONObject("data"), pending.first) }
                            .onSuccess { pending.second.complete(it) }.onFailure { pending.second.completeExceptionally(it) }
                    }
                    "info-error" -> if (mainFrame) pendingInfo.remove(data.optString("id"))?.second
                        ?.completeExceptionally(IllegalStateException("应用信息加载失败"))
                    "clipboard" -> {
                        val id = data.optString("id").takeIf { it.length in 1..64 } ?: return@addWebMessageListener
                        val method = data.optString("method")
                        val text = data.optString("text")
                        if (method !in setOf("read", "write") || text.toByteArray(Charsets.UTF_8).size > 4096) {
                            send(reply, id, null, "INVALID_ARGUMENTS")
                        } else clipboard(MiniAppClipboardRequest(method == "write", text) { value, error -> send(reply, id, value, error) })
                    }
                }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                script = WebViewCompat.addDocumentStartJavaScript(view, compatibility, setOf("https://linux.do"))
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            presentationScript = WebViewCompat.addDocumentStartJavaScript(view, presentationCode, setOf("https://linux.do"))
        }
    }

    fun installCompatibility() {
        if (!closed) view.evaluateJavascript(presentationCode, null)
        if (supported && !closed && MiniAppLocation.parse(view.url.orEmpty()) != null) {
            view.evaluateJavascript(compatibility, null)
        }
    }

    suspend fun info(id: String): MiniAppInfo = withTimeout(15000) {
        check(supported && !closed)
        require(MiniAppLocation.parse("https://linux.do/apps/$id")?.id == id)
        val token = UUID.randomUUID().toString()
        val result = CompletableDeferred<MiniAppInfo>()
        pendingInfo[token] = id to result
        val encodedId = JSONObject.quote(id)
        val encodedToken = JSONObject.quote(token)
        view.evaluateJavascript("""
            (async () => {
              const id = $encodedId, token = $encodedToken;
              const bridge = window.KudoMiniApp;
              try {
                const response = await fetch('/mini-app-api/public/' + id + '/info', { credentials: 'same-origin' });
                if (!response.ok) throw new Error('HTTP');
                const data = await response.json();
                bridge.postMessage(JSON.stringify({ type: 'info', id: token, data: { app: data.app } }));
              } catch (_) { bridge.postMessage(JSON.stringify({ type: 'info-error', id: token })); }
            })();
        """.trimIndent(), null)
        try { result.await() } finally { pendingInfo.remove(token) }
    }

    fun invalidate() {
        pendingInfo.values.forEach { it.second.cancel() }
        pendingInfo.clear()
    }

    fun close() {
        closed = true
        invalidate()
        script?.remove()
        presentationScript?.remove()
        if (supported) WebViewCompat.removeWebMessageListener(view, "KudoMiniApp")
    }

    private fun send(reply: JavaScriptReplyProxy, id: String, value: String?, error: String?) {
        if (closed) return
        reply.postMessage(JSONObject().put("id", id).put("value", value ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL).toString())
    }
}
