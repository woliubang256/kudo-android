package com.kudo.android.ui.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

internal class MiniAppClipboardRequest(
    val write: Boolean, val text: String?, private val valid: () -> Boolean = { true },
    val appId: String? = null,
    private val reply: (String?, String?) -> Unit,
) {
    var completed by mutableStateOf(false)
        private set
    fun cancel(error: String = "USER_CANCELLED") = finish(null, error)
    fun allow(context: Context, onAllowed: () -> Unit = {}) {
        if (completed) return
        // Claim the request before touching the clipboard, including stale/double UI callbacks.
        completed = true
        if (!valid()) { reply(null, "APP_UNLOADED"); return }
        try {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val value: String?
            if (write) {
                if (text.orEmpty().toByteArray(Charsets.UTF_8).size > 4096) { reply(null, "INVALID_ARGUMENTS"); return }
                manager.setPrimaryClip(ClipData.newPlainText("小程序", text.orEmpty()))
                value = null
            } else {
                value = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                if (value.toByteArray(Charsets.UTF_8).size > 4096) { reply(null, "INVALID_ARGUMENTS"); return }
            }
            onAllowed()
            reply(value, null)
        } catch (_: SecurityException) {
            reply(null, "PERMISSION_DENIED")
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
    private val sessionValid: () -> Boolean = active,
    private val launch: (MiniAppLaunchState) -> Unit = {},
    permission: (MiniAppPermissionRequest) -> Unit = { it.cancel() },
    private val presentation: (Boolean) -> Unit = {},
) {
    private val permissionBridge = MiniAppPermissionBridge(view, active, permission)
    private val pendingInfo = mutableMapOf<String, Pair<String, CompletableDeferred<MiniAppInfo>>>()
    private val pendingClipboard = mutableMapOf<Triple<Boolean, String, String>, MiniAppClipboardRequest>()
    private var script: ScriptHandler? = null
    private var presentationScript: ScriptHandler? = null
    private var actionScript: ScriptHandler? = null
    private data class ActionReply(val appId: String, val url: String, val result: CompletableDeferred<JSONObject>)
    private val pendingActions = mutableMapOf<String, ActionReply>()
    private val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private var closed = false
    private val compatibility by lazy {
        view.context.assets.open("mini-app-compat.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    private val presentationCode by lazy {
        view.context.assets.open("mini-app-presentation.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    private val actionCode by lazy {
        view.context.assets.open("mini-app-actions.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
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
                if (data.optString("type") == "launch") {
                    if (mainFrame) MiniAppLaunchState.parse(data, view.url.orEmpty())?.let(launch)
                    return@addWebMessageListener
                }
                val id = data.optString("id").takeIf { it.length in 1..64 }
                val document = data.optString("document").takeIf { it.length in 1..64 }
                val key = if (id != null && document != null) Triple(mainFrame, document, id) else null
                if (data.optString("type") == "clipboard-cancel") {
                    if (key != null) pendingClipboard[key]?.cancel(data.optString("error")
                        .takeIf { it in setOf("USER_CANCELLED", "TIMEOUT", "APP_UNLOADED") } ?: "USER_CANCELLED")
                    return@addWebMessageListener
                }
                if (data.optString("type") == "action-result" && mainFrame) {
                    if (!sessionValid()) return@addWebMessageListener
                    val pending = pendingActions[data.optString("id")] ?: return@addWebMessageListener
                    if (pending.url != view.url || pending.url != data.optString("url") ||
                        pending.appId != location.id || pending.appId != data.optString("appId")) return@addWebMessageListener
                    pendingActions.remove(data.optString("id"))
                    if (data.opt("error") is String) pending.result.completeExceptionally(
                        MiniAppActionFailure(data.getString("error").take(500), data.opt("uncertain") == true))
                    else data.optJSONObject("data")?.let { pending.result.complete(it) }
                        ?: pending.result.completeExceptionally(MiniAppActionFailure("操作结果暂不可用", true))
                    return@addWebMessageListener
                }
                if (!active()) {
                    if (data.optString("type") == "clipboard" && id != null) send(reply, id, null, "USER_CANCELLED")
                    return@addWebMessageListener
                }
                when (data.optString("type")) {
                    "info" -> if (mainFrame) {
                        val pending = pendingInfo.remove(data.optString("id")) ?: return@addWebMessageListener
                        runCatching { MiniAppInfo.parse(data.getJSONObject("data"), pending.first) }
                            .onSuccess { pending.second.complete(it) }.onFailure { pending.second.completeExceptionally(it) }
                    }
                    "info-error" -> if (mainFrame) pendingInfo.remove(data.optString("id"))?.second
                        ?.completeExceptionally(IllegalStateException("应用信息加载失败"))
                    "clipboard" -> {
                        if (id == null || key == null) return@addWebMessageListener
                        val url = data.optString("url")
                        if (url != view.url) { send(reply, id, null, "APP_UNLOADED"); return@addWebMessageListener }
                        if (pendingClipboard.containsKey(key)) return@addWebMessageListener
                        val method = data.optString("method")
                        val text = data.optString("text")
                        if (method !in setOf("read", "write") || (method == "write" && data.opt("text") !is String) ||
                            text.toByteArray(Charsets.UTF_8).size > 4096) {
                            send(reply, id, null, "INVALID_ARGUMENTS")
                        } else {
                            val request = MiniAppClipboardRequest(method == "write", text,
                                valid = { !closed && active() && view.url == url }, appId = location.id) { value, error ->
                                pendingClipboard.remove(key)
                                send(reply, id, value, error)
                            }
                            pendingClipboard[key] = request
                            clipboard(request)
                        }
                    }
                }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                script = WebViewCompat.addDocumentStartJavaScript(view, compatibility, setOf("https://linux.do"))
                actionScript = WebViewCompat.addDocumentStartJavaScript(view, actionCode, setOf("https://linux.do"))
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            presentationScript = WebViewCompat.addDocumentStartJavaScript(view, presentationCode, setOf("https://linux.do"))
        }
    }

    fun installCompatibility() {
        permissionBridge.install()
        if (!closed) view.evaluateJavascript(presentationCode, null)
        if (supported && !closed && MiniAppLocation.parse(view.url.orEmpty()) != null) {
            view.evaluateJavascript(compatibility, null)
            view.evaluateJavascript(actionCode, null)
        }
    }

    suspend fun action(operation: MiniAppOperation, payload: JSONObject = JSONObject()): JSONObject {
        check(supported && !closed && active()) { "当前页面暂不可操作" }
        val url = view.url.orEmpty()
        val id = MiniAppLocation.parse(url)?.id ?: error("当前页面不是小程序")
        val token = UUID.randomUUID().toString()
        val result = CompletableDeferred<JSONObject>()
        pendingActions[token] = ActionReply(id, url, result)
        view.evaluateJavascript("window.__kudoMiniAppAction?.(${JSONObject.quote(token)}, ${JSONObject.quote(operation.wire)}, $payload)", null)
        try {
            return withTimeout(30000) { result.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw MiniAppActionFailure("操作超时，请稍后核对结果", operation in setOf(MiniAppOperation.TIP, MiniAppOperation.REPORT))
        } finally { pendingActions.remove(token) }
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
        permissionBridge.invalidate()
        pendingActions.values.forEach { it.result.cancel() }
        pendingActions.clear()
        pendingClipboard.values.toList().forEach { it.cancel("APP_UNLOADED") }
        pendingClipboard.clear()
        pendingInfo.values.forEach { it.second.cancel() }
        pendingInfo.clear()
    }

    fun close() {
        permissionBridge.close()
        closed = true
        invalidate()
        script?.remove()
        presentationScript?.remove()
        actionScript?.remove()
        if (supported) WebViewCompat.removeWebMessageListener(view, "KudoMiniApp")
    }

    private fun send(reply: JavaScriptReplyProxy, id: String, value: String?, error: String?) {
        if (closed) return
        reply.postMessage(JSONObject().put("id", id).put("value", value ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL).toString())
    }
}
