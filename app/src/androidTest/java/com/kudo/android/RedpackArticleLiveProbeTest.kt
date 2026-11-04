package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.DisplaySettings
import com.kudo.android.data.ThemeMode
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.MiniAppPreviewEnvironment
import com.kudo.android.ui.topic.RichContent
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Public metadata/CDN assets only; does not open or claim a real red packet. */
class RedpackArticleLiveProbeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun publicRedpackSvgAndMiniAppMetadataRenderInBothThemes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("redpackPreviewLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext as KudoApplication).container.repository
        val app = "https://linux.do/apps/01a19ba5-b9d2-766f-9fb9-b7eddc10e0c7?embed_mode=true&hb=fixture-packet"
        val target = "https://hb.unsnow.org/hb/fixture-packet"
        val image = "https://cdn3.ldstatic.com/original/4X/3/f/a/3fac9fd4e4e7f7b80ce5acb9e1fa0b1b0606d844.svg"
        val links = mutableListOf<String>()
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { KudoTheme(DisplaySettings(theme = theme)) {
            MiniAppPreviewEnvironment(repository, null) {
                RichContent("""<p><a href="$target"><img src="$image" alt="红包卡片" width="300" height="409"></a></p><iframe src="${app.replace("&", "&amp;")}"></iframe>""",
                    {}, { links += it }, galleryKey = "live-redpack")
            }
        } }
        compose.waitUntil(30000) { compose.onAllNodesWithText("红包页 · lafish的红包站").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30000) { compose.onAllNodesWithTag("article-image-live-redpack-0-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("加载失败，点击重试").assertDoesNotExist()
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { theme = mode }
            compose.onNodeWithText("红包页 · lafish的红包站").assertIsDisplayed()
            val pixels = compose.onNodeWithTag("article-image-live-redpack-0").captureToImage().toPixelMap()
            val color = pixels[pixels.width / 2, pixels.height / 5]
            assertTrue("Actual public SVG must render", color.red > .8f && color.green < .4f)
            capture("redpack-live-${mode.name.lowercase()}.png")
        }
        compose.onNodeWithTag("article-image-live-redpack-0").performClick()
        compose.onNodeWithTag("mini-app-card").performClick()
        compose.runOnIdle { assertEquals(listOf(target, app), links) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }
}
