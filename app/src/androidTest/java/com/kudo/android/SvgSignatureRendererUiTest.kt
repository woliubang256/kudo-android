package com.kudo.android

import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.kudo.android.data.DisplaySettings
import com.kudo.android.data.Member
import com.kudo.android.data.Post
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.SignatureImageWebView
import com.kudo.android.ui.topic.SvgSignatureImage
import com.kudo.android.ui.topic.UserSignature
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercise the production callback without killing a real shared WebView renderer. */
class SvgSignatureRendererUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val views = CopyOnWriteArrayList<RecordingWebView>()
    private val fixtureUrl = "https://signature-renderer.test/image.svg"
    private val post = Post(60, 1, Member(1, "svg", "SVG", "", avatarDetailsLoaded = true),
        "正文", "", 0, false, null, null, false, signature = fixtureUrl)

    private fun create(context: Context): WebView = RecordingWebView(context).also { views += it }

    @Test fun crashedRendererRemovesTheFailedSignatureAndKeepsTheNativePost() {
        verifyExit(crashed = true, keepText = false)
    }

    @Test fun reclaimedRendererRemovesOnlyItsImageAndKeepsTheOtherSignatureContent() {
        verifyExit(crashed = false, keepText = true)
    }

    private fun verifyExit(crashed: Boolean, keepText: Boolean) {
        val sample = if (keepText) post.copy(signature = "<p>保留签名文字</p><img src='$fixtureUrl'>") else post
        compose.setContent { KudoTheme(DisplaySettings(showSignatures = true)) {
            Column(Modifier.fillMaxWidth()) {
                Text("原生正文仍可阅读")
                UserSignature(sample, {}, ::create)
            }
        } }
        compose.waitUntil(5_000) { views.isNotEmpty() }
        compose.waitForIdle()
        val view = views.single()
        lateinit var callbacks: WebViewClient
        compose.runOnUiThread {
            callbacks = view.webViewClient
            assertTrue("The signature must handle renderer exit", callbacks.onRenderProcessGone(view, exit(crashed)))
        }
        compose.onNodeWithText("原生正文仍可阅读").assertIsDisplayed()
        compose.onNodeWithTag("svg-signature-content").assertDoesNotExist()
        if (keepText) compose.onNodeWithText("保留签名文字").assertIsDisplayed()
        else compose.onNodeWithTag("user-signature-60").assertDoesNotExist()
        compose.runOnUiThread {
            assertEquals(1, view.destroyCalls)
            callbacks.onPageFinished(view, "https://linux.do/")
            assertTrue("Repeated callbacks must remain handled", callbacks.onRenderProcessGone(view, exit(crashed)))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("原生正文仍可阅读").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, views.size)
            assertEquals(1, view.destroyCalls)
            assertEquals(0, view.callsAfterDestroy)
        }
    }

    @Test fun callbacksFromADisposedSignatureCannotReportFailureOrReuseItsView() {
        var visible by mutableStateOf(true)
        var failures = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            Column {
                Text("原生正文仍可阅读")
                if (visible) SvgSignatureImage(fixtureUrl, Modifier.fillMaxWidth().height(100.dp), ::create,
                    onFailure = { failures++ })
            }
        } }
        compose.waitUntil(5_000) { views.isNotEmpty() }
        compose.waitForIdle()
        val view = views.single()
        lateinit var callbacks: WebViewClient
        compose.runOnUiThread { callbacks = view.webViewClient }
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("svg-signature-content").assertDoesNotExist()
        compose.runOnUiThread {
            assertEquals(1, view.destroyCalls)
            callbacks.onPageFinished(view, "https://linux.do/")
            assertTrue("A disposed signature must not propagate a late renderer exit", callbacks.onRenderProcessGone(view, exit(true)))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {
            assertEquals(0, failures)
            assertEquals(1, views.size)
            assertEquals(1, view.destroyCalls)
            assertEquals(0, view.callsAfterDestroy)
        }
    }

    private fun exit(crashed: Boolean) = object : RenderProcessGoneDetail() {
        override fun didCrash() = crashed
        override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_BOUND
    }

    private inner class RecordingWebView(context: Context) : SignatureImageWebView(context) {
        var destroyCalls = 0
        var callsAfterDestroy = 0
        override fun onPause() { if (destroyCalls > 0) callsAfterDestroy++; super.onPause() }
        override fun onResume() { if (destroyCalls > 0) callsAfterDestroy++; super.onResume() }
        override fun getContentHeight(): Int {
            if (destroyCalls > 0) callsAfterDestroy++
            return super.getContentHeight()
        }
        override fun destroy() { super.destroy(); destroyCalls++ }
        override fun setWebViewClient(client: WebViewClient) {
            super.setWebViewClient(object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (request.url.toString() == fixtureUrl) WebResourceResponse("image/svg+xml", "UTF-8",
                        """<svg xmlns="http://www.w3.org/2000/svg" width="320" height="80"><rect width="320" height="80" fill="blue"/></svg>""".byteInputStream())
                    else client.shouldInterceptRequest(view, request)
                override fun onPageFinished(view: WebView, url: String) = client.onPageFinished(view, url)
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = client.onRenderProcessGone(view, detail)
            })
        }
    }
}
