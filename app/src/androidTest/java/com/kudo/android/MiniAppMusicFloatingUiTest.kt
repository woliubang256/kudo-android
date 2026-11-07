package com.kudo.android

import android.content.Context
import android.graphics.Bitmap
import android.app.Notification
import android.app.NotificationManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
import java.util.concurrent.atomic.AtomicInteger

class MiniAppMusicFloatingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val request = MiniAppTaskRequest.parse("https://linux.do/apps/$MUSIC_APP_ID", 101)!!
    private lateinit var web: WebView
    private var runtime by mutableStateOf(true)
    private var display by mutableStateOf(DisplaySettings())
    private var owner by mutableStateOf(101)
    private var page by mutableStateOf(0)
    private var red by mutableStateOf(false)
    private var ink = Color.Unspecified

    @After fun clear() { compose.runOnUiThread { MiniAppTasks.floating.clear() } }

    private fun show() {
        compose.setContent { KudoTheme(display) {
            val color = MaterialTheme.colorScheme.onSurface
            SideEffect { ink = color }
            MiniAppFloatingHost(101, true, pageKey = runtime) {
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

    @Test fun systemMediaControlsFollowOriginalPlayerAndRetireOnClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        try {
            show()
            compose.onNodeWithTag("mini-app-music-handle").assertDoesNotExist()
            val manager = context.getSystemService(MediaSessionManager::class.java)
            fun sessions() = manager.getActiveSessions(null).filter { it.packageName == context.packageName }
            compose.waitUntil(6000) { sessions().isNotEmpty() }
            val media = sessions().single()
            compose.waitUntil { media.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == "测试歌曲" }
            assertEquals("测试歌手", media.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST))
            assertEquals(PlaybackState.STATE_PLAYING, media.playbackState?.state)
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
                .single { it.notification.category == Notification.CATEGORY_TRANSPORT }.notification
            assertEquals(media.sessionToken, notification.extras.getParcelable<android.media.session.MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION))
            media.transportControls.pause()
            compose.waitUntil { MiniAppMusic.current?.state?.playing == false && media.playbackState?.state == PlaybackState.STATE_PAUSED }
            assertTrue("System pause must reach the original webpage", (web as FixtureView).pauses.get() > 0)
            assertEquals(1, sessions().size)
            media.transportControls.play()
            compose.waitUntil { MiniAppMusic.current?.state?.playing == true && media.playbackState?.state == PlaybackState.STATE_PLAYING }
            media.transportControls.skipToNext()
            compose.waitUntil { media.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == "下一首测试歌曲" }
            media.transportControls.skipToPrevious()
            compose.waitUntil { media.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == "测试歌曲" }
            val entry = MiniAppFloatingEntry(request, -100, null, null)
            compose.runOnIdle { MiniAppTasks.minimize(entry); MiniAppTasks.close(compose.activity, entry) }
            compose.waitUntil { sessions().isEmpty() }
            assertNull(MiniAppMusic.current)
            val pauses = (web as FixtureView).pauses.get()
            media.transportControls.play()
            compose.runOnIdle { assertNull(MiniAppMusic.current) }
            assertEquals(pauses, (web as FixtureView).pauses.get())
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    @Test fun musicHandleRequiresItsOwnMinimizedEntryAndRestoreKeepsPlaying() {
        show()
        compose.onNodeWithTag("mini-app-music-handle").assertDoesNotExist()
        val other = MiniAppTaskRequest.parse("https://linux.do/apps/01a1966a-f4a0-7b43-ad9c-c46b7f0e0281",101)!!
        val otherAccount = MiniAppTaskRequest.parse(request.url,202)!!
        compose.runOnIdle {
            MiniAppTasks.minimize(MiniAppFloatingEntry(other, -101, null, null))
            MiniAppTasks.minimize(MiniAppFloatingEntry(otherAccount, -102, null, null))
        }
        compose.onNodeWithTag("mini-app-music-handle").assertDoesNotExist()
        compose.runOnIdle { MiniAppTasks.minimize(MiniAppFloatingEntry(request, -100, null, null)) }
        compose.onNodeWithTag("mini-app-music-handle").assertIsDisplayed()
        compose.onNodeWithTag("mini-app-music-handle").performClick()
        compose.runOnIdle { MiniAppTasks.resumed(request.key) }
        compose.onNodeWithTag("mini-app-music-control").assertDoesNotExist()
        compose.onNodeWithTag("mini-app-music-handle").assertDoesNotExist()
        compose.onNodeWithTag("mini-app-floating-handle").assertIsDisplayed()
        compose.runOnIdle { assertTrue(MiniAppMusic.current!!.state.playing); assertEquals(0, (web as FixtureView).pauses.get()) }
    }

    @Test fun closingMusicMiniAppStopsOriginalPlayerWhileOtherAppClosureDoesNot() {
        show()
        val other = MiniAppTaskRequest.parse("https://linux.do/apps/01a1966a-f4a0-7b43-ad9c-c46b7f0e0281",101)!!
        val game = MiniAppFloatingEntry(other, -101, null, null)
        compose.runOnIdle {
            MiniAppTasks.minimize(MiniAppFloatingEntry(request, -100, null, null))
            MiniAppTasks.minimize(game)
            MiniAppTasks.close(compose.activity, game)
            assertTrue(MiniAppMusic.current!!.state.playing)
            assertEquals(0, (web as FixtureView).pauses.get())
        }
        compose.onNodeWithTag("mini-app-floating-handle").performClick()
        compose.runOnIdle { MiniAppMusic.current!!.command("next") }
        compose.onNodeWithContentDescription("关闭浮窗", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { (web as FixtureView).pauses.get() > 0 }
        compose.runOnIdle { assertNull(MiniAppMusic.current); assertTrue(MiniAppTasks.floating.isEmpty()) }
        compose.onNodeWithTag("mini-app-music-handle").assertDoesNotExist()
        compose.onNodeWithTag("mini-app-floating-handle").assertDoesNotExist()
    }

    @Test fun opaqueRuntimeReportsAndUsesOriginalButtonsWhileRejectingForgedSources() {
        show()
        val original = web
        compose.runOnIdle { assertTrue(MiniAppMusic.current!!.state.playing) }
        compose.onNodeWithTag("mini-app-music-handle").assertDoesNotExist()
        compose.runOnIdle { MiniAppTasks.minimize(MiniAppFloatingEntry(request, -100, null, null)) }
        js("window.postMessage({type:'kudo-mini-music-v1',action:'state',title:'forged',playing:false,canControl:true},'*')")
        js("true")
        compose.runOnIdle { assertEquals("测试歌曲", MiniAppMusic.current!!.state.title) }
        compose.onNodeWithTag("mini-app-music-handle").performClick()
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithText("测试歌曲").assertIsDisplayed()
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (glass in GlassMode.entries) {
            compose.runOnIdle { display = display.copy(theme = theme, glass = glass, fontScale = 1.5f) }
            assertMaterial("mini-app-music-control", glass)
            compose.onNodeWithTag("mini-app-music-control").assertHeightIsEqualTo(56.dp)
            val capture = screenshot("music-${theme.name.lowercase()}-${glass.name.lowercase()}")
            if (theme == ThemeMode.DARK) {
                val bounds = screenBounds("mini-app-music-control")
                val background = capture.getPixel(bounds.center.x.toInt(), bounds.top.toInt() + 10)
                val contrast = androidx.core.graphics.ColorUtils.calculateContrast(ink.toArgb(), background)
                assertTrue("Music title must stay readable over a light website: $contrast", contrast >= 4.5)
            }
        }
        back()
        compose.onNodeWithTag("mini-app-music-control").assertDoesNotExist()
        compose.runOnIdle { assertTrue(MiniAppMusic.current!!.state.playing) }
        compose.onNodeWithTag("mini-app-music-handle").performClick()
        compose.onNodeWithContentDescription("暂停", useUnmergedTree = true).performClick()
        compose.waitUntil { MiniAppMusic.current?.state?.playing == false }
        compose.onNodeWithContentDescription("继续播放", useUnmergedTree = true).performClick()
        compose.waitUntil { MiniAppMusic.current?.state?.playing == true }
        compose.runOnIdle { MiniAppMusic.current!!.command("next") }
        compose.waitUntil { MiniAppMusic.current?.state?.title == "下一首测试歌曲" }
        compose.runOnIdle {
            MiniAppTasks.minimize(MiniAppFloatingEntry(request, -100, null, null))
            assertSame(original, web)
        }
        compose.onNodeWithTag("mini-app-floating-handle").performClick()
        compose.onNodeWithTag("mini-app-music-control").assertDoesNotExist()
        compose.onNodeWithTag("mini-app-floating-previews").assertIsDisplayed()
        back()
        compose.onNodeWithTag("mini-app-music-handle").performClick()
        compose.onNodeWithTag("mini-app-floating-previews").assertDoesNotExist()
        compose.onNodeWithContentDescription("暂停并关闭音乐浮窗", useUnmergedTree = true).performClick()
        compose.waitUntil { MiniAppMusic.current?.hidden == true }
        compose.onNodeWithTag("mini-app-music-handle").assertDoesNotExist()
        compose.onNodeWithTag("mini-app-floating-handle").assertIsDisplayed()
        compose.runOnIdle { assertFalse(MiniAppMusic.current!!.state.playing); assertEquals(1, MiniAppTasks.floating.size) }
        assertTrue("Closing the music bar must pause the original player", (web as FixtureView).pauses.get() > 0)
        compose.runOnIdle { runtime = false }
        compose.waitUntil { MiniAppMusic.current == null }
    }

    @Test fun previewsFollowAllMaterialsAndRetainSeparateCloseTarget() {
        val other = MiniAppTaskRequest.parse("https://linux.do/apps/01a1966a-f4a0-7b43-ad9c-c46b7f0e0281",101)!!
        compose.runOnUiThread {
            MiniAppTasks.minimize(MiniAppFloatingEntry(other, -101, null, null))
            MiniAppTasks.minimize(MiniAppFloatingEntry(request, -100, null, null))
        }
        previews()
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (glass in GlassMode.entries) {
            compose.runOnIdle { display = display.copy(theme = theme, glass = glass, fontScale = 1.3f) }
            assertMaterial("mini-app-floating-handle-surface", glass)
            compose.onNodeWithTag("mini-app-floating-handle").performClick()
            compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
            compose.onNodeWithTag("mini-app-floating-previews").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("关闭浮窗", useUnmergedTree = true).assertCountEquals(2)
            assertMaterial("mini-app-preview-$MUSIC_APP_ID", glass)
            val a = compose.onNodeWithTag("mini-app-preview-$MUSIC_APP_ID").fetchSemanticsNode().boundsInRoot
            val b = compose.onNodeWithTag("mini-app-preview-${other.key.appId}").fetchSemanticsNode().boundsInRoot
            assertEquals(a.top, b.top, 1f)
            assertTrue(a.right <= b.left || b.right <= a.left)
            if (glass != GlassMode.TRANSLUCENT) awaitBlueSample("mini-app-preview-$MUSIC_APP_ID")
            screenshot("previews-${theme.name.lowercase()}-${glass.name.lowercase()}")
            back()
            compose.waitUntil { compose.onAllNodesWithTag("mini-app-floating-previews").fetchSemanticsNodes().isEmpty() }
        }
        compose.onNodeWithTag("mini-app-floating-handle").performClick()
        val snapshot = Bitmap.createBitmap(50, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        compose.runOnIdle { MiniAppTasks.minimize(MiniAppFloatingEntry(request, -100, null, snapshot)) }
        val filled = screenBounds("mini-app-preview-$MUSIC_APP_ID")
        compose.waitUntil(5000) {
            val capture = automation().takeScreenshot()
            listOf(.1f, .5f, .9f).all { capture.getPixel((filled.left + filled.width * it).toInt(), filled.center.y.toInt()) == android.graphics.Color.CYAN }
        }
        val capture = screenshot("snapshot-fill")
        for (fraction in listOf(.1f, .5f, .9f)) {
            assertEquals(android.graphics.Color.CYAN, capture.getPixel((filled.left + filled.width * fraction).toInt(), filled.center.y.toInt()))
        }
        compose.onAllNodesWithContentDescription("关闭浮窗", useUnmergedTree = true)[0].performClick()
        compose.runOnIdle { assertEquals(1, MiniAppTasks.floating.size) }
        compose.onNodeWithContentDescription("关闭浮窗", useUnmergedTree = true).performClick()
        compose.waitUntil { MiniAppTasks.floating.isEmpty() }
    }

    @Test fun backgroundChangesReachGlassWithoutSamplingTheFloatingWindow() {
        compose.runOnUiThread { MiniAppTasks.minimize(MiniAppFloatingEntry(request, -100, null, null)) }
        previews()
        compose.onNodeWithTag("mini-app-floating-handle").performClick()
        assertMaterial("mini-app-preview-$MUSIC_APP_ID", GlassMode.LIQUID)
        val bounds = screenBounds("mini-app-preview-$MUSIC_APP_ID")
        val point = bounds.center
        awaitBlueSample("mini-app-preview-$MUSIC_APP_ID")
        val before = screenshot("source-blue").getPixel(point.x.toInt(), point.y.toInt())
        compose.runOnIdle { red = true }
        try { compose.waitUntil(8000) {
            val now = automation().takeScreenshot().getPixel(point.x.toInt(), point.y.toInt())
            android.graphics.Color.red(now) - android.graphics.Color.red(before) > 30 &&
                android.graphics.Color.blue(before) - android.graphics.Color.blue(now) > 30
        } } finally { screenshot("source-final") }
        val after = screenshot("source-red").getPixel(point.x.toInt(), point.y.toInt())
        // Repeated copies of an unchanged background must not accumulate the popup's tint.
        val start = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(3000) { android.os.SystemClock.uptimeMillis() - start > 900 }
        val stable = screenshot("source-red-stable").getPixel(point.x.toInt(), point.y.toInt())
        assertTrue(kotlin.math.abs(android.graphics.Color.red(stable) - android.graphics.Color.red(after)) < 5)
        assertTrue(kotlin.math.abs(android.graphics.Color.blue(stable) - android.graphics.Color.blue(after)) < 5)
        // An outside physical touch dismisses only the preview.
        val time = android.os.SystemClock.uptimeMillis()
        automation().injectInputEvent(android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, 40f, 200f, 0), true)
        automation().injectInputEvent(android.view.MotionEvent.obtain(time, time + 10, android.view.MotionEvent.ACTION_UP, 40f, 200f, 0), true)
        compose.waitUntil { compose.onAllNodesWithTag("mini-app-floating-previews").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("mini-app-floating-previews").assertDoesNotExist()
        compose.onNodeWithTag("mini-app-floating-handle").assertIsDisplayed()
    }

    @Test fun horizontalOverflowNavigationAccountAndDraggingKeepPanelsBounded() {
        val requests = (1..4).map { MiniAppTaskRequest.parse("https://linux.do/apps/01a1966a-f4a0-7b43-ad9c-c46b7f0e028$it",101)!! }
        compose.runOnUiThread { requests.forEachIndexed { index, item -> MiniAppTasks.minimize(MiniAppFloatingEntry(item, -100-index, null, null)) } }
        previews()
        compose.onNodeWithTag("mini-app-floating-handle").performTouchInput { swipeUp() }
        compose.onNodeWithTag("mini-app-floating-handle").performClick()
        val bounds = screenBounds("mini-app-floating-previews")
        assertTrue(bounds.left >= 0 && bounds.right <= automation().takeScreenshot().width)
        compose.onNodeWithTag("mini-app-floating-previews").performTouchInput { swipeRight() }
        compose.onNodeWithTag("mini-app-preview-${requests.first().key.appId}").assertIsDisplayed()
        compose.runOnIdle { page++ }
        compose.onNodeWithTag("mini-app-floating-previews").assertDoesNotExist()
        compose.onNodeWithTag("mini-app-floating-handle").performClick()
        compose.runOnIdle { owner = 202 }
        compose.onNodeWithTag("mini-app-floating-previews").assertDoesNotExist()
        compose.onNodeWithTag("mini-app-floating-handle").assertDoesNotExist()
        compose.runOnIdle { owner = 101 }
        compose.onNodeWithTag("mini-app-floating-handle").assertIsDisplayed()
    }

    private fun previews() {
        compose.setContent { KudoTheme(display) { MiniAppFloatingHost(owner, true, pageKey = page) {
            Box(Modifier.fillMaxSize().background(if (red) Color(0xFFDE2626) else Color(0xFF205ADF))) { Text("论坛内容") }
        } } }
    }

    private fun assertMaterial(tag: String, glass: GlassMode) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        node.assert(SemanticsMatcher.expectValue(FloatingMaterialKey, glass))
        if (glass != GlassMode.TRANSLUCENT) compose.waitUntil(8000) { node.fetchSemanticsNode().config[FloatingSampleKey] }
        else node.assert(SemanticsMatcher.expectValue(FloatingSampleKey, false))
    }

    private fun screenBounds(tag: String): Rect {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        return Rect(node.layoutInfo.coordinates.positionOnScreen(), node.boundsInRoot.size)
    }

    private fun awaitBlueSample(tag: String) {
        val point = screenBounds(tag).center
        compose.waitUntil(5000) {
            val color = automation().takeScreenshot().getPixel(point.x.toInt(), point.y.toInt())
            android.graphics.Color.blue(color) - android.graphics.Color.red(color) > 30
        }
    }

    private fun automation() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun back() {
        automation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil { compose.onAllNodesWithTag("mini-app-floating-previews").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithTag("mini-app-music-control").fetchSemanticsNodes().isEmpty() }
    }
    private fun screenshot(name: String): Bitmap {
        // Compose idle does not wait for the physical Popup window's OEM entrance animation.
        automation().waitForIdle(400, 3000)
        return automation().takeScreenshot().also { bitmap ->
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "mini-app-anchor-$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private class FixtureView(context: Context) : WebView(context) {
        val pauses = AtomicInteger()
        override fun setWebViewClient(client: WebViewClient) {
            super.setWebViewClient(object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val path = request.url.path.orEmpty()
                    if (path == "/fixture/pause") {
                        pauses.incrementAndGet()
                        return WebResourceResponse("image/svg+xml", "UTF-8", "<svg xmlns='http://www.w3.org/2000/svg'/>".byteInputStream())
                    }
                    val body = when {
                        path == "/mini-app-api/frame-host" -> """<iframe sandbox="allow-scripts" src="https://linux.do/mini-app-api/production/$MUSIC_APP_ID/runtime"></iframe>"""
                        path.endsWith("/runtime") -> """<div id="track-title">测试歌曲</div><div id="track-artist">测试歌手</div>
                          <button id="toggle" class="is-playing" onclick="this.classList.toggle('is-playing');if(!this.classList.contains('is-playing'))new Image().src='https://linux.do/fixture/pause?n='+Date.now()">播放</button>
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
