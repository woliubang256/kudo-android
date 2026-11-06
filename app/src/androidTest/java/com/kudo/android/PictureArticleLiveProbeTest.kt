package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import com.kudo.android.data.DisplaySettings
import com.kudo.android.data.ThemeMode
import com.kudo.android.data.miniAppPreview
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.ArticleBlock
import com.kudo.android.ui.topic.RichContent
import com.kudo.android.ui.topic.MiniAppPreviewEnvironment
import com.kudo.android.ui.topic.parseArticle
import com.kudo.android.ui.web.MiniAppLocation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Reads the reported post and its image; never opens the real packet or sends reading timings. */
class PictureArticleLiveProbeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun reportedPictureRendersAndKeepsItsOriginalLink() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pictureArticleLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = (context.applicationContext as KudoApplication).container
        val raw = runBlocking { withTimeout(45000) { app.client.get("/t/3134492.json") } }
        val posts = raw.getJSONObject("post_stream").getJSONArray("posts")
        val post = (0 until posts.length()).map(posts::getJSONObject).first { it.getInt("post_number") == 1 }
        val html = post.getString("cooked")
        File(context.getExternalFilesDir(null), "picture-topic-3134492.html").writeText(html, Charsets.UTF_8)
        val document = Jsoup.parseBodyFragment(html)
        val pictures = document.select("picture")
        val blocks = parseArticle(html)
        val images = blocks.filterIsInstance<ArticleBlock.Image>()
        val miniApp = blocks.filterIsInstance<ArticleBlock.Embed>().single { MiniAppLocation.parse(it.url)?.id != null }
        val appInfo = runBlocking { withTimeout(45000) { app.repository.miniAppPreview(MiniAppLocation.parse(miniApp.url)!!.id!!) } }
        val report = JSONObject().put("topicId", 3134492).put("pictures", pictures.size)
            .put("sourceOnlyImages", pictures.select("img:not([src])").size).put("parsedImages", images.size)
            .put("miniAppName", appInfo.name).put("miniAppId", appInfo.id)
        File(context.getExternalFilesDir(null), "picture-topic-contract.json").writeText(report.toString(2), Charsets.UTF_8)
        assertTrue("用户话题应包含 picture", pictures.isNotEmpty())
        val picture = pictures.first()!!
        val card = images.firstOrNull { it.alt == picture.selectFirst("img")?.attr("alt") }
        assertNotNull("picture 红包图片不能被丢弃", card)
        val loaded = runBlocking { withTimeout(45000) {
            SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(card!!.url).size(675, 900).build())
        } }
        val error = (loaded as? ErrorResult)?.throwable
        report.put("imageLoadError", error?.stackTraceToString() ?: JSONObject.NULL)
        File(context.getExternalFilesDir(null), "picture-topic-contract.json").writeText(report.toString(2), Charsets.UTF_8)
        assertNull("真实 SVG 必须由生产图片加载器成功加载：$error", error)
        val links = mutableListOf<String>()
        val tag = "article-image-picture-live-${images.indexOf(card!!)}"
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { KudoTheme(DisplaySettings(theme = theme)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MiniAppPreviewEnvironment(app.repository, null) {
                    RichContent(html, {}, { links += it }, galleryKey = "picture-live")
                }
            }
        } }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { theme = mode }
            compose.onNodeWithTag(tag).assertIsDisplayed()
            compose.waitUntil(30000) {
                compose.onAllNodesWithTag("$tag-loading").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("加载失败，点击重试").assertDoesNotExist()
            compose.waitUntil(10000) { compose.onAllNodesWithText(appInfo.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mini-app-card").assertIsDisplayed()
            val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 3]
            assertTrue("真实 SVG 的深色卡片必须绘制出来", center.red < .4f && center.green < .4f && center.blue < .4f)
            compose.waitForIdle()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            try { File(context.getExternalFilesDir(null), "picture-topic-$mode.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { bitmap.recycle() }
        }
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag("mini-app-card").performClick()
        compose.runOnIdle { assertEquals(listOf(card.linkUrl, miniApp.url), links) }
    }
}
