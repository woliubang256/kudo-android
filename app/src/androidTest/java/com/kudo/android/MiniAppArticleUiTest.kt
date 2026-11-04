package com.kudo.android

import androidx.activity.ComponentActivity
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kudo.android.data.DisplaySettings
import com.kudo.android.data.MiniAppEntry
import com.kudo.android.data.MiniAppPreviewCache
import com.kudo.android.data.ThemeMode
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.LocalMiniAppPreviews
import com.kudo.android.ui.topic.RichContent
import com.kudo.android.ui.web.WebScreen
import kotlinx.coroutines.CompletableDeferred
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class MiniAppArticleUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val id = "01a19ba5-b9d2-766f-9fb9-b7eddc10e0c7"
    private val target = "https://linux.do/apps/$id?embed_mode=true&hb=fixture-packet#detail"

    @Test fun miniAppIframeHasAnAppCardAndPreservesLaunchParameters() {
        val links = mutableListOf<String>()
        compose.setContent { KudoTheme(DisplaySettings()) {
            RichContent("""<iframe src="${target.replace("&", "&amp;")}" width="100%" height="700" frameborder="0" allow="fullscreen; clipboard-write" allowfullscreen=""></iframe>""",
                {}, { links += it })
        } }
        compose.onNodeWithTag("mini-app-card").assertIsDisplayed().performClick()
        compose.onNodeWithText("小程序").assertIsDisplayed()
        compose.onNodeWithText("打开媒体网页").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(target), links) }
    }

    @Test fun metadataUpdatesTheCardAndDifferentRedPacketsKeepTheirOwnLaunchParameters() {
        val release = CompletableDeferred<Unit>()
        val links = mutableListOf<String>()
        val other = target.replace("fixture-packet", "another-fixture")
        var calls = 0
        var dark by mutableStateOf(false)
        compose.setContent { KudoTheme(DisplaySettings(theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
            val scope = rememberCoroutineScope()
            val cache = remember { MiniAppPreviewCache(scope, { calls++; release.await(); MiniAppEntry(id, "红包页", null) }) }
            DisposableEffect(cache) { onDispose { cache.close() } }
            CompositionLocalProvider(LocalMiniAppPreviews provides cache) { Column {
                for (url in listOf(target, other)) RichContent("""<iframe src="${url.replace("&", "&amp;")}"></iframe>""", {}, { links += it })
            } }
        } }
        compose.onAllNodesWithTag("mini-app-card")[0].performClick()
        compose.runOnIdle { assertEquals(listOf(target), links); release.complete(Unit) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("红包页").fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithTag("mini-app-card-icon", useUnmergedTree = true)[0].assertIsDisplayed()
        compose.runOnIdle { dark = true }
        compose.onAllNodesWithText("红包页")[1].assertIsDisplayed()
        compose.onAllNodesWithTag("mini-app-card")[1].performClick()
        compose.runOnIdle { assertEquals(listOf(target, other), links); assertEquals(1, calls) }
    }

    @Test fun failedMetadataLeavesAClickableCompactFallbackAndOrdinaryEmbedsRemainOrdinary() {
        val links = mutableListOf<String>()
        val ordinary = listOf("https://example.com/media", "http://linux.do/apps/$id", "https://linux.do.evil.test/apps/$id",
            "https://linux.do:444/apps/$id", "https://linux.do/apps", "https://linux.do/apps/invalid-id")
        compose.setContent { KudoTheme(DisplaySettings()) {
            val scope = rememberCoroutineScope()
            val cache = remember { MiniAppPreviewCache(scope, { error("offline") }) }
            DisposableEffect(cache) { onDispose { cache.close() } }
            CompositionLocalProvider(LocalMiniAppPreviews provides cache) { Column {
                RichContent("""<iframe src="$target"></iframe>""", {}, { links += it })
                for (url in ordinary) RichContent("""<iframe src="$url"></iframe>""", {}, { links += it })
            } }
        } }
        compose.onNodeWithText("小程序").assertIsDisplayed()
        compose.onNodeWithTag("mini-app-card").performClick()
        compose.onAllNodesWithTag("onebox-preview").assertCountEquals(ordinary.size)
        compose.runOnIdle { assertEquals(listOf(target), links) }
    }

    @Test fun openingTheCardEntersTheImmersiveRuntimeAndClosingReturnsToThePost() {
        lateinit var view: WebView
        compose.setContent { KudoTheme(DisplaySettings()) {
            var opened by remember { mutableStateOf<String?>(null) }
            val current = opened
            if (current == null) RichContent("""<iframe src="$target"></iframe>""", {}, { opened = it })
            else WebScreen(current, { opened = null }, {}, { FixtureView(it).also { view = it } })
        } }
        compose.onNodeWithTag("mini-app-card").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("web-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("mini-app-capsule").assertIsDisplayed()
        compose.runOnIdle {
            val url = view.url!!.toHttpUrl()
            assertEquals("/apps/$id", url.encodedPath)
            assertEquals("fixture-packet", url.queryParameter("hb"))
            assertEquals("true", url.queryParameter("embed_mode"))
            assertEquals("detail", url.fragment)
        }
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭网页").performClick()
        compose.onNodeWithTag("mini-app-card").assertIsDisplayed()
    }

    private class FixtureView(context: Context) : WebView(context) {
        override fun setWebViewClient(client: WebViewClient) {
            super.setWebViewClient(object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    WebResourceResponse("text/html", "utf-8", """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><div class="mini-app-page"><div class="mini-app-loader">红包运行时夹具</div></div>""".byteInputStream())
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) = client.onPageStarted(view, url, favicon)
                override fun onPageFinished(view: WebView, url: String?) = client.onPageFinished(view, url)
            })
        }
    }
}
