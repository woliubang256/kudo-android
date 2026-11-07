package com.kudo.android

import android.content.Context
import android.graphics.Bitmap
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.web.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MiniAppMusicFloatingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val request = MiniAppTaskRequest.parse("https://linux.do/apps/$MUSIC_APP_ID", 101)!!
    private lateinit var web: WebView
    private var runtime by mutableStateOf(true)
    private var display by mutableStateOf(DisplaySettings())

    @After fun clear() { compose.runOnUiThread { MiniAppTasks.floating.clear() } }

    private fun show() {
        compose.setContent { KudoTheme(display) {
            MiniAppFloatingHost(101, true, ordinary = runtime) {
                Box(Modifier.fillMaxSize()) {
                    if (runtime) WebScreen(request.url, {}, {}, { FixtureView(it).also { web = it } },
                        accountId = 101, musicRequest = request)
                    else Text("论坛内容")
                }
            }
        } }
        compose.waitUntil(15000) { MiniAppMusic.current?.state?.title == "测试歌曲" }
    }

    private fun js(code: String): String {
        val latch = CountDownLatch(1); var value = ""
        compose.runOnUiThread { web.evaluateJavascript(code) { value = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS)); return value
    }

    @Test fun opaqueRuntimeReportsAndUsesOriginalButtonsWhileRejectingForgedSources() {
        show()
        val original = web
        compose.runOnIdle { assertTrue(MiniAppMusic.current!!.state.playing) }
        js("window.postMessage({type:'kudo-mini-music-v1',action:'state',title:'forged',playing:false,canControl:true},'*')")
        js("true")
        compose.runOnIdle { assertEquals("测试歌曲", MiniAppMusic.current!!.state.title) }
        compose.onNodeWithTag("mini-app-music-handle").performClick()
        compose.onNodeWithText("测试歌曲").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停", useUnmergedTree = true).performClick()
        compose.waitUntil { MiniAppMusic.current?.state?.playing == false }
        compose.onNodeWithContentDescription("继续播放", useUnmergedTree = true).performClick()
        compose.waitUntil { MiniAppMusic.current?.state?.playing == true }
        compose.onNodeWithContentDescription("下一首", useUnmergedTree = true).performClick()
        compose.waitUntil { MiniAppMusic.current?.state?.title == "下一首测试歌曲" }
        compose.runOnIdle {
            MiniAppTasks.minimize(MiniAppFloatingEntry(request, -100, null, null))
            assertSame(original, web)
        }
        compose.onNodeWithContentDescription("暂停并关闭音乐浮窗", useUnmergedTree = true).performClick()
        compose.waitUntil { MiniAppMusic.current?.hidden == true }
        compose.onNodeWithTag("mini-app-music-handle").assertDoesNotExist()
        compose.onNodeWithTag("mini-app-floating-handle").assertIsDisplayed()
        compose.runOnIdle { assertFalse(MiniAppMusic.current!!.state.playing); assertEquals(1, MiniAppTasks.floating.size) }
        compose.runOnIdle { runtime = false }
        compose.waitUntil { MiniAppMusic.current == null }
    }

    @Test fun previewsFollowAllMaterialsAndRetainSeparateCloseTarget() {
        compose.runOnUiThread { MiniAppTasks.minimize(MiniAppFloatingEntry(request, -100, null, null)) }
        compose.setContent { KudoTheme(display) { MiniAppFloatingHost(101, true) { Text("论坛内容") } } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (glass in GlassMode.entries) {
            compose.runOnIdle { display = display.copy(theme = theme, glass = glass, fontScale = 1.3f) }
            compose.onNodeWithTag("mini-app-floating-handle").performClick()
            compose.onNodeWithTag("mini-app-floating-previews").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭浮窗", useUnmergedTree = true).assertIsDisplayed()
            val capture = compose.onRoot().captureToImage()
            val handle = compose.onNodeWithTag("mini-app-floating-handle").fetchSemanticsNode().boundsInRoot
            val pixels = capture.toPixelMap()
            val a = pixels[(handle.left + 4).toInt(), handle.center.y.toInt()]
            val b = pixels[(handle.left - 4).toInt(), handle.center.y.toInt()]
            assertTrue("Handle must not draw a rectangular background outside its visible shape: $a vs $b",
                kotlin.math.abs(a.red-b.red) + kotlin.math.abs(a.green-b.green) + kotlin.math.abs(a.blue-b.blue) < .08f)
            val bitmap = capture.asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "mini-app-float-${theme.name.lowercase()}-${glass.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            compose.onNodeWithTag("modal-scrim").performClick()
            compose.waitUntil { compose.onAllNodesWithTag("liquid-action-sheet").fetchSemanticsNodes().isEmpty() }
        }
        compose.onNodeWithTag("mini-app-floating-handle").performClick()
        compose.onNodeWithContentDescription("关闭浮窗", useUnmergedTree = true).performClick()
        compose.waitUntil { MiniAppTasks.floating.isEmpty() }
    }

    private class FixtureView(context: Context) : WebView(context) {
        override fun setWebViewClient(client: WebViewClient) {
            super.setWebViewClient(object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val path = request.url.path.orEmpty()
                    val body = when {
                        path == "/mini-app-api/frame-host" -> """<iframe sandbox="allow-scripts" src="https://linux.do/mini-app-api/production/$MUSIC_APP_ID/runtime"></iframe>"""
                        path.endsWith("/runtime") -> """<div id="track-title">测试歌曲</div><div id="track-artist">测试歌手</div>
                          <button id="toggle" class="is-playing" onclick="this.classList.toggle('is-playing')">播放</button>
                          <button id="previous" onclick="document.getElementById('track-title').textContent='测试歌曲'">上一首</button>
                          <button id="next" onclick="document.getElementById('track-title').textContent='下一首测试歌曲'">下一首</button>"""
                        else -> """<main><div class="mini-app-page"><div class="mini-app-loader"><iframe class="mini-app-frame-host" src="https://linux.do/mini-app-api/frame-host"></iframe></div></div></main>"""
                    }
                    val html = "<!doctype html><html><head><meta name='viewport' content='width=device-width'></head><body>$body</body></html>"
                    // The official package mount replaces the document and clears early window listeners.
                    val response = if (path.endsWith("/runtime")) "<script>setTimeout(()=>{document.open();document.write(${org.json.JSONObject.quote(html)});document.close();},50);</script>" else html
                    return WebResourceResponse("text/html","UTF-8", response.byteInputStream())
                }
                override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) = client.onPageStarted(view,url,icon)
                override fun onPageFinished(view: WebView, url: String) = client.onPageFinished(view,url)
                override fun onPageCommitVisible(view: WebView, url: String) = client.onPageCommitVisible(view,url)
                override fun doUpdateVisitedHistory(view: WebView, url: String, reload: Boolean) = client.doUpdateVisitedHistory(view,url,reload)
            })
        }
    }
}
