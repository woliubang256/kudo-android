package com.kudo.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.EventListener
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import com.kudo.android.data.*
import com.kudo.android.ui.components.TopicCard
import com.kudo.android.ui.components.imageAspectLabel
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import com.kudo.android.media.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class ImageGalleryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var observedGallery: ImageGalleryState? = null
    private val author = Member(21, "gallery", "图片测试作者", "", avatarDetailsLoaded = true)
    private val loaded = CopyOnWriteArrayList<String>()
    private val failOriginal = AtomicBoolean(false)
    private var imageGate: CompletableDeferred<Unit>? = null
    private lateinit var previousLoader: ImageLoader
    private lateinit var loader: ImageLoader
    private val palette = listOf(0xFFFFCF52.toInt(), 0xFF46C598.toInt(), 0xFF508CF0.toInt(), 0xFFEB819B.toInt(), 0xFF9F81EA.toInt())
    private val pictures = (1..5).map { number -> ArticleBlock.Image("https://gallery.test/$number.png", "图片 $number", 4f / 3f,
        filename = "figure-$number.png", dimensions = "800×600", sizeLabel = "24 KB") }

    @Before fun images() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        previousLoader = SingletonImageLoader.get(context)
        val bytes = (1..5).associate { "$it.png" to png(800, 600, palette[it - 1], it.toString()) } +
            mapOf("wide.png" to png(1688, 339, palette[0], "1688 x 339"),
                "tall.png" to png(100, 500, palette[1], "长图"),
                "tall-sharp.png" to png(180, 1000, palette[1], "长图"))
        loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                    if (!data.toString().startsWith("https://gallery.test/")) return null
                    return object : Fetcher {
                        override suspend fun fetch(): SourceFetchResult {
                            imageGate?.await()
                            val name = data.toString().substringAfterLast('/')
                            if (name == "failed.png" && failOriginal.get()) throw IOException("fixture offline")
                            return SourceFetchResult(ImageSource(Buffer().write(bytes.getValue(if (name == "failed.png") "1.png" else name)), options.fileSystem),
                                "image/png", DataSource.MEMORY)
                        }
                    }
                }
            })
        }.eventListener(object : EventListener() {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) { loaded += request.data.toString() }
        }).build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun restore() { imageGate?.complete(Unit); SingletonImageLoader.setUnsafe(previousLoader); loader.shutdown() }

    private fun html(images: List<ArticleBlock.Image>) = images.joinToString("") { image ->
        """<div class="lightbox-wrapper"><a class="lightbox" href="${image.originalUrl}"><img src="${image.url}" width="${if (image.url.endsWith("wide.png")) 1688 else 800}" height="${if (image.url.endsWith("wide.png")) 339 else 600}" alt="${image.alt}"><div class="meta"><span class="filename">${image.filename}</span><span class="informations">${image.dimensions} ${image.sizeLabel}</span></div></a></div>"""
    }

    @Composable private fun Detail(main: List<ArticleBlock.Image> = pictures, comments: Boolean = false, openLink: (String) -> Unit = {}, exports: ImageExportServices? = null) {
        val first = Post(10, 1, author, "<p>查看图片及返回位置</p>" + html(main), "2026-10-09T00:00:00Z", 0, false, null, null, true)
        val replies = if (comments) listOf(
            first.copy(id = 11, number = 2, cooked = html(pictures.take(2))),
            first.copy(id = 12, number = 3, cooked = html(pictures.takeLast(3))),
        ) else emptyList()
        val posts = listOf(first) + replies
        KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            CompositionLocalProvider(LocalImageExportServices provides exports) { ImageGalleryHost({ pictures }, openLink) {
                val gallery = LocalImageGallery.current
                SideEffect { observedGallery = gallery }
                TopicScreen(TopicState(TopicDetail(5, "图片查看器验收", 1, posts, posts.map { it.id }, false), false),
                    GlassMode.LIQUID, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            } }
        }
    }

    private fun openFirst() {
        compose.waitUntil(5_000) { loaded.contains(pictures.first().url) }
        compose.onNodeWithTag("article-image-post-10-0").performTouchInput { click() }
        compose.onNodeWithTag("image-viewer").assertIsDisplayed()
    }

    private fun third() {
        repeat(2) { compose.onNodeWithTag("image-pager").performTouchInput { swipeLeft(durationMillis = 500) } }
        compose.onNodeWithText("3 / 5").assertIsDisplayed()
    }

    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun assertTricolorLoading() {
        val pixels = compose.onNode(progress, useUnmergedTree = true).assertIsDisplayed().captureToImage().toPixelMap()
        var yellow = 0
        var white = 0
        var ink = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > 0.7f && color.green > 0.5f && color.blue < 0.4f) yellow++
            if (color.red > 0.8f && color.green > 0.8f && color.blue > 0.8f) white++
            if (color.red in 0.08f..0.15f && color.green in 0.08f..0.15f && color.blue in 0.08f..0.15f) ink++
        }
        assertTrue("图片加载圈应包含黄色段，实际像素数=$yellow", yellow > 5)
        assertTrue("图片加载圈应包含白色段，实际像素数=$white", white > 5)
        assertTrue("图片加载圈应包含深色段，实际像素数=$ink", ink > 5)
    }

    @Test fun fullscreenImageLoadingUsesTheTricolorIndicatorUntilDecodeCompletes() {
        val gate = CompletableDeferred<Unit>().also { imageGate = it }
        val gallery = ImageGalleryState().apply { open(pictures.take(1), 0, "loading-fixture") }
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) {
            ImageScreen(gallery, gallery.session!!, {})
        } }
        capture("gallery-image-loading-colors.png")
        assertTricolorLoading()
        gate.complete(Unit)
        compose.waitUntil(5_000) { loaded.contains(pictures.first().url) }
        compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("gallery-photo-0").assertIsDisplayed()
    }

    @Test fun deferredAlbumLoadingUsesTheTricolorIndicatorUntilTheAlbumArrives() {
        val album = CompletableDeferred<List<ArticleBlock.Image>>()
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) {
            ImageGalleryHost({ album.await() }, {}) { DeferredAlbumPreview() }
        } }
        compose.waitUntil(5_000) { loaded.contains(pictures.first().url) }
        compose.onNodeWithTag("deferred-album-image").performTouchInput { click() }
        capture("gallery-album-loading-colors.png")
        assertTricolorLoading()
        album.complete(pictures)
        compose.onNodeWithText("1 / 5").assertIsDisplayed()
        compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun wideImageUsesItsRealHeightAndAutomaticInformationIsHidden() {
        val wide = pictures.first().copy(url = "https://gallery.test/wide.png", originalUrl = "https://gallery.test/wide.png",
            ratio = 1688f / 339f, filename = "20261008230932_14_506", dimensions = "1688×339", sizeLabel = "22.8 KB")
        compose.setContent { Detail(listOf(wide)) }
        compose.waitUntil(5_000) { loaded.contains(wide.url) }
        val bounds = compose.onNodeWithTag("article-image-post-10-0").fetchSemanticsNode().boundsInRoot
        assertEquals(1688f / 339f, bounds.width / bounds.height, 0.03f)
        compose.onNodeWithText(wide.filename).assertDoesNotExist()
        compose.onNodeWithText("1688×339 22.8 KB").assertDoesNotExist()
        compose.onNodeWithText("宽图", useUnmergedTree = true).assertIsDisplayed()
        capture("gallery-wide-inline.png")
    }

    @Test fun tallPreviewHasABoundedHeightAndShrinksItsWidthBeforeOpeningTheOriginal() {
        var opened = ""
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
                RichContent("""<a class="lightbox" href="https://gallery.test/original-tall.png"><img src="https://gallery.test/tall.png" width="100" height="500"></a><p>长图后面的回复</p>""",
                    { opened = it }, {})
            }
        } }
        compose.waitUntil(5_000) { loaded.contains("https://gallery.test/tall.png") }
        val preview = compose.onNodeWithTag("article-image-article-0")
        preview.assertHeightIsEqualTo(300.dp).assertWidthIsEqualTo(60.dp)
        val bounds = preview.fetchSemanticsNode().boundsInRoot
        assertEquals(with(compose.density) { 16.dp.toPx() }, bounds.left, 1f)
        compose.onNodeWithText("长图后面的回复").assertIsDisplayed()
        preview.performTouchInput { click() }
        compose.runOnIdle { assertEquals("https://gallery.test/original-tall.png", opened) }
        capture("image-preview-tall-bounded.png")
    }

    @Test fun tallPreviewWithoutHtmlDimensionsBecomesBoundedAfterDecode() {
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
                RichContent("""<img src="https://gallery.test/tall.png"><p>下一条内容</p>""", {}, {})
            }
        } }
        compose.waitUntil(5_000) { loaded.contains("https://gallery.test/tall.png") }
        compose.onNodeWithTag("article-image-article-0").assertHeightIsEqualTo(300.dp).assertWidthIsEqualTo(60.dp)
        compose.onNodeWithText("下一条内容").assertIsDisplayed()
    }

    @Test fun listBadgeKeepsItsPlacementAndOpensTopicWithoutLoadingAnAlbum() {
        val wide = ArticleBlock.Image("https://gallery.test/wide.png", "宽图示例", 1688f / 339f)
        val topic = Topic(9, "形状角标测试", "", author, 1, emptyList(), 0, 0, 0, wide.url, "2026-10-09T00:00:00Z")
        var topicOpens = 0
        var albumRequests = 0
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            ImageGalleryHost({ albumRequests++; pictures }, {}) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
                    TopicCard(topic, null, { topicOpens++ }, {})
                }
            }
        } }
        compose.waitUntil(5_000) { loaded.contains(wide.url) }
        val badge = compose.onNodeWithText("宽图", useUnmergedTree = true).assertIsDisplayed()
        val previewBounds = compose.onNodeWithTag("topic-image-9").fetchSemanticsNode().boundsInRoot
        val badgeBounds = compose.onNodeWithTag("image-aspect-badge", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val inset = with(compose.density) { 6.dp.toPx() }
        assertEquals(previewBounds.right - inset, badgeBounds.right, 1f)
        assertEquals(previewBounds.bottom - inset, badgeBounds.bottom, 1f)
        badge.performTouchInput { click() }
        compose.onNodeWithTag("image-viewer").assertDoesNotExist()
        compose.onNodeWithText("宽图", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, topicOpens); assertEquals(0, albumRequests) }
        capture("feed-image-opens-topic.png")
    }

    @Test fun aspectBadgesRecognizeThresholdsWithoutLabelingInvalidOrOrdinaryImages() {
        assertEquals("宽图", imageAspectLabel(3f))
        assertEquals("长图", imageAspectLabel(1f / 3f))
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 1f, 2.999f, 0.334f).forEach {
            assertNull("不能给未知、非法或普通比例 $it 加标识", imageAspectLabel(it))
        }
    }

    @Test fun replyUsesTheSharpPreviewAndKeepsTheBadgeInsideInDarkLargeText() {
        val cooked = """<a class="lightbox" href="https://gallery.test/tall.png"><img src="https://gallery.test/tall.png" width="90" height="500" srcset="https://gallery.test/tall.png, https://gallery.test/tall-sharp.png 2x"></a>"""
        val reply = Post(14, 4, author, cooked, "2026-10-09T00:00:00Z", 0, false, null, null, true)
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            TopicScreen(TopicState(TopicDetail(5, "长图回复", 1, listOf(reply), listOf(14), false), false),
                GlassMode.LIQUID, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.waitUntil(5_000) { loaded.contains("https://gallery.test/tall-sharp.png") }
        val preview = compose.onNodeWithTag("article-image-post-14-0")
        preview.assertHeightIsEqualTo(300.dp).assertWidthIsEqualTo(54.dp)
        val imageBounds = preview.fetchSemanticsNode().boundsInRoot
        val badge = compose.onNodeWithTag("image-aspect-badge", useUnmergedTree = true)
        val badgeBounds = badge.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val textBounds = compose.onNodeWithText("长图", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("大字号角标不能超出图片", badgeBounds.left >= imageBounds.left && badgeBounds.bottom <= imageBounds.bottom)
        assertTrue("角标文字必须完整显示", textBounds.left >= badgeBounds.left && textBounds.right <= badgeBounds.right && textBounds.top >= badgeBounds.top && textBounds.bottom <= badgeBounds.bottom)
        val inset = with(compose.density) { 6.dp.toPx() }
        assertEquals(imageBounds.right - inset, badgeBounds.right, 1f)
        assertEquals(imageBounds.bottom - inset, badgeBounds.bottom, 1f)
        capture("image-badge-reply-dark-large.png")
    }

    @Test fun tallImageAndItsBadgeZoomFromTheNarrowSourceAndReturnTogether() {
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            ImageGalleryHost({ emptyList() }, {}) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
                    RichContent("""<img src="https://gallery.test/tall.png" width="100" height="500">""", {}, {})
                }
            }
        } }
        compose.waitUntil(5_000) { loaded.contains("https://gallery.test/tall.png") }
        compose.onNodeWithText("长图", useUnmergedTree = true).assertIsDisplayed()
        val source = compose.onNodeWithTag("article-image-article-0").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("article-image-article-0").performClick()
            awaitTransition()
            assertNear(source, coloredBounds(palette[1]), 4f)
            compose.mainClock.advanceTimeBy(100)
            assertTrue("窄长图应从自己的矩形放大", coloredBounds(palette[1]).width > source.width)
            compose.mainClock.advanceTimeBy(500)
        } finally { compose.mainClock.autoAdvance = true }
        // The covered source stays composed; only the merged accessibility tree is cleared.
        compose.onNodeWithText("长图").assertDoesNotExist()
        val fullscreen = compose.onRoot().captureToImage().toPixelMap()
        val badgePixel = fullscreen[(source.right - 10).toInt(), (source.bottom - 10).toInt()]
        assertTrue("全屏只显示长图，来源黄色角标不能透出", badgePixel.red < 0.5f && badgePixel.green > 0.5f)
        compose.onNodeWithTag("gallery-page-0").performTouchInput { click() }
        compose.onNodeWithText("长图", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("article-image-article-0").assertHeightIsEqualTo(300.dp).assertWidthIsEqualTo(60.dp)
        assertNear(source, coloredBounds(palette[1]), 4f)
        capture("image-badge-tall-return.png")
    }

    @Test fun longImageInitiallyFillsTheWidthScrollsAndStillPagesAtDefaultZoom() {
        val tall = ArticleBlock.Image("https://gallery.test/tall.png", "长图", 0.2f)
        compose.setContent { KudoTheme(DisplaySettings()) {
            ImageGalleryHost({ emptyList() }, {}) {
                val gallery = LocalImageGallery.current!!
                SideEffect { observedGallery = gallery }
                androidx.compose.material3.Text("打开长图", Modifier.clickable { gallery.open(listOf(tall, pictures[0]), 0, "long-fixture") })
            }
        } }
        compose.onNodeWithText("打开长图").performClick()
        compose.waitUntil(5000) { loaded.contains(tall.url) }
        val viewport = compose.onNodeWithTag("gallery-page-0").fetchSemanticsNode().boundsInRoot
        val bounds = coloredBounds(palette[1])
        assertEquals(viewport.width, bounds.width, 3f)
        compose.runOnIdle { assertEquals(0f, observedGallery!!.session!!.transforms[0].displayedBounds().top, 1f) }
        compose.onNodeWithTag("gallery-page-0").performTouchInput { swipeUp(durationMillis = 600) }
        compose.runOnIdle { assertTrue(observedGallery!!.session!!.transforms[0].displayedBounds().top < -50f) }
        capture("gallery-long-default-width.png")
        compose.onNodeWithTag("image-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
    }

    @Test fun postPagerClosesToThirdImageAboveTheBottomActions() {
        compose.setContent { Detail() }
        openFirst()
        third()
        capture("gallery-third-fullscreen.png")
        compose.onNodeWithTag("gallery-page-2").performTouchInput { click() }
        compose.onNodeWithTag("image-viewer").assertDoesNotExist()
        val image = compose.onNodeWithTag("article-image-post-10-2").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("topic-actions").fetchSemanticsNode().boundsInRoot
        assertTrue("返回图不能被底部操作栏遮住：$image / $actions", image.bottom <= actions.top + 2)
        capture("gallery-return-third.png")
    }

    @Test fun commentsKeepSeparateAlbums() {
        compose.setContent { Detail(emptyList(), comments = true) }
        compose.onNodeWithTag("article-image-post-11-0").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithTag("image-pager").performTouchInput { swipeLeft(durationMillis = 500) }
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithTag("gallery-page-1").performTouchInput { click() }
        compose.onNodeWithTag("article-image-post-11-1").assertIsDisplayed()
    }

    @Test fun discussionImageReturnsToItsPreviewAndKeepsFloorAndActionsAvailable() {
        val first = Post(10, 1, author, "<p>主帖正文</p>", "", 0, false, null, null, true)
        val root = first.copy(id = 11, number = 2, cooked = "<p>原评论正文</p>", replyCount = 1)
        val child = first.copy(id = 12, number = 3, cooked = html(pictures.take(2)), replyTo = 2)
        val posts = listOf(first, root, child)
        compose.setContent { KudoTheme(DisplaySettings()) { ImageGalleryHost({ emptyList() }, {}) {
            val gallery = LocalImageGallery.current
            SideEffect { observedGallery = gallery }
            TopicScreen(TopicState(TopicDetail(5, "楼中楼图片", 1, posts, posts.map(Post::id), false), false,
                replyLoads = mapOf(root.id to ReplyLoad(cursors = mapOf(root.id to ReplyCursor(3, true))))),
                GlassMode.LIQUID, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithText("查看 1 条回复").performScrollTo().performClick()
        compose.onNodeWithTag("thread-posts").performScrollToNode(hasTestTag("article-image-thread-preview-12-0"))
        compose.waitUntil(5_000) { loaded.contains(pictures.first().url) }
        compose.onNodeWithTag("article-image-thread-preview-12-0").performTouchInput { click() }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(pictures.first().originalUrl, observedGallery!!.session!!.current.originalUrl)
            assertNotNull(observedGallery!!.openingAnchor?.painter)
        }
        compose.onNodeWithTag("gallery-page-0").performTouchInput { click() }
        compose.onNodeWithTag("image-viewer").assertDoesNotExist()
        compose.onNodeWithTag("article-image-thread-preview-12-0").assertIsDisplayed()
        compose.onNodeWithTag("thread-posts").performScrollToNode(hasTestTag("thread-like-3"))
        compose.onNodeWithTag("thread-like-3").assertIsEnabled()
        compose.onNodeWithTag("thread-reply-3").assertIsEnabled()
        compose.onNodeWithTag("thread-floor-3").performScrollTo().assertTextEquals("#3")
        compose.onNodeWithTag("thread-jump-3").performClick()
        compose.onNodeWithTag("thread-posts").assertDoesNotExist()
        compose.onNodeWithTag("post-3").assertIsDisplayed()
    }

    @Test fun listImageOpensTopicOnceWithoutPreviewOrAlbumRequest() {
        var topicOpens = 0
        var albumRequests = 0
        val topic = Topic(9, "列表图片进入详情", "点击图片查看帖子", author, 1, emptyList(), 0, 0, 0, pictures.first().url, "2026-10-09T00:00:00Z")
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            ImageGalleryHost({ albumRequests++; pictures }, {}) {
                val gallery = LocalImageGallery.current
                SideEffect { observedGallery = gallery }
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) { TopicCard(topic, null, { topicOpens++ }, {}) }
            }
        } }
        compose.waitUntil(5_000) { loaded.contains(pictures.first().url) }
        compose.onNodeWithTag("topic-image-9").performTouchInput { click() }
        compose.onNodeWithTag("image-viewer").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, topicOpens)
            assertEquals(0, albumRequests)
            assertNull(observedGallery?.session)
        }
    }

    @Test fun pinchAndPanningDoNotTurnPagesOrCloseTheViewer() {
        compose.setContent { Detail() }
        openFirst()
        val page = compose.onNodeWithTag("gallery-page-0")
        page.performTouchInput {
            down(0, Offset(width * 0.4f, height * 0.5f))
            down(1, Offset(width * 0.6f, height * 0.5f))
            moveTo(0, Offset(width * 0.2f, height * 0.5f), delayMillis = 200)
            moveTo(1, Offset(width * 0.8f, height * 0.5f), delayMillis = 200)
            up(0); up(1)
        }
        page.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已放大 3.0 倍"))
        page.performTouchInput { swipeDown(durationMillis = 500) }
        compose.onNodeWithTag("image-viewer").assertIsDisplayed()
        compose.onNodeWithText("1 / 5").assertIsDisplayed()
        val reset = page.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "还原图片" }
        compose.runOnIdle { assertTrue(reset.action()) }
        page.performTouchInput { click() }
        compose.onNodeWithTag("image-viewer").assertDoesNotExist()
    }

    @Test fun shortPullAndCancelledPullRestoreTheImageWhileLongPullCloses() {
        compose.setContent { Detail() }
        openFirst()
        val photo = compose.onNodeWithTag("gallery-photo-0", useUnmergedTree = true)
        val resting = photo.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("gallery-page-0").performTouchInput {
            swipe(center, center + Offset(0f, height * 0.08f), durationMillis = 600)
        }
        compose.onNodeWithTag("image-viewer").assertIsDisplayed()
        compose.runOnIdle { assertFalse("小幅下拉不能开始关闭", observedGallery?.session?.closing != false) }
        assertEquals(resting.top, photo.fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.onNodeWithTag("gallery-page-0").performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(0f, height * 0.01f), delayMillis = 30) }
            cancel()
        }
        compose.onNodeWithTag("image-viewer").assertIsDisplayed()
        assertEquals(resting.top, photo.fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.onNodeWithTag("gallery-page-0").performTouchInput {
            swipe(Offset(width / 2f, height * 0.3f), Offset(width / 2f, height * 0.8f), durationMillis = 650)
        }
        compose.onNodeWithTag("image-viewer").assertDoesNotExist()
    }

    @Test fun longPressShowsTheImageMenuAndDismissingKeepsTheCurrentPage() {
        compose.setContent { Detail() }
        openFirst()
        third()
        compose.onNodeWithTag("gallery-page-2").performTouchInput { longClick() }
        compose.onNodeWithText("分享图片").assertIsDisplayed()
        compose.onNodeWithText("保存原图（24 KB）").assertIsDisplayed()
        compose.onNodeWithText("保存所有图片（约 120 KB）").assertIsDisplayed()
        compose.onNodeWithText("图片信息").assertDoesNotExist()
        capture("gallery-image-action-menu.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("image-action-menu").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("3 / 5").assertIsDisplayed()
        compose.onNodeWithTag("image-viewer").assertIsDisplayed()
    }

    @Test fun sharingAndSavingAfterPagingUseTheCurrentOriginalAndBatchUsesTheWholeAlbum() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "gallery-export-fixture-${System.nanoTime()}").apply { mkdirs() }
        val requests = CopyOnWriteArrayList<String>()
        val saved = CopyOnWriteArrayList<String>()
        val shared = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(png(32, 32, palette[2], "3").toResponseBody()).build()
        }.build()
        val downloader = ImageDownloader(client, directory)
        val exports = ImageExportServices(downloader, ImageSaver(downloader, ImageStore { saved += it.displayName })) { shared += it.displayName }
        try {
            compose.setContent { Detail(exports = exports) }
            openFirst(); third()
            compose.onNodeWithTag("gallery-page-2").performTouchInput { longClick() }
            compose.onNodeWithText("保存原图（24 KB）").performClick()
            compose.waitUntil(5_000) { saved.size == 1 && compose.onAllNodesWithText("正在处理图片…").fetchSemanticsNodes().isEmpty() }
            assertEquals(listOf("figure-3.png"), saved.toList())
            assertEquals(listOf(pictures[2].originalUrl), requests.toList())
            compose.onNodeWithTag("gallery-page-2").performTouchInput { longClick() }
            compose.onNodeWithText("分享图片").performClick()
            compose.waitUntil(5_000) { shared.size == 1 && compose.onAllNodesWithText("正在处理图片…").fetchSemanticsNodes().isEmpty() }
            assertEquals(listOf("figure-3.png"), shared.toList())
            compose.onNodeWithTag("gallery-page-2").performTouchInput { longClick() }
            compose.onNodeWithText("保存所有图片（约 120 KB）").performClick()
            compose.waitUntil(10_000) { saved.size == 6 && compose.onAllNodesWithText("正在处理图片…").fetchSemanticsNodes().isEmpty() }
            assertEquals(pictures.map { it.filename }, saved.drop(1))
            assertEquals(pictures.map { it.originalUrl }, requests.drop(2))
            compose.onNodeWithText("3 / 5").assertIsDisplayed()
        } finally { directory.deleteRecursively() }
    }

    @Test fun restoringStateKeepsTheThirdPageAndSystemBackReturnsToItsSource() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Detail() }
        openFirst()
        third()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("3 / 5").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("image-viewer").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("article-image-post-10-2").assertIsDisplayed()
    }

    @Test fun failedOriginalCanRetryWithoutClosingOrLosingThePreview() {
        failOriginal.set(true)
        compose.setContent { Detail(listOf(pictures.first().copy(originalUrl = "https://gallery.test/failed.png"))) }
        openFirst()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("图片加载失败").fetchSemanticsNodes().isNotEmpty() }
        failOriginal.set(false)
        compose.onNodeWithText("重试").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("图片加载失败").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("image-viewer").assertIsDisplayed()
    }

    // Saved sessions from older versions may still contain a deferred album.
    // Keep race/transition coverage without restoring the removed feed preview entry point.
    @Composable private fun DeferredAlbumPreview() {
        val gallery = LocalImageGallery.current!!
        var image by remember { mutableStateOf(pictures.first()) }
        val anchor = remember { GalleryThumbnailAnchor().apply { contentScale = ContentScale.Crop } }
        val radius = with(LocalDensity.current) { 10.dp.toPx() }
        SideEffect { anchor.cornerRadius = radius; observedGallery = gallery }
        RegisterGalleryOrigin("deferred-fixture") { current, _ -> image = current; withFrameNanos { }; anchor }
        DisposableEffect(anchor) { onDispose { anchor.coordinates = null; anchor.painter = null } }
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.9f).onGloballyPositioned { anchor.coordinates = it }
                .graphicsLayer { alpha = if (gallery.hiddenAnchor === anchor) 0f else 1f }
                .clip(RoundedCornerShape(10.dp)).testTag("deferred-album-image")
                .clickable { gallery.open(listOf(image), 0, "deferred-fixture", anchor, 9) }) {
                AsyncImage(image.url, "恢复相册测试图片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    onSuccess = { anchor.painter = it.painter })
            }
        }
    }

    @Test fun fillingADeferredAlbumDoesNotResetAnInProgressZoom() {
        val album = CompletableDeferred<List<ArticleBlock.Image>>()
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            ImageGalleryHost({ album.await() }, {}) {
                DeferredAlbumPreview()
            }
        } }
        compose.waitUntil(5_000) { loaded.contains(pictures.first().url) }
        compose.onNodeWithTag("deferred-album-image").performTouchInput { click() }
        compose.onNodeWithTag("gallery-page-0").performTouchInput {
            down(0, Offset(width * 0.4f, height * 0.5f))
            down(1, Offset(width * 0.6f, height * 0.5f))
            moveTo(0, Offset(width * 0.2f, height * 0.5f), delayMillis = 200)
            moveTo(1, Offset(width * 0.8f, height * 0.5f), delayMillis = 200)
            up(0); up(1)
        }
        compose.runOnIdle { album.complete(pictures) }
        compose.onNodeWithText("1 / 5").assertIsDisplayed()
        compose.onNodeWithTag("gallery-page-0").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已放大 3.0 倍"))
    }

    @Test fun removingTheSourceWhileViewingStillAllowsClosing() {
        var show by mutableStateOf(true)
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            ImageGalleryHost({ pictures }, {}) {
                Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
                    if (show) RichContent(html(pictures), {}, {}) else Text("原内容已更新")
                }
            }
        } }
        compose.waitUntil(5_000) { loaded.contains(pictures.first().url) }
        compose.onNodeWithTag("article-image-article-0").performTouchInput { click() }
        compose.runOnIdle { show = false }
        compose.onNodeWithTag("gallery-page-0").performTouchInput { click() }
        compose.onNodeWithTag("image-viewer").assertDoesNotExist()
        compose.onNodeWithText("原内容已更新").assertIsDisplayed()
    }

    @Test fun fillingADeferredAlbumWhileDraggingKeepsPagerPagesConsistent() {
        val album = CompletableDeferred<List<ArticleBlock.Image>>()
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            ImageGalleryHost({ album.await() }, {}) {
                DeferredAlbumPreview()
            }
        } }
        compose.waitUntil(5_000) { loaded.contains(pictures.first().url) }
        compose.onNodeWithTag("deferred-album-image").performTouchInput { click() }
        val pager = compose.onNodeWithTag("image-pager")
        val bounds = pager.fetchSemanticsNode().boundsInRoot
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        fun pointer(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.left + bounds.width * fraction, bounds.center.y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        // Android can measure a dragged page before Compose recomposes the album owner.
        // Native events avoid the Compose input helper's implicit clock/idle synchronization.
        compose.mainClock.autoAdvance = false
        try {
            pointer(MotionEvent.ACTION_DOWN, 0.8f)
            SystemClock.sleep(60)
            pointer(MotionEvent.ACTION_MOVE, 0.6f)
            compose.runOnUiThread { album.complete(pictures) }
            compose.waitUntil(5_000) { observedGallery?.session?.albumLoaded == true }
            SystemClock.sleep(100)
            pointer(MotionEvent.ACTION_MOVE, 0.2f)
            pointer(MotionEvent.ACTION_UP, 0.2f)
        } finally { compose.mainClock.autoAdvance = true }
        repeat(4) { pager.performTouchInput { swipeLeft(durationMillis = 500) } }
        compose.onNodeWithText("5 / 5").assertIsDisplayed()
        compose.onNodeWithTag("gallery-page-4").performTouchInput { click() }
        compose.onNodeWithTag("image-viewer").assertDoesNotExist()
        compose.onNodeWithTag("deferred-album-image").performTouchInput { click() }
        compose.waitUntil(5_000) { observedGallery?.session?.albumLoaded == true }
        compose.runOnIdle { assertEquals("再次打开第5张预览，补齐相册不能跳回第1张", 4, observedGallery?.session?.index) }
        compose.onNodeWithText("5 / 5").assertIsDisplayed()
    }

    @Test fun completingAnAlbumKeepsTheDisplayedImageForTheClosingAnimation() {
        val album = CompletableDeferred<List<ArticleBlock.Image>>()
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            ImageGalleryHost({ album.await() }, {}) {
                DeferredAlbumPreview()
            }
        } }
        compose.waitUntil(5_000) { loaded.contains(pictures.first().url) }
        compose.onNodeWithTag("deferred-album-image").performTouchInput { click() }
        compose.waitUntil(5_000) { loaded.count { it == pictures.first().url } >= 2 }
        compose.runOnIdle { album.complete(pictures) }
        compose.onNodeWithText("1 / 5").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("gallery-page-0").performClick()
            awaitTransition()
            val start = coloredBounds(palette[0])
            compose.mainClock.advanceTimeBy(80)
            assertTrue("补齐相册不能丢掉当前画面并退化为淡出", coloredBounds(palette[0]).width < start.width)
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("image-viewer").assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun transitionPixelsGrowFromFirstSourceAndShrinkToTheThirdSource() {
        compose.setContent { Detail() }
        compose.waitUntil(5_000) { loaded.contains(pictures.first().url) }
        val source = compose.onNodeWithTag("article-image-post-10-0").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("article-image-post-10-0").performClick()
        awaitTransition()
        val opening = coloredBounds(palette[0])
        assertNear(source, opening, 4f)
        compose.mainClock.advanceTimeBy(80)
        val middle = coloredBounds(palette[0])
        assertTrue("放大过程应保持可见图片，且比来源更宽：$source → $middle", middle.width > source.width && middle.width < 1080f)
        compose.mainClock.advanceTimeBy(500)
        val expanded = coloredBounds(palette[0])
        assertTrue(expanded.width > source.width)
        compose.onNodeWithTag("gallery-transition").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        third()
        compose.mainClock.autoAdvance = false
        val fullThird = coloredBounds(palette[2])
        compose.onNodeWithTag("gallery-page-2").performClick()
        awaitTransition()
        val closing = coloredBounds(palette[2])
        assertNear(fullThird, closing, 4f)
        compose.mainClock.advanceTimeBy(80)
        val shrinking = coloredBounds(palette[2])
        assertTrue("缩小的应是蓝色第3张：$fullThird → $shrinking", shrinking.width < fullThird.width)
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("image-viewer").assertDoesNotExist()
        val target = compose.onNodeWithTag("article-image-post-10-2").fetchSemanticsNode().boundsInRoot
        assertNear(target, coloredBounds(palette[2]), 3f)
        assertTrue("应缩向当前第3张位置", shrinking.center.y in fullThird.center.y..target.center.y)
        compose.mainClock.autoAdvance = true
    }

    private fun awaitTransition() {
        repeat(65) {
            if (compose.onAllNodesWithTag("gallery-transition").fetchSemanticsNodes().isNotEmpty()) return
            compose.mainClock.advanceTimeByFrame()
            android.os.SystemClock.sleep(16)
        }
        fail("图片转场应在来源完成定位后开始")
    }

    private fun coloredBounds(color: Int): Rect {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if ((pixels[y * bitmap.width + x] and 0xFFFFFF) == (color and 0xFFFFFF)) {
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("必须在真实绘制像素中找到当前图片", right > left && bottom > top)
        return Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
    }

    private fun assertNear(expected: Rect, actual: Rect, tolerance: Float) {
        assertEquals("left: $expected → $actual", expected.left, actual.left, tolerance)
        assertEquals("top: $expected → $actual", expected.top, actual.top, tolerance)
        assertEquals("right: $expected → $actual", expected.right, actual.right, tolerance)
        assertEquals("bottom: $expected → $actual", expected.bottom, actual.bottom, tolerance)
    }

    private fun png(width: Int, height: Int, color: Int, label: String): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(color)
            drawText(label, width * 0.4f, height * 0.58f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.BLACK; textSize = height * 0.18f })
        }
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(250)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
