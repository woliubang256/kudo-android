package com.kudo.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.web.WebScreen
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MiniAppWebUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val first = "01a1966a-f4a0-7b43-ad9c-c46b7f0e0281"
    private val second = "01a1966e-935a-79e0-8c76-9b0f7552c578"
    private lateinit var web: FixtureView
    private val requests = AtomicInteger()
    @Volatile private var failInfo = false
    private var display by mutableStateOf(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f))

    private fun show(start: String = "https://linux.do/apps/$first?room=one#play", external: (String) -> Unit = {}) {
        compose.setContent { KudoTheme(display) {
            var visible by remember { mutableStateOf(true) }
            if (visible) WebScreen(start, { visible = false }, external, { FixtureView(it).also { view -> web = view } })
            else Text("原生来源")
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("web-loading").fetchSemanticsNodes().isEmpty() }
        if (start.contains("/apps")) compose.onNodeWithTag("mini-app-capsule").assertIsDisplayed()
    }

    private fun js(script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        compose.runOnUiThread { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun click(id: String) {
        val position = JSONArray(js("(()=>{const b=document.getElementById('$id').getBoundingClientRect();return [b.x+b.width/2,b.y+b.height/2,innerWidth]})()"))
        val node = compose.onNodeWithTag("web-content")
        val scale = node.fetchSemanticsNode().boundsInRoot.width / position.getDouble(2).toFloat()
        node.performTouchInput { click(Offset(position.getDouble(0).toFloat() * scale, position.getDouble(1).toFloat() * scale)) }
    }

    private fun about(name: String) {
        compose.onNodeWithContentDescription("小程序更多").performClick()
        compose.onNodeWithText("关于", substring = false).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("mini-app-about").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).assertIsDisplayed()
    }

    @Test fun capsuleAboutUsesRealFieldsAndClosingPreservesTheSource() {
        show()
        compose.onNodeWithContentDescription("刷新网页").assertDoesNotExist()
        compose.onNodeWithContentDescription("小程序更多").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        about("2048")
        compose.onNodeWithContentDescription("应用图标").assertIsDisplayed()
        compose.onNodeWithText("2048 小游戏").assertIsDisplayed()
        compose.onNodeWithText("1.0.0").assertIsDisplayed()
        compose.onNodeWithText("Neo").assertIsDisplayed()
        assertEquals(1, requests.get())
        capture("mini-app-about-dark.png")
        compose.onNodeWithText("关闭", substring = false).performClick()
        compose.onNodeWithContentDescription("关闭网页").performClick()
        compose.onNodeWithText("原生来源").assertIsDisplayed()
        compose.runOnIdle { assertTrue(web.destroyed) }
    }

    @Test fun spaNavigationClearsOldInfoAndFailedInfoCanRetry() {
        show()
        about("2048")
        compose.onNodeWithText("关闭", substring = false).performClick()
        compose.waitForIdle()
        js("history.pushState({}, '', '/apps/$second');document.title='打砖块'")
        compose.waitUntil { compose.onAllNodesWithText("打砖块").fetchSemanticsNodes().isNotEmpty() }
        failInfo = true
        compose.onNodeWithContentDescription("小程序更多").performClick()
        compose.onNodeWithText("关于", substring = false).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("应用信息加载失败").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("2048 小游戏").assertDoesNotExist()
        failInfo = false
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("砖块游戏简介").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("2.0.0").assertIsDisplayed()
        compose.onNodeWithText("另一位发布人").assertIsDisplayed()
    }

    @Test fun actualFullscreenKeepsCapsuleAndBackExitsBeforeWebHistory() {
        show()
        click("fullscreen")
        compose.waitUntil(10000) { compose.onAllNodesWithTag("mini-app-fullscreen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mini-app-capsule").assertIsDisplayed()
        compose.onNodeWithContentDescription("小程序更多").performClick()
        compose.onNodeWithText("关于", substring = false).assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("mini-app-fullscreen").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("mini-app-fullscreen").assertDoesNotExist()
        compose.onNodeWithTag("mini-app-capsule").assertIsDisplayed()
        compose.onNodeWithText("原生来源").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭网页").performClick()
        compose.onNodeWithText("原生来源").assertIsDisplayed()
    }

    @Test fun clipboardFallbackRequiresConsentAndIsAbsentFromUntrustedFrames() {
        val manager = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var previous: ClipData? = null
        compose.runOnUiThread { previous = manager.primaryClip; manager.setPrimaryClip(ClipData.newPlainText("fixture", "小程序测试文字")) }
        try {
            show()
            assertEquals("true", js("typeof navigator.clipboard.readText === 'function'"))
            js("window.clipResult='pending';navigator.clipboard.readText().then(x=>window.clipResult=x,e=>window.clipResult=e.code||e.name)")
            compose.waitUntil(5000) { compose.onAllNodesWithText("允许小程序读取剪贴板文字？").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(5000) { js("window.clipResult").contains("USER_CANCELLED") }
            js("window.clipResult='pending';navigator.clipboard.readText().then(x=>window.clipResult=x,e=>window.clipResult=e.code||e.name)")
            compose.waitUntil(5000) { compose.onAllNodesWithText("仅本次允许").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("仅本次允许").performClick()
            compose.waitUntil(5000) { js("window.clipResult").contains("小程序测试文字") }
            js("window.clipResult='pending';navigator.clipboard.writeText('写入测试').then(()=>window.clipResult='written',e=>window.clipResult=e.code||e.name)")
            compose.waitUntil(5000) { compose.onAllNodesWithText("允许小程序写入剪贴板？").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("仅本次允许").performClick()
            compose.waitUntil(5000) { js("window.clipResult").contains("written") }
            compose.runOnUiThread { assertEquals("写入测试", manager.primaryClip!!.getItemAt(0).text.toString()) }
            compose.waitUntil(5000) { js("window.frameResults.length").toInt() >= 2 }
            assertEquals("true", js("window.frameResults.every(x => x.bridge === 'undefined')"))
            compose.runOnIdle {
                assertFalse(web.settings.allowFileAccess)
                assertFalse(web.settings.allowContentAccess)
                assertTrue(web.settings.mediaPlaybackRequiresUserGesture)
                assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(web))
            }
        } finally { compose.runOnUiThread { previous?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip() } }
    }

    @Test fun squareMenuAndBrowserUseCurrentUrlAndThemeChangesKeepTheSamePage() {
        var opened = ""
        show("https://linux.do/apps", { opened = it })
        val original = web
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (glass in GlassMode.entries) {
            compose.runOnIdle { display = DisplaySettings(theme = theme, glass = glass, fontScale = 1.3f) }
            compose.onNodeWithTag("mini-app-capsule").assertIsDisplayed()
            compose.onNodeWithTag("material-page-content").assertDoesNotExist()
            compose.runOnIdle { assertSame(original, web); assertFalse(original.destroyed) }
        }
        compose.onNodeWithText("应用广场").assertIsDisplayed()
        compose.onNodeWithContentDescription("小程序更多").performClick()
        compose.onNodeWithText("关于", substring = false).assertDoesNotExist()
        compose.onNodeWithText("在浏览器打开").performClick()
        compose.runOnIdle { assertEquals("https://linux.do/apps", opened) }
    }

    @Test fun enteringAppsThroughSiteHistoryInstallsClipboardCompatibility() {
        show("https://linux.do/t/topic/1")
        compose.onNodeWithTag("mini-app-capsule").assertDoesNotExist()
        js("history.pushState({}, '', '/apps/$first');document.title='2048'")
        compose.waitUntil { compose.onAllNodesWithTag("mini-app-capsule").fetchSemanticsNodes().isNotEmpty() }
        js("navigator.clipboard.readText().catch(()=>{})")
        compose.waitUntil(5000) { compose.onAllNodesWithText("允许小程序读取剪贴板文字？").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun backgroundCancelsClipboardAndPausesMediaWithoutReplacingThePage() {
        show()
        val original = web
        js("window.pauses=0;const media=document.createElement('audio');media.pause=()=>window.pauses++;document.body.append(media)")
        js("window.clipResult='pending';navigator.clipboard.readText().catch(e=>window.clipResult=e.code||e.name)")
        compose.waitUntil(5000) { compose.onAllNodesWithText("允许小程序读取剪贴板文字？").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5000) { js("window.clipResult").contains("USER_CANCELLED") }
        assertEquals("1", js("window.pauses"))
        compose.runOnIdle { assertSame(original, web); assertEquals(android.view.View.VISIBLE, web.visibility) }
        compose.onNodeWithTag("mini-app-capsule").assertIsDisplayed()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }

    private inner class FixtureView(context: Context) : WebView(context) {
        var destroyed = false
        override fun destroy() { destroyed = true; super.destroy() }
        override fun setWebViewClient(client: WebViewClient) {
            super.setWebViewClient(object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val path = request.url.path.orEmpty()
                    if (path.endsWith("/info")) {
                        requests.incrementAndGet()
                        val other = path.contains(second)
                        val data = if (failInfo) "{}" else """{"app":{"app_id":"${if (other) second else first}",
                            "name":"${if (other) "打砖块" else "2048"}","description":"${if (other) "砖块游戏简介" else "2048 小游戏"}",
                            "version":"${if (other) "2.0.0" else "1.0.0"}","author":{"name":"${if (other) "另一位发布人" else "Neo"}"}}}"""
                        return WebResourceResponse("application/json", "UTF-8", if (failInfo) 503 else 200,
                            if (failInfo) "Unavailable" else "OK", emptyMap(), data.byteInputStream())
                    }
                    val html = if (request.url.host != "linux.do") """<html><script>parent.postMessage({bridge:typeof KudoMiniApp},'*')</script></html>"""
                    else """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>测试应用</title>
                        <style>body{margin:0;background:#202020;color:white}button{display:block;height:48px;margin:16px}#game{background:#333;height:300px}</style></head>
                        <body><script>window.frameResults=[];addEventListener('message',e=>{if(e.data&&e.data.bridge)frameResults.push(e.data)})</script>
                        <div id="game"><button id="fullscreen" onclick="document.getElementById('game').requestFullscreen().catch(e=>window.fullError=e.name)">全屏游戏</button></div>
                        <iframe src="https://outside-fixture.test/frame"></iframe>
                        <iframe sandbox="allow-scripts" srcdoc="<script>parent.postMessage({bridge:typeof KudoMiniApp},'*')</script>"></iframe>
                        </body></html>"""
                    return WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = client.shouldOverrideUrlLoading(view, request)
                override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) = client.onPageStarted(view, url, icon)
                override fun onPageFinished(view: WebView, url: String) = client.onPageFinished(view, url)
                override fun onPageCommitVisible(view: WebView, url: String) = client.onPageCommitVisible(view, url)
                override fun doUpdateVisitedHistory(view: WebView, url: String, reload: Boolean) = client.doUpdateVisitedHistory(view, url, reload)
            })
        }
    }
}
