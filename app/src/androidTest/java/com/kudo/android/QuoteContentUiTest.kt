package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.kudo.android.data.DisplaySettings
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.io.File

@OptIn(coil3.annotation.DelicateCoilApi::class)
class QuoteContentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var previous: ImageLoader
    private lateinit var loader: ImageLoader
    private val quote = """<aside class="quote no-group" data-username="babiwei" data-display-name="芭比威" data-topic="3002436" data-post="1"><div class="title"><div class="quote-controls"></div><img class="avatar" src="https://quote.test/avatar.png" width="24" height="24"> 芭比威:</div><blockquote><p>电子称重勺</p></blockquote></aside>"""

    @Before fun images() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        previous = SingletonImageLoader.get(instrumentation.targetContext)
        loader = ImageLoader.Builder(instrumentation.targetContext).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                    if (!data.toString().startsWith("https://quote.test/")) return null
                    return object : Fetcher {
                        override suspend fun fetch() = SourceFetchResult(ImageSource(Buffer().write(
                            instrumentation.context.assets.open("avatars/static.png").use { it.readBytes() }), options.fileSystem), "image/png", DataSource.MEMORY)
                    }
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
    }
    @After fun restore() { SingletonImageLoader.setUnsafe(previous); loader.shutdown() }

    @Test fun oneboxShowsCachedPreviewAndClickKeepsTheOriginalDestination() {
        var opened = ""
        var photo = ""
        compose.setContent { KudoTheme(DisplaySettings(theme = com.kudo.android.data.ThemeMode.DARK, fontScale = 1.3f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("quote-fixture")) {
                RichContent("""<aside class="onebox githubrepo" data-onebox-src="https://github.com/Kuddev/pebrel"><header class="source"><a href="https://github.com/Kuddev/pebrel">github.com</a></header><article class="onebox-body"><img class="thumbnail" src="https://quote.test/preview.png"><h3><a href="https://github.com/Kuddev/pebrel">GitHub - Kuddev/pebrel: AI-native terminal</a></h3><p>AI-native, GPU-accelerated terminal emulator for Windows.</p></article></aside>""", { photo = it }, { opened = it })
            }
        } }
        compose.onNodeWithText("GitHub - Kuddev/pebrel: AI-native terminal", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("AI-native, GPU-accelerated terminal emulator for Windows.", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("onebox-thumbnail", useUnmergedTree = true).assertIsDisplayed()
        capture("onebox-dark-large.png")
        compose.onNodeWithTag("onebox-preview").performClick()
        compose.runOnIdle { assertEquals("https://github.com/Kuddev/pebrel", opened); assertEquals("", photo) }
    }

    @Test fun updateLogStartsCollapsedAndKeepsItsChoiceAfterRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KudoTheme(DisplaySettings()) {
            Column(Modifier.fillMaxSize().padding(16.dp).testTag("quote-fixture")) {
                RichContent("""<details><summary>v1.7.0更新日志</summary><p>加入catppuccin全家桶配色！</p><img src="https://quote.test/theme.png" width="565" height="500"></details>""", {}, {})
            }
        } }
        compose.onNodeWithText("v1.7.0更新日志").assertIsDisplayed()
        compose.onNodeWithText("加入catppuccin全家桶配色！").assertDoesNotExist()
        capture("details-collapsed.png")
        compose.onNodeWithContentDescription("展开折叠内容").performClick()
        compose.onNodeWithText("加入catppuccin全家桶配色！").assertIsDisplayed()
        compose.onNodeWithTag("article-image-article-0").assertIsDisplayed()
        capture("details-expanded.png")
        restoration.emulateSavedInstanceStateRestore()
        capture("details-restored.png")
        compose.onNodeWithText("加入catppuccin全家桶配色！").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起折叠内容").performClick()
        compose.onNodeWithTag("article-image-article-0").assertDoesNotExist()
    }

    @Test fun galleryReturnOpensNestedDetailsAndQuoteWithoutChangingPhotoIndices() {
        val gallery = ImageGalleryState()
        lateinit var scope: CoroutineScope
        val html = """<details open><summary>外层更新</summary><aside class="quote" data-username="author"><div class="title">引用作者:</div><blockquote><details><summary>内层更新</summary><img src="https://quote.test/repeat.png" width="100" height="60"></details></blockquote></aside></details><img src="https://quote.test/outside.png" width="100" height="60">"""
        compose.setContent { KudoTheme(DisplaySettings()) {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalImageGallery provides gallery) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { RichContent(html, {}, {}) }
            }
        } }
        compose.onNodeWithText("内层更新").assertIsDisplayed()
        compose.onNodeWithTag("article-image-article-0").assertDoesNotExist()
        compose.onNodeWithContentDescription("收起引用").performClick()
        compose.onNodeWithContentDescription("收起折叠内容").performClick()
        compose.onNodeWithTag("article-image-article-1").performClick()
        compose.runOnIdle {
            assertEquals(1, gallery.session!!.index)
            assertEquals(2, gallery.session!!.images.size)
            scope.launch { gallery.dismiss(0) }
        }
        compose.waitUntil(5000) { gallery.session == null }
        compose.onNodeWithTag("article-image-article-0").assertIsDisplayed()
        compose.onNodeWithText("内层更新").assertIsDisplayed()
        compose.onNodeWithTag("article-image-article-0").performClick()
        compose.runOnIdle { assertEquals(0, gallery.session!!.index) }
    }

    @Test fun quoteIsOneRegionWithSmallAvatarAndOriginalPostNavigation() {
        var link = ""
        var image = ""
        compose.setContent { KudoTheme(DisplaySettings()) {
            Column(Modifier.fillMaxSize().padding(16.dp).testTag("quote-fixture")) {
                RichContent(quote + "<p>挺有意思的产品啊，感觉做出来包装下可以找个 hci 相关的会议发了</p>", { image = it }, { link = it })
            }
        } }
        compose.onNodeWithTag("quote-avatar-root-0", useUnmergedTree = true).assertWidthIsEqualTo(20.dp).assertHeightIsEqualTo(20.dp)
        val region = compose.onNodeWithTag("article-quote-root-0").fetchSemanticsNode().boundsInRoot
        for (text in listOf("芭比威", "电子称重勺")) {
            val bounds = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(region.contains(bounds.center))
        }
        compose.onNodeWithText("“", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("article-quote-mark-root-0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("article-image-article-0").assertDoesNotExist()
        capture("quote-fixed.png")
        compose.onNodeWithContentDescription("跳转引用原帖").performClick()
        compose.runOnIdle { assertEquals("https://linux.do/t/topic/3002436/1", link); assertEquals("", image) }
        compose.onNodeWithText("电子称重勺").assertIsDisplayed()
    }

    @Test fun plainQuoteShowsDecorativeMark() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                RichContent("<blockquote><p>普通正文引用</p></blockquote>", {}, {})
            }
        } }
        compose.onNodeWithText("普通正文引用", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("“", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("article-quote-mark-root-0", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun shortQuoteHeightFollowsItsTextWithoutAnExtraLine() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            Column(Modifier.fillMaxSize().padding(16.dp).testTag("quote-fixture")) {
                RichContent("<blockquote><p>老家 潍坊</p></blockquote>", {}, {})
            }
        } }
        val region = compose.onNodeWithTag("article-quote-root-0").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag("article-text-root-0-0").fetchSemanticsNode().boundsInRoot
        capture("quote-short.png")
        assertEquals(text.height, region.height, 1f)
    }

    @Test fun shortQuoteAtLargeFontKeepsTheVectorInsideItsTextHeight() {
        compose.setContent { KudoTheme(DisplaySettings(theme = com.kudo.android.data.ThemeMode.DARK, fontScale = 1.3f)) {
            Column(Modifier.fillMaxSize().padding(16.dp).testTag("quote-fixture")) {
                RichContent("<blockquote><p>老家 潍坊</p></blockquote>", {}, {})
            }
        } }
        val region = compose.onNodeWithTag("article-quote-root-0").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag("article-text-root-0-0").fetchSemanticsNode().boundsInRoot
        val mark = compose.onNodeWithTag("article-quote-mark-root-0", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        capture("quote-short-dark-large.png")
        assertEquals(text.height, region.height, 1f)
        assertTrue(mark.left >= region.left && mark.right <= region.right)
        assertTrue(mark.top >= region.top && mark.bottom <= region.bottom)
        compose.onNodeWithText("“", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun plainQuoteKeepsExplicitLineBreaksAndParagraphSpacing() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            Column(Modifier.fillMaxSize().padding(16.dp).testTag("quote-fixture")) {
                RichContent("<blockquote><p>第一行<br>第二行</p><p>下一个段落</p></blockquote>", {}, {})
            }
        } }
        compose.onNodeWithText("第一行\n第二行", useUnmergedTree = true).assertIsDisplayed()
        val first = compose.onNodeWithTag("article-text-root-0-0").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("article-text-root-0-1").fetchSemanticsNode().boundsInRoot
        val region = compose.onNodeWithTag("article-quote-root-0").fetchSemanticsNode().boundsInRoot
        assertEquals(with(compose.density) { 12.dp.toPx() }, second.top - first.bottom, 1f)
        assertEquals(second.bottom - first.top, region.height, 1f)
        compose.onAllNodesWithTag("article-quote-mark-root-0", useUnmergedTree = true).assertCountEquals(1)
        capture("quote-paragraphs.png")
    }

    @Test fun collapsedQuoteStaysCollapsedAfterSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KudoTheme(DisplaySettings()) { RichContent(quote, {}, {}) } }
        compose.onNodeWithContentDescription("收起引用").performClick()
        compose.onNodeWithText("电子称重勺").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("电子称重勺").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开引用").performClick()
        compose.onNodeWithText("电子称重勺").assertIsDisplayed()
    }

    @Test fun collapsedPhotosKeepTheirAlbumIndicesAndExpandWhenReturningFromGallery() {
        val gallery = ImageGalleryState()
        lateinit var scope: CoroutineScope
        val body = quote.replace("<p>电子称重勺</p>", """<p>电子称重勺</p><img src="https://quote.test/repeat.png" width="100" height="60"><aside class="quote" data-username="inner"><div class="title">内层作者:</div><blockquote><img src="https://quote.test/repeat.png" width="100" height="60"></blockquote></aside>""") + """<img src="https://quote.test/outside.png" width="100" height="60">"""
        compose.setContent { KudoTheme(DisplaySettings()) {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalImageGallery provides gallery) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { RichContent(body, {}, {}) }
            }
        } }
        compose.onAllNodesWithContentDescription("收起引用")[1].performScrollTo().performClick()
        compose.onAllNodesWithContentDescription("收起引用")[0].performScrollTo().performClick()
        compose.onNodeWithTag("article-image-article-2").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, gallery.session!!.index)
            assertEquals(listOf("repeat.png", "repeat.png", "outside.png"), gallery.session!!.images.map { it.url.substringAfterLast('/') })
            scope.launch { gallery.dismiss(1) }
        }
        compose.waitUntil(5000) { gallery.session == null }
        compose.onNodeWithTag("article-image-article-1").assertIsDisplayed()
        compose.onNodeWithText("电子称重勺").assertIsDisplayed()
        compose.onNodeWithTag("article-image-article-1").performClick()
        compose.runOnIdle { assertEquals(1, gallery.session!!.index) }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onNodeWithTag("quote-fixture").captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
