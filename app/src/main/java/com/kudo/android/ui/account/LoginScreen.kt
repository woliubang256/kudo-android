package com.kudo.android.ui.account

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kudo.android.ui.components.PageTopBar
import com.kudo.android.ui.components.MaterialPageScaffold
import com.kudo.android.ui.components.TopBarTextButton
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(vm: AccountViewModel, back: () -> Unit, verified: () -> Unit,
    webViewFactory: (Context) -> WebView = { WebView(it) }) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { webView?.stopLoading(); webView?.destroy(); webView = null } }
    MaterialPageScaffold(header = {
        PageTopBar("登录与网页验证", back) {
            TopBarTextButton(if (vm.checkingSession) "检查中…" else "完成登录", {
                scope.launch {
                    CookieManager.getInstance().flush()
                    if (vm.checkSession()) verified()
                    else message = vm.sessionError?.message ?: "尚未检测到登录；若已完成验证，可以返回浏览话题。"
                }
            }, enabled = !vm.checkingSession)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface)) {
            Text("请在 Linux.do 官网完成验证和登录，然后点右上角。", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(factory = { context ->
                webViewFactory(context).apply {
                    // WRAP_CONTENT makes Chromium's CSS viewport units resolve to zero in AndroidView.
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme != "https"
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { loading = true }
                        override fun onPageFinished(view: WebView, url: String) { loading = false; CookieManager.getInstance().flush() }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) { loading = false; message = "网页加载失败，请检查网络后重新进入。" } }
                    }
                    webChromeClient = WebChromeClient()
                    webView = this
                    loadUrl("https://linux.do/login")
                }
            }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}
