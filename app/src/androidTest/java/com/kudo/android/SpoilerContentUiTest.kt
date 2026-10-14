package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.platform.app.InstrumentationRegistry
import coil3.*
import coil3.decode.*
import coil3.fetch.*
import coil3.request.*
import com.kudo.android.data.DisplaySettings
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(coil3.annotation.DelicateCoilApi::class)
class SpoilerContentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var previous: ImageLoader
    private lateinit var loader: ImageLoader
    @Before fun images() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        previous = SingletonImageLoader.get(context)
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 160) bitmap.setPixel(x, y, if ((x / 8 + y / 8) % 2 == 0) 0xff46c598.toInt() else 0xff202020.toInt())
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        bitmap.recycle()
        loader = ImageLoader.Builder(context).components { add(object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                if (!data.toString().startsWith("https://spoiler.test/")) return null
                return object : Fetcher { override suspend fun fetch() = SourceFetchResult(
                    ImageSource(Buffer().write(bytes), options.fileSystem), "image/png", DataSource.MEMORY) }
            }
        }) }.build()
        SingletonImageLoader.setUnsafe(loader)
    }
    @After fun cleanup() { SingletonImageLoader.setUnsafe(previous); loader.shutdown() }
    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
    @Test fun revealedSpoilerImageOpensPreviewWithoutHiding() {
        var opened = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            RichContent("""<div class="spoiler"><img src="https://spoiler.test/photo.png" width="160" height="100"></div>""", { opened++ }, {})
        } }
        val region = compose.onNodeWithTag("article-spoiler-root-0")
        compose.onNodeWithContentDescription("隐藏剧透图片").assertDoesNotExist()
        region.performTouchInput { click() }
        region.assert(state("剧透已显示"))
        region.assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        compose.onNodeWithContentDescription("隐藏剧透图片").assertIsDisplayed()
        compose.onNodeWithTag("spoiler-image-root-0-0", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals("Revealed image must open the preview", 1, opened) }
        region.assert(state("剧透已显示"))
        compose.onNodeWithContentDescription("隐藏剧透图片").performTouchInput { click() }
        region.assert(state("剧透已隐藏"))
        compose.runOnIdle { assertEquals("Hide control must not open the preview", 1, opened) }
    }

    /** Opt-in, read-only audit of server metadata. No community cookies or post bodies are exported. */
    @Test fun auditListCoverMetadata() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("spoilerListAudit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as KudoApplication).container
        val report = JSONObject()
        try {
            val response = app.client.get("/latest.json")
            val topics = response.getJSONObject("topic_list").getJSONArray("topics")
            val keys = sortedSetOf<String>()
            val imageMetadata = JSONArray()
            for (i in 0 until topics.length()) {
                val topic = topics.getJSONObject(i)
                topic.keys().forEach { keys += it }
                if (!topic.optString("image_url").isNullOrBlank() && topic.optString("image_url") != "null") {
                    val item = JSONObject().put("id", topic.optInt("id"))
                    topic.keys().asSequence().filter { it.contains("image") || it.contains("thumbnail") || it.contains("spoiler") }
                        .forEach { key -> item.put(key, if (key == "image_url") "present" else topic.opt(key)) }
                    imageMetadata.put(item)
                }
            }
            report.put("count", topics.length()).put("keys", JSONArray(keys.toList())).put("covers", imageMetadata)
        } catch (failure: Exception) { report.put("failure", failure.javaClass.simpleName).put("message", failure.message) }
        File(context.getExternalFilesDir(null), "spoiler-list-audit.json").writeText(report.toString(2))
    }
    @Test fun blockTextEmojiAndImageHideOnlyThroughTheImageControl() {
        var opened = 0
        compose.setContent { KudoTheme(DisplaySettings()) { Column {
            RichContent("""<div class="spoiler"><p>秘密文本<img class="emoji" src="https://spoiler.test/emoji.png" alt=":ok:"></p><img src="https://spoiler.test/photo.png" width="160" height="100"></div>""", { opened++ }, { opened++ })
        } } }
        val region = compose.onNodeWithTag("article-spoiler-root-0")
        region.assert(state("剧透已隐藏"))
        compose.onNodeWithText("秘密文本", substring = true).assertDoesNotExist()
        region.performTouchInput { click() }
        region.assert(state("剧透已显示"))
        compose.onNodeWithText("秘密文本", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("隐藏剧透图片").performTouchInput { click() }
        region.assert(state("剧透已隐藏"))
        compose.runOnIdle { assertEquals(0, opened) }
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "spoiler-hidden.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    @Test fun previewReturnAndRestorationKeepTheImageClearAndAlbumsSeparate() {
        val restoration = StateRestorationTester(compose)
        var gallery: ImageGalleryState? = null
        restoration.setContent { KudoTheme(DisplaySettings()) {
            ImageGalleryHost({ error("Spoiler preview must not request a topic album") }, {}) {
                val current = LocalImageGallery.current
                SideEffect { gallery = current }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RichContent("""<div class="spoiler"><img src="https://spoiler.test/secret.png" width="160" height="100"></div>
                        <div class="spoiler"><img src="https://spoiler.test/other-secret.png" width="160" height="100"></div>
                        <img src="https://spoiler.test/public.png" width="160" height="100">""", { error("Preview must use the gallery") }, {}, galleryKey = "spoiler-fixture")
                }
            }
        } }
        val region = compose.onNodeWithTag("article-spoiler-root-0")
        region.performTouchInput { click() }
        compose.onNodeWithTag("spoiler-image-root-0-0", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithTag("image-viewer").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("https://spoiler.test/secret.png"), gallery!!.session!!.images.map { it.url }) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("image-viewer").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("image-viewer").fetchSemanticsNodes().isEmpty() }
        region.assert(state("剧透已显示"))
        compose.onNodeWithTag("article-spoiler-root-1").assert(state("剧透已隐藏"))
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "spoiler-revealed-controls.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        compose.onNodeWithContentDescription("隐藏剧透图片").performTouchInput { click() }
        region.assert(state("剧透已隐藏"))
        compose.onNodeWithTag("article-image-spoiler-fixture-0").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("image-viewer").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("https://spoiler.test/public.png"), gallery!!.session!!.images.map { it.url }) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("image-viewer").fetchSemanticsNodes().isEmpty() }
        region.assert(state("剧透已隐藏"))
    }

    @Test fun plainBlockSpoilerStillTogglesByTouch() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            RichContent("""<div class="spoiler"><p>秘密文本</p><p>另一段秘密</p></div>""", {}, {})
        } }
        val region = compose.onNodeWithTag("article-spoiler-root-0")
        region.performTouchInput { click() }
        region.assert(state("剧透已显示"))
        region.performTouchInput { click() }
        region.assert(state("剧透已隐藏"))
    }
    @Test fun inlineSpoilerKeepsSurroundingTextAndRestoresItsStateWithoutFollowingItsLink() {
        val restoration = StateRestorationTester(compose)
        var position = Offset.Zero
        var links = 0
        restoration.setContent { KudoTheme(DisplaySettings()) {
            val measured = rememberTextMeasurer().measure("前秘密后", MaterialTheme.typography.bodyLarge)
            position = measured.getBoundingBox(1).center
            RichContent("""<p>前<span class="spoiler"><a href="https://example.com/">秘密</a></span>后</p>""", {}, { links++ })
        } }
        val text = compose.onNodeWithTag("article-text-root-0")
        text.assertTextEquals("前剧透内容后").assert(state("剧透已隐藏"))
        text.performTouchInput { click(position) }
        text.assertTextEquals("前秘密后").assert(state("剧透已显示"))
        restoration.emulateSavedInstanceStateRestore()
        text.assert(state("剧透已显示"))
        text.performTouchInput { click(position) }
        text.assertTextEquals("前剧透内容后")
        compose.runOnIdle { assertEquals(0, links) }
    }
    @Test fun oldAndroidFallbackCoversTheContentInsteadOfShowingItUnblurred() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            SpoilerRegion("fallback", "fallback-spoiler", blurSupported = false) {
                Text("秘密", Modifier.fillMaxWidth().background(Color.Red))
            }
        } }
        val region = compose.onNodeWithTag("fallback-spoiler")
        val hidden = region.captureToImage().toPixelMap()
        assertTrue(hidden[2, 2].green > 0.1f)
        region.performTouchInput { click() }
        val shown = region.captureToImage().toPixelMap()
        assertTrue(shown[2, 2].red > 0.8f && shown[2, 2].green < 0.1f)
    }

    @Test fun obsidianCalloutRendersTitleAndCanBeExpanded() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            RichContent("""<blockquote><p>[!note]-</p><p>隐藏的提示正文</p></blockquote>""", {}, {})
        } }
        compose.onNodeWithTag("article-callout-root-0").assertIsDisplayed()
        compose.onNodeWithText("Note").assertIsDisplayed()
        compose.onNodeWithText("隐藏的提示正文").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开提示").performClick()
        compose.onNodeWithText("隐藏的提示正文").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起提示").assertIsDisplayed()
    }
}
