package com.kudo.android.ui.topic

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun isSvgSignature(url: String): Boolean = url.toHttpUrlOrNull()?.let {
    it.isHttps && it.encodedPath.endsWith(".svg", ignoreCase = true)
} == true

internal fun signatureImageDocument(url: String): String {
    val escaped = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    return """<!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https:; style-src 'unsafe-inline'; script-src 'none'">
        <style>html,body{margin:0;width:100%;overflow:hidden;background:transparent}
        img{display:block;width:100%;height:auto;pointer-events:none}</style>
        </head><body><img src="$escaped" alt=""></body></html>""".trimIndent()
}

/** The signature is an image, so scrolling and taps belong to the surrounding native page. */
internal open class SignatureImageWebView(context: Context) : WebView(context) {
    // No clicks are detected here: the native image container owns accessibility and gestures.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

@Composable
internal fun SvgSignatureImage(url: String, modifier: Modifier = Modifier,
    webViewFactory: (Context) -> WebView = { SignatureImageWebView(it) },
    onAspectRatio: (Float) -> Unit = {}, onFailure: () -> Unit = {}) {
    val failure by rememberUpdatedState(onFailure)
    val dimensions by rememberUpdatedState(onAspectRatio)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    var visible by remember(url) { mutableStateOf(false) }
    val active = visible && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    Box(modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = bounds.width > 0 && bounds.height > 0
    }, contentAlignment = Alignment.Center) {
        // Releasing each inactive view avoids global pauseTimers(), which would pause login/browser pages too.
        key(url, active) {
            var loaded by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf(false) }
            var released by remember { mutableStateOf(false) }
            var webView by remember { mutableStateOf<WebView?>(null) }
            fun release(view: WebView, rendererGone: Boolean = false) {
                if (released) return
                released = true
                if (webView === view) webView = null
                if (rendererGone) {
                    // A dead renderer cannot be reused; detach before destroying its view.
                    (view.parent as? ViewGroup)?.removeView(view)
                } else {
                    view.stopLoading(); view.onPause(); view.removeAllViews()
                }
                view.destroy()
            }
            fun fail() {
                if (failed || released) return
                failed = true; loaded = false
                failure()
            }
            DisposableEffect(webView, loaded) {
                val view = webView
                val observer = view?.viewTreeObserver
                var previousRatio = 0f
                val listener = android.view.ViewTreeObserver.OnPreDrawListener {
                    if (view != null && webView === view && !released) {
                        @Suppress("DEPRECATION")
                        val height = view.contentHeight * view.scale
                        val ratio = if (height > 0) view.width / height else 0f
                        if (ratio.isFinite() && ratio > 0 && kotlin.math.abs(ratio - previousRatio) > .0001f) {
                            previousRatio = ratio
                            view.post { if (webView === view && !released && !failed) dimensions(ratio) }
                        }
                    }
                    true
                }
                if (loaded && !released) observer?.addOnPreDrawListener(listener)
                onDispose {
                    observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
                }
            }
            DisposableEffect(lifecycle, webView) {
                val view = webView
                val observer = LifecycleEventObserver { _, event ->
                    if (view != null && webView === view && !released) when (event) {
                        Lifecycle.Event.ON_PAUSE -> { view.onPause(); view.visibility = View.INVISIBLE }
                        Lifecycle.Event.ON_RESUME -> { view.onResume(); view.visibility = View.VISIBLE }
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            if (active && !failed) AndroidView(factory = { context ->
                webViewFactory(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isLongClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportZoom(false)
                    settings.setSupportMultipleWindows(false)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            if (!request.isForMainFrame && request.method == "GET" && request.url.toString() == url) return null
                            return WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                        }
                        override fun onPageFinished(view: WebView, value: String) {
                            if (!failed && !released && webView === view) loaded = true
                        }
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            if (!released) {
                                val notify = !failed
                                failed = true; loaded = false
                                release(view, rendererGone = true)
                                if (notify) failure()
                            }
                            return true
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame || request.url.toString() == url) fail()
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (response.statusCode >= 400 && (request.isForMainFrame || request.url.toString() == url)) {
                                fail()
                            }
                        }
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                            handler.cancel()
                            if (error.url == url) fail()
                        }
                    }
                    webView = this
                    // Own-host images retain their site cookies; external images remain third-party and receive no site cookies.
                    loadDataWithBaseURL("https://linux.do/", signatureImageDocument(url), "text/html", "UTF-8", null)
                }
            }, onRelease = { release(it) },
                modifier = Modifier.fillMaxSize().alpha(if (loaded) 1f else 0f).testTag("svg-signature-content"))
            if (!loaded && !failed) Text("签名图片", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
