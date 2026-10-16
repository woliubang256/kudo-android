package com.kudo.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.AndroidSessionCookies
import com.kudo.android.data.DisplaySettings
import com.kudo.android.data.ExternalLinkMode
import com.kudo.android.data.ThemeMode
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.components.QuickScrollToTopHost
import com.kudo.android.ui.web.WebScreen
import org.junit.*
import org.junit.Assert.*
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.File

class WebScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val views = mutableListOf<FixtureWebView>()
    private val loadedTitle = AtomicReference("")
    private val loads = AtomicInteger()
    private val mainLoads = AtomicInteger()
    private val navigations = mutableListOf<String>()
    private var failFirst = false
    @Volatile private var responseGate: CountDownLatch? = null
    private val start = "https://reader-fixture.test/guidelines"

    private fun create(context: Context) = FixtureWebView(context).also { views += it }
    private fun current() = views.last()
    private fun show(close: () -> Unit = {}, external: (String) -> Unit = {}, app: (String) -> Boolean = { false }) {
        compose.setContent { KudoTheme(DisplaySettings()) { WebScreen(start, close, external, ::create, app) } }
        awaitPage("指南")
    }
    private fun awaitPage(title: String) {
        compose.waitUntil(10000) { loadedTitle.get() == title }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("web-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(title).assertIsDisplayed()
    }
    private fun javascript(script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        compose.runOnUiThread { current().evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("JavaScript callback timed out", latch.await(5, TimeUnit.SECONDS))
        return result
    }
    private fun click(id: String) {
        val point = JSONArray(javascript("(()=>{const r=document.getElementById('$id').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,innerWidth]})()"))
        val node = compose.onNodeWithTag("web-content")
        val scale = node.fetchSemanticsNode().boundsInRoot.width / point.getDouble(2).toFloat()
        loadedTitle.set("")
        node.performTouchInput { click(Offset(point.getDouble(0).toFloat() * scale, point.getDouble(1).toFloat() * scale)) }
    }

    @After fun removeFixtureCookies() {
        responseGate?.countDown()
        compose.runOnUiThread {
            val manager = CookieManager.getInstance()
            listOf("app_fixture", "web_fixture").forEach {
                manager.setCookie(start, "$it=; Max-Age=0; Path=/; Secure")
            }
            manager.flush()
        }
    }

    @Test fun initialPageShowsLoadingUntilContentAppearsEvenIfProgressFinishesFirst() {
        val gate = CountDownLatch(1)
        responseGate = gate
        try {
            compose.setContent { KudoTheme(DisplaySettings()) { WebScreen(start, {}, {}, ::create) } }
            compose.onNodeWithTag("web-loading").assertIsDisplayed()
            capture("ui-polish-web-loading.png")
            compose.onNodeWithText("reader-fixture.test").assertDoesNotExist()
            compose.runOnUiThread { current().webChromeClient?.onProgressChanged(current(), 100) }
            compose.onNodeWithTag("web-loading").assertIsDisplayed()
            gate.countDown()
            awaitPage("指南")
            compose.onNodeWithTag("web-loading").assertDoesNotExist()
            compose.onNodeWithText("reader-fixture.test").assertDoesNotExist()
            capture("ui-polish-web-loaded.png")
        } finally { gate.countDown() }
    }

    @Test fun darkLoadingKeepsWindowStatusBarAndToolbarOnTheSameTheme() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        val background = AtomicInteger()
        val gate = CountDownLatch(1)
        responseGate = gate
        try {
            compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) {
                val colors = MaterialTheme.colorScheme
                SideEffect { background.set(colors.background.toArgb()) }
                WebScreen(start, {}, {}, ::create)
            } }
            fun assertDarkChrome(stage: String) {
                compose.onNodeWithTag("web-loading").assertIsDisplayed()
                capture("browser-dark-$stage.png")
                val titleBounds = compose.onNodeWithText(if (stage == "initial") "网页" else "指南").fetchSemanticsNode().boundsInRoot
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try {
                    val status = screenshot.getPixel(screenshot.width / 2, 4)
                    val toolbar = screenshot.getPixel(screenshot.width - 4, titleBounds.center.y.toInt())
                    assertTrue("$stage status bar and glass toolbar share the same dark tone",
                        kotlin.math.abs(android.graphics.Color.red(status) - android.graphics.Color.red(toolbar)) <= 3 &&
                        kotlin.math.abs(android.graphics.Color.green(status) - android.graphics.Color.green(toolbar)) <= 3 &&
                        kotlin.math.abs(android.graphics.Color.blue(status) - android.graphics.Color.blue(toolbar)) <= 3)
                    assertTrue("$stage chrome stays dark", androidx.core.graphics.ColorUtils.calculateLuminance(status) < .15)
                } finally { screenshot.recycle() }
                val header = compose.onNodeWithTag("material-page-header").fetchSemanticsNode().boundsInRoot
                val content = compose.onNodeWithTag("web-content").fetchSemanticsNode().boundsInRoot
                assertTrue("$stage web viewport stays below the toolbar", content.top >= header.bottom)
                compose.runOnIdle {
                    assertFalse(WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars)
                    assertEquals("$stage native window fallback", background.get(), (compose.activity.window.decorView.background as? ColorDrawable)?.color)
                }
            }
            assertDarkChrome("initial")
            gate.countDown()
            awaitPage("指南")
            val refreshGate = CountDownLatch(1)
            responseGate = refreshGate
            try {
                compose.onNodeWithContentDescription("刷新网页").performClick()
                assertDarkChrome("refresh")
            } finally { refreshGate.countDown() }
            awaitPage("指南")
        } finally { gate.countDown(); responseGate?.countDown() }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun quickReturnWaitsForAViewportAndReturnsTheWebPageToTheTop() {
        compose.setContent { KudoTheme(DisplaySettings()) { QuickScrollToTopHost {
            WebScreen(start, {}, {}, ::create)
        } } }
        awaitPage("指南")
        javascript("document.body.style.minHeight='6000px'")
        compose.runOnUiThread { current().scrollTo(0, current().height / 4) }
        compose.onNodeWithTag("quick-scroll-to-top").assertDoesNotExist()
        compose.runOnUiThread { current().scrollTo(0, current().height * 2) }
        compose.onNodeWithTag("quick-scroll-to-top").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { current().scrollY == 0 }
        compose.onNodeWithTag("quick-scroll-to-top").assertDoesNotExist()
    }

    @Test fun refreshingShowsLoadingAgainUntilContentAppears() {
        show()
        val gate = CountDownLatch(1)
        responseGate = gate
        try {
            loadedTitle.set("")
            compose.onNodeWithContentDescription("刷新网页").performClick()
            compose.onNodeWithTag("web-loading").assertIsDisplayed()
            gate.countDown()
            awaitPage("指南")
            compose.onNodeWithTag("web-loading").assertDoesNotExist()
        } finally { gate.countDown() }
    }

    @Test fun siteLinksNewWindowsAndRedirectsStayInWebHistory() {
        var closed = 0
        val external = mutableListOf<String>()
        show({ closed++ }, { external += it })
        click("topic"); awaitPage("网页帖子")
        click("profile"); awaitPage("网页用户")
        click("category"); awaitPage("网页分类")
        click("blank"); awaitPage("新窗口网页")
        click("redirect"); awaitPage("重定向后的网页帖子")
        compose.runOnIdle { assertEquals(1, views.size); assertEquals(0, closed); assertTrue(external.isEmpty()) }
        compose.onNodeWithContentDescription("返回").performClick()
        awaitPage("新窗口网页")
        compose.onNodeWithContentDescription("在浏览器打开").performClick()
        compose.runOnIdle { assertEquals(listOf("https://linux.do/t/topic/3001000/9"), external) }
        compose.onNodeWithContentDescription("关闭网页").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun userAppLinkHandsOffWithoutChangingTheCurrentWebPage() {
        val opened = mutableListOf<String>()
        show(app = { opened += it; true })
        click("quark")
        compose.runOnIdle {
            assertEquals(listOf("https://pan.quark.cn/s/Abc123?pwd=1234"), opened)
            assertEquals(start, current().url)
        }
        compose.onNodeWithText("指南").assertIsDisplayed()
        click("other")
        compose.runOnIdle {
            assertEquals("https://other-fixture.test/page", opened.last())
            assertEquals(start, current().url)
        }
    }

    @Test fun openingModeChangesApplyWithoutReloadAndInAppSharesNeverHandOff() {
        var mode by mutableStateOf(ExternalLinkMode.SYSTEM)
        val opened = mutableListOf<String>()
        compose.setContent { KudoTheme(DisplaySettings()) {
            WebScreen(start, {}, {}, ::create, { opened += it; true }, externalLinkMode = mode)
        } }
        awaitPage("指南")
        click("quark")
        val previous = mainLoads.get()
        compose.runOnIdle { assertEquals(1, opened.size); mode = ExternalLinkMode.IN_APP }
        compose.runOnIdle { assertEquals(previous, mainLoads.get()); assertEquals(1, views.size) }
        click("quark"); awaitPage("夸克网页")
        compose.runOnIdle { assertEquals(1, opened.size) }
        compose.onNodeWithContentDescription("返回").performClick(); awaitPage("指南")
        javascript("document.querySelectorAll('a').forEach(a=>a.style.display='block')")
        click("quark-scheme"); awaitPage("夸克网页")
        compose.runOnIdle {
            assertEquals(1, opened.size)
            assertEquals("https://pan.quark.cn/s/Abc123?pwd=1234", current().url)
        }
        compose.onNodeWithContentDescription("返回").performClick(); awaitPage("指南")
        compose.runOnIdle { mode = ExternalLinkMode.SYSTEM }
        click("other")
        compose.runOnIdle {
            assertEquals(2, opened.size)
            assertEquals("https://other-fixture.test/page", opened.last())
            assertEquals(start, current().url)
        }
    }

    @Test fun missingAppAndPublishedSchemeFallBackToTheShareWebPage() {
        val attempted = mutableListOf<String>()
        show(app = { attempted += it; false })
        click("quark"); awaitPage("夸克网页")
        compose.onNodeWithContentDescription("返回").performClick(); awaitPage("指南")
        // Separate the fixture's adjacent touch targets before checking scheme navigation.
        javascript("document.querySelectorAll('a').forEach(a=>a.style.display='block')")
        click("quark-scheme")
        compose.runOnIdle { assertEquals("Navigation requests: $navigations", 2, attempted.size) }
        awaitPage("夸克网页")
        compose.runOnIdle {
            assertEquals(2, attempted.size)
            assertTrue(attempted.last().startsWith("qklink://"))
            assertEquals("https://pan.quark.cn/s/Abc123?pwd=1234", current().url)
        }
    }

    @Test fun scriptsRedirectsFramesAndSitePagesNeverInvokeAppHandoff() {
        val opened = mutableListOf<String>()
        show(app = { opened += it; true })
        // A real script navigation must stay in web history, even if an app could handle it.
        javascript("location.href='https://pan.quark.cn/s/Abc123?pwd=1234'")
        awaitPage("夸克网页")
        fun request(value: String, frame: Boolean = true, gesture: Boolean = true, redirect: Boolean = false) =
            object : WebResourceRequest {
                override fun getUrl() = android.net.Uri.parse(value)
                override fun isForMainFrame() = frame
                override fun hasGesture() = gesture
                override fun isRedirect() = redirect
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = emptyMap<String, String>()
            }
        compose.runOnUiThread {
            val view = current()
            val client = view.webViewClient!!
            assertFalse(client.shouldOverrideUrlLoading(view, request("https://pan.quark.cn/s/Abc123", redirect = true)))
            assertFalse(client.shouldOverrideUrlLoading(view, request("https://pan.quark.cn/s/Abc123", frame = false)))
            assertFalse(client.shouldOverrideUrlLoading(view, request("https://linux.do/t/123")))
            assertFalse(client.shouldOverrideUrlLoading(view, request("https://connect.linux.do/help")))
            assertTrue(client.shouldOverrideUrlLoading(view, request("intent://evil")))
            assertTrue(client.shouldOverrideUrlLoading(view, request(
                "qklink://www.uc.cn/b20b84fd735a8dd3f7541129bacc4e9a?action=open_url&url=https%3A%2F%2Fpan.quark.cn%2Fs%2FAbc123", gesture = false)))
        }
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
    }

    @Test fun cookieStoreIsSharedWithAppAndIsolatedFromOtherOrigins() {
        compose.runOnUiThread { AndroidSessionCookies().set(start, "app_fixture=from_app; Path=/; Secure; SameSite=Lax") }
        show()
        assertTrue(javascript("document.cookie").contains("app_fixture=from_app"))
        javascript("document.cookie='web_fixture=from_web; Path=/; Secure; SameSite=Lax'")
        compose.runOnIdle {
            assertTrue(AndroidSessionCookies().get(start).contains("web_fixture=from_web"))
            assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(current()))
            assertFalse(current().settings.allowFileAccess)
            assertFalse(current().settings.allowContentAccess)
        }
        click("other"); awaitPage("其他网站")
        assertFalse(javascript("document.cookie").contains("app_fixture"))
        assertFalse(javascript("document.cookie").contains("web_fixture"))
    }

    @Test fun restoredPageKeepsItsHistoryAndClosingReleasesTheWebView() {
        val restoration = StateRestorationTester(compose)
        var visible by mutableStateOf(true)
        restoration.setContent { KudoTheme(DisplaySettings()) {
            if (visible) WebScreen(start, { visible = false }, {}, ::create) else Text("原话题")
        } }
        awaitPage("指南")
        click("topic"); awaitPage("网页帖子")
        loadedTitle.set("")
        restoration.emulateSavedInstanceStateRestore()
        awaitPage("网页帖子")
        compose.runOnIdle { assertEquals(2, views.size); assertTrue(views.first().released); assertTrue(current().canGoBack()) }
        compose.onNodeWithContentDescription("返回").performClick()
        awaitPage("指南")
        compose.onNodeWithContentDescription("关闭网页").performClick()
        compose.onNodeWithText("原话题").assertIsDisplayed()
        compose.runOnIdle { assertTrue(current().released) }
    }

    @Test fun errorsCanRetryAndRecompositionDoesNotReloadTheInitialPage() {
        failFirst = true
        var dark by mutableStateOf(false)
        val background = AtomicInteger()
        compose.setContent { KudoTheme(DisplaySettings(theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
            val color = MaterialTheme.colorScheme.background.toArgb()
            SideEffect { background.set(color) }
            WebScreen(start, {}, {}, ::create)
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("web-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("web-loading").assertDoesNotExist()
        compose.onNodeWithText("重新加载").performClick()
        awaitPage("指南")
        val previous = loads.get()
        compose.runOnIdle {
            assertEquals(background.get(), (compose.activity.window.decorView.background as? ColorDrawable)?.color)
            dark = true
        }
        compose.runOnIdle {
            assertEquals(previous, loads.get()); assertEquals(1, views.size)
            assertEquals(background.get(), (compose.activity.window.decorView.background as? ColorDrawable)?.color)
        }
        compose.onNodeWithContentDescription("刷新网页").performClick()
        compose.waitUntil(10000) { loads.get() > previous }
        awaitPage("指南")
    }

    @Test fun cssViewportHeightDoesNotCollapseEmbeddedFrames() {
        show()
        val dimensions = JSONArray(javascript("[innerHeight, document.getElementById('vh').offsetHeight, document.getElementById('frame').offsetHeight]"))
        assertEquals("100vh must match the viewport", dimensions.getDouble(0), dimensions.getDouble(1), 2.0)
        assertEquals("Viewport cap must not hide the frame", 76.0, dimensions.getDouble(2), 1.0)
    }

    private inner class FixtureWebView(context: Context) : WebView(context) {
        var released = false
        private val pendingError = AtomicReference<Pair<WebResourceRequest, WebResourceResponse>?>(null)
        override fun destroy() { released = true; super.destroy() }
        override fun setWebViewClient(client: WebViewClient) {
            super.setWebViewClient(object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    navigations += "${request.url} main=${request.isForMainFrame} gesture=${request.hasGesture()} redirect=${request.isRedirect}"
                    return client.shouldOverrideUrlLoading(view, request)
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.isForMainFrame) responseGate?.await(10, TimeUnit.SECONDS)
                    val value = request.url.toString()
                    if (request.isForMainFrame) mainLoads.incrementAndGet()
                    val attempt = loads.incrementAndGet()
                    val title = when {
                        value.contains("/redirect") -> "跳转中"
                        value.contains("/9") -> "新窗口网页"
                        value.contains("/10") -> "重定向后的网页帖子"
                        value.contains("/t/") -> "网页帖子"
                        value.contains("/u/") -> "网页用户"
                        value.contains("/c/") -> "网页分类"
                        value.contains("other-fixture") -> "其他网站"
                        value.contains("pan.quark.cn") -> "夸克网页"
                        else -> "指南"
                    }
                    val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>$title</title>
                        <style>#vh {position:absolute;visibility:hidden;height:100vh} #frame {border:0;height:76px;max-height:min(1000px,200vh)}</style></head><body>
                        <div id="vh"></div><iframe id="frame" srcdoc="fixture"></iframe>
                        <h1>$title</h1>
                        <a id="topic" href="https://linux.do/t/topic/3001000/8">帖子</a>
                        <a id="profile" href="https://linux.do/u/stingysheep/activity/">用户</a>
                        <a id="category" href="https://linux.do/c/develop/4">分类</a>
                        <a id="blank" target="_blank" href="https://linux.do/t/topic/3001000/9">新窗口</a>
                        <a id="redirect" href="https://reader-fixture.test/redirect">跳转</a>
                        <a id="other" href="https://other-fixture.test/page">外站</a>
                        <a id="quark" href="https://pan.quark.cn/s/Abc123?pwd=1234">夸克分享</a>
                        <a id="quark-scheme" href="qklink://www.uc.cn/b20b84fd735a8dd3f7541129bacc4e9a?action=open_url&amp;url=https%3A%2F%2Fpan.quark.cn%2Fs%2FAbc123%3Fpwd%3D1234">夸克 App 分享</a>
                        ${if (value.contains("/redirect")) "<script>location.replace('https://linux.do/t/topic/3001000/10')</script>" else ""}
                        </body></html>"""
                    val response = WebResourceResponse("text/html", "UTF-8", if (failFirst && attempt == 1) 500 else 200,
                        if (failFirst && attempt == 1) "Server Error" else "OK", emptyMap(), html.byteInputStream())
                    // Intercepted responses skip the network stack's HTTP-error callback on this WebView.
                    if (response.statusCode >= 400) pendingError.set(request to response)
                    return response
                }
                override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) = client.onPageStarted(view, url, icon)
                override fun onPageCommitVisible(view: WebView, url: String) = client.onPageCommitVisible(view, url)
                override fun doUpdateVisitedHistory(view: WebView, url: String, reload: Boolean) = client.doUpdateVisitedHistory(view, url, reload)
                override fun onPageFinished(view: WebView, url: String) {
                    client.onPageFinished(view, url)
                    pendingError.getAndSet(null)?.let { (request, response) -> client.onReceivedHttpError(view, request, response) }
                    loadedTitle.set(view.title)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) = client.onReceivedError(view, request, error)
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) = client.onReceivedHttpError(view, request, response)
            })
        }
    }
}
