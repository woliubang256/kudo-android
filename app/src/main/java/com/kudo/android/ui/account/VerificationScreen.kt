package com.kudo.android.ui.account

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kudo.android.data.*
import com.kudo.android.ui.components.CommunityIconButton
import com.kudo.android.ui.components.PageTopBar
import com.kudo.android.ui.components.MaterialPageScaffold
import com.kudo.android.ui.components.TopBarTextButton
import com.kudo.android.ui.topic.LocalReadingBlocked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ChallengeUrl = "https://linux.do/challenge"
internal val LocalWebVerification = staticCompositionLocalOf<((() -> Unit) -> Unit)?> { null }

@Composable
internal fun VerificationHost(client: DiscourseClient, userAgent: String,
    webViewFactory: (Context) -> WebView = { WebView(it) }, content: @Composable () -> Unit) {
    var pendingRetry by remember { mutableStateOf<(() -> Unit)?>(null) }
    val blocked = LocalReadingBlocked.current || pendingRetry != null
    CompositionLocalProvider(LocalWebVerification provides { retry -> pendingRetry = retry }, LocalReadingBlocked provides blocked) {
        Box(Modifier.fillMaxSize().then(if (pendingRetry != null) Modifier.clearAndSetSemantics {} else Modifier)) { content() }
    }
    if (pendingRetry != null) Dialog(onDismissRequest = { pendingRetry = null }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        VerificationScreen(userAgent, { pendingRetry = null }, {
            withContext(Dispatchers.IO) {
                val payload = client.get("/latest.json")
                if (payload.optJSONObject("topic_list")?.optJSONArray("topics") == null)
                    throw ApiFailure(FailureKind.SERVER, "暂时无法确认验证结果，请稍后重试")
            }
        }, {
            val retry = pendingRetry
            pendingRetry = null
            retry?.invoke()
        }, webViewFactory)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun VerificationScreen(userAgent: String, back: () -> Unit, check: suspend () -> Unit, completed: () -> Unit,
    webViewFactory: (Context) -> WebView = { WebView(it) }) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    MaterialPageScaffold(modifier = Modifier.testTag("verification-page"), header = {
        PageTopBar("网页验证", back) {
            CommunityIconButton(Icons.Rounded.Refresh, "刷新验证页", {
                message = null
                webView?.loadUrl(ChallengeUrl)
            }, modifier = Modifier.testTag("verification-refresh"), enabled = !checking)
            TopBarTextButton(if (checking) "检查中…" else "完成验证", {
                if (checking) return@TopBarTextButton
                checking = true
                message = null
                scope.launch {
                    try {
                        CookieManager.getInstance().flush()
                        check()
                        completed()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: "暂时无法确认验证结果，请稍后重试" }
                    finally { checking = false }
                }
            }, enabled = !checking, modifier = Modifier.testTag("verification-complete"))
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface)) {
            Text("如果页面显示不存在，请多刷新几次，直到出现验证页面；完成过盾后，再点击「完成验证」。",
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(factory = { context -> webViewFactory(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.userAgentString = userAgent
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.scheme != "https"
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { loading = true }
                    override fun onPageFinished(view: WebView, url: String) { loading = false; CookieManager.getInstance().flush() }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) { loading = false; message = "网页加载失败，请点击刷新重试" }
                    }
                }
                webChromeClient = WebChromeClient()
                webView = this
                loadUrl(ChallengeUrl)
            } }, onRelease = { view ->
                webView = null
                view.stopLoading(); view.removeAllViews(); view.destroy()
                CookieManager.getInstance().flush()
            }, modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding())
        }
    }
}
