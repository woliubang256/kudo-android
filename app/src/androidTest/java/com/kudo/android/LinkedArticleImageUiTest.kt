package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import com.kudo.android.data.DisplaySettings
import com.kudo.android.data.ThemeMode
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.RichContent
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer

@OptIn(coil3.annotation.DelicateCoilApi::class)
class LinkedArticleImageUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var previous: ImageLoader
    private lateinit var loader: ImageLoader
    private val image = "https://redpack-image.test/card.svg"
    private val target = "https://hb.unsnow.org/hb/fixture-packet?source=post#detail"
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="728" height="992" viewBox="0 0 728 992"><defs><linearGradient id="red"><stop stop-color="#F52A30"/><stop offset="1" stop-color="#EF1B24"/></linearGradient></defs><rect width="728" height="992" rx="25" fill="url(#red)"/><circle cx="364" cy="341" r="73" fill="#FFE29A"/><text x="364" y="541" text-anchor="middle" font-size="48" fill="white">红包</text></svg>"""

    @Before fun setUp() {
        previous = SingletonImageLoader.get(InstrumentationRegistry.getInstrumentation().targetContext)
        loader = previous.newBuilder().components {
            add(Interceptor { chain ->
                if (chain.request.data.toString().startsWith("https://redpack-image.test/"))
                    chain.withRequest(chain.request.newBuilder().data(ByteBuffer.wrap(svg.toByteArray())).build()).proceed()
                else chain.proceed()
            })
            addFetcherFactories { previous.components.fetcherFactories }
            addDecoderFactories { previous.components.decoderFactories }
        }.build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun tearDown() { SingletonImageLoader.setUnsafe(previous); loader.shutdown() }

    @Test fun linkedSvgUsesTheCurrentLinkCallbackAndKeepsItsImageMenu() {
        val links = mutableListOf<String>()
        val oldLinks = mutableListOf<String>()
        val photos = mutableListOf<String>()
        var updated by mutableStateOf(false)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { KudoTheme(DisplaySettings(theme = theme)) {
            RichContent("""<p><a href="$target" rel="noopener nofollow ugc"><img src="$image" alt="红包卡片" width="300" height="409"></a></p>""",
                { photos += it }, if (updated) ({ links += it }) else ({ oldLinks += it }), galleryKey = "linked")
        } }
        awaitImage("article-image-linked-0")
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { theme = mode }
            val pixels = compose.onNodeWithTag("article-image-linked-0").captureToImage().toPixelMap()
            val color = pixels[pixels.width / 2, pixels.height / 5]
            assertTrue("SVG must render the red envelope", color.red > .8f && color.green < .4f)
            capture("linked-redpack-${mode.name.lowercase()}.png")
        }
        compose.onNodeWithTag("article-image-linked-0").performClick()
        compose.runOnIdle { assertEquals(listOf(target), oldLinks); assertTrue(photos.isEmpty()); updated = true }
        compose.onNodeWithTag("article-image-linked-0").performClick()
        compose.runOnIdle { assertEquals(listOf(target), links); assertTrue(photos.isEmpty()) }
        compose.onNodeWithTag("article-image-linked-0").performTouchInput { longClick() }
        compose.onNodeWithText("分享图片").assertIsDisplayed()
        compose.onNodeWithText("下载图片", substring = true).assertIsDisplayed()
    }

    @Test fun ordinaryAndLightboxImagesKeepTheirImageDestinations() {
        val photos = mutableListOf<String>()
        val links = mutableListOf<String>()
        compose.setContent { KudoTheme(DisplaySettings()) { Column {
            RichContent("""<p><img src="$image" width="60" height="80"></p><p><a class="lightbox" href="https://redpack-image.test/original.svg"><img src="$image" width="60" height="80"></a></p>""",
                { photos += it }, { links += it }, galleryKey = "ordinary")
        } } }
        awaitImage("article-image-ordinary-0"); awaitImage("article-image-ordinary-1")
        compose.onNodeWithTag("article-image-ordinary-0").performClick()
        compose.onNodeWithTag("article-image-ordinary-1").performClick()
        compose.runOnIdle { assertEquals(listOf(image, "https://redpack-image.test/original.svg"), photos); assertTrue(links.isEmpty()) }
    }

    @Test fun sourceOnlyPictureShowsTheCardAndKeepsItsLinkAndImageMenu() {
        val target = "https://hb.lafi.sh/packet?source=post#detail"
        val links = mutableListOf<String>()
        val photos = mutableListOf<String>()
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { KudoTheme(DisplaySettings(theme = theme)) {
            RichContent("""<p>通关越快奖励越多</p><a href="$target"><picture><source type="image/svg+xml" srcset="$image 1x"><img width="300" height="409" alt="红包卡片"></picture></a><p>游玩小程序</p>""",
                { photos += it }, { links += it }, galleryKey = "picture")
        } }
        awaitImage("article-image-picture-0")
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { theme = mode }
            compose.onNodeWithText("通关越快奖励越多").assertIsDisplayed()
            compose.onNodeWithText("游玩小程序").assertIsDisplayed()
            val card = compose.onNodeWithTag("article-image-picture-0")
            val pixels = card.captureToImage().toPixelMap()
            val color = pixels[pixels.width / 2, pixels.height / 5]
            assertTrue("source-only SVG must render", color.red > .8f && color.green < .4f)
            val bounds = card.fetchSemanticsNode().boundsInRoot
            assertEquals(728f / 992f, bounds.width / bounds.height, .01f)
            capture("picture-redpack-${mode.name.lowercase()}.png")
        }
        compose.onNodeWithTag("article-image-picture-0").performClick()
        compose.runOnIdle { assertEquals(listOf(target), links); assertTrue(photos.isEmpty()) }
        compose.onNodeWithTag("article-image-picture-0").performTouchInput { longClick() }
        compose.onNodeWithText("分享图片").assertIsDisplayed()
        compose.onNodeWithText("下载图片", substring = true).assertIsDisplayed()
    }

    @Test fun spoilerImageRevealsBeforeOpeningItsLink() {
        val links = mutableListOf<String>()
        val photos = mutableListOf<String>()
        compose.setContent { KudoTheme(DisplaySettings()) {
            RichContent("""<div class="spoiler"><a href="$target"><img src="$image" width="100" height="136"></a></div>""",
                { photos += it }, { links += it })
        } }
        compose.onNodeWithTag("article-spoiler-root-0").performTouchInput { click() }
        compose.runOnIdle { assertTrue(links.isEmpty()); assertTrue(photos.isEmpty()) }
        compose.onNodeWithTag("spoiler-image-root-0-0", useUnmergedTree = true).assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("spoiler-image-root-0-0-loading", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("spoiler-image-root-0-0", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(target), links); assertTrue(photos.isEmpty()) }
    }

    private fun awaitImage(tag: String) {
        compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("$tag-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("加载失败，点击重试").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
