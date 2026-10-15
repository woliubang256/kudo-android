package com.kudo.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import com.kudo.android.data.*
import com.kudo.android.ui.feed.*
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FeedHeaderUiTest {
    @get:Rule val compose = createComposeRule()

    private fun content() {
        val topics = (1..30).map { Topic(it, "吸顶测试话题 $it", "用于验证顶部搜索折叠及列表滚动的内容。", null, 1, emptyList(), 0, 0, 0, "", "") }
        compose.setContent {
            KudoTheme(DisplaySettings()) {
                FeedScreen(FeedState(topics = topics, loading = false), null, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
    }

    @Test fun scrollingCollapsesSearchPinsTabsAndReverseExpandsSearch() {
        content()
        val initialTop = compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top
        val list = compose.onNodeWithTag("feed-list-latest")
        list.performTouchInput { swipeUp(durationMillis = 800) }
        compose.onNodeWithText("最新").assertIsDisplayed()
        val pinnedTop = compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top
        assertTrue("上滑后 Tab 应移到搜索行原位置并吸顶", pinnedTop < initialTop - 40f)
        compose.onNodeWithContentDescription("Linux.do 社区 Logo").assertIsNotDisplayed()
        list.performTouchInput { swipeUp(durationMillis = 800) }
        assertEquals(pinnedTop, compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top, 1f)
        swipeFeedDown("feed-list-latest")
        compose.onNodeWithContentDescription("Linux.do 社区 Logo").assertIsDisplayed()
        assertEquals(initialTop, compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun feedStartsWithTopicsWithoutBannerOrShortcuts() {
        content()
        compose.onNodeWithText("认真交流，自在分享").assertDoesNotExist()
        listOf("新鲜事", "热议中", "值得看", "逛分类").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("吸顶测试话题 1").assertIsDisplayed()
        assertTrue("首屏话题不能被顶部遮挡", compose.onNodeWithTag("topic-card-1").fetchSemanticsNode().boundsInRoot.top >=
            compose.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot.bottom)
    }

    @Test fun searchPillKeepsIconSloganDividerAndActionOrderedAndClickable() {
        var narrow by mutableStateOf(false)
        var searches = 0
        compose.setContent {
            KudoTheme(DisplaySettings(theme = if (narrow) ThemeMode.DARK else ThemeMode.LIGHT, fontScale = if (narrow) 1.3f else 1f)) {
                Box(Modifier.fillMaxSize()) {
                    FeedScreen(FeedState(loading = false), null, {}, {}, {}, {}, {}, { searches++ }, {}, {},
                        Modifier.width(if (narrow) 280.dp else 360.dp))
                }
            }
        }
        var expectedSearches = 0
        for (compact in listOf(false, true)) {
            compose.runOnIdle { narrow = compact }
            val pill = compose.onNodeWithTag("home-search").assertIsDisplayed().assertHasClickAction()
            val icon = compose.onNodeWithTag("home-search-icon", useUnmergedTree = true).assertIsDisplayed()
            val slogan = compose.onNodeWithText("真诚 · 友善 · 团结 · 专业", useUnmergedTree = true).assertIsDisplayed()
            val divider = compose.onNodeWithTag("home-search-divider", useUnmergedTree = true).assertIsDisplayed()
            val action = compose.onNodeWithText("搜索", useUnmergedTree = true).assertIsDisplayed()
            val bounds = listOf(icon, slogan, divider, action).map { it.fetchSemanticsNode().boundsInRoot }
            bounds.zipWithNext().forEach { (left, right) ->
                assertTrue("搜索图标、标语、竖线和搜索文字应从左到右且不重叠", left.right <= right.left)
            }
            val pillBounds = pill.fetchSemanticsNode().boundsInRoot
            assertTrue("搜索栏内容应保持在胶囊内", bounds.all { it.left >= pillBounds.left && it.right <= pillBounds.right })
            val layouts = mutableListOf<TextLayoutResult>()
            action.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("搜索文字应保持一行", 1, layout.lineCount)
            assertFalse("搜索文字不能省略", layout.isLineEllipsized(0))
            assertTrue("窄屏大字号下搜索文字应完整显示", "搜索".indices.map(layout::getBoundingBox).all {
                it.left >= -1f && it.right <= layout.size.width + 1f
            })
            for (target in listOf(icon, slogan, action)) {
                target.performTouchInput { click() }
                expectedSearches++
                compose.runOnIdle { assertEquals("点击搜索栏各区域应只触发一次搜索", expectedSearches, searches) }
            }
            capture(if (compact) "home-search-pill-dark-narrow.png" else "home-search-pill-light.png")
        }
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test fun pinnedHeaderSamplesLiveContentInLightAndDarkThemes() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        var source by mutableStateOf("red")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = SingletonImageLoader.get(context)
        val loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                    if (!data.toString().startsWith("https://header-glass.test/")) return null
                    return object : Fetcher {
                        override suspend fun fetch(): ImageFetchResult {
                            val bitmap = Bitmap.createBitmap(600, 320, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            val paint = Paint()
                            for (stripe in 0 until 19) {
                                val shade = if (stripe % 2 == 0) 255 else 80
                                paint.color = if (data.toString().contains("red")) android.graphics.Color.rgb(shade, 0, 0)
                                    else android.graphics.Color.rgb(0, 0, shade)
                                canvas.drawRect(stripe * 32f, 0f, (stripe + 1) * 32f, 320f, paint)
                            }
                            return ImageFetchResult(bitmap.asImage(), false, DataSource.MEMORY)
                        }
                    }
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
        try {
            compose.setContent {
                KudoTheme(DisplaySettings(theme = theme)) {
                    key(theme) {
                        val topics = (1..12).map {
                            Topic(it, "玻璃采样话题 $it", "", null, 1, emptyList(), 0, 0, 0, "https://header-glass.test/$source.png", "")
                        }
                        FeedScreen(FeedState(topics = topics, loading = false), null, {}, {}, {}, {}, {}, {}, {}, {})
                    }
                }
            }
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                compose.runOnIdle { theme = mode; source = "red" }
                awaitCover()
                val expanded = headerPixel()
                val imageTop = compose.onNodeWithTag("topic-image-1").fetchSemanticsNode().boundsInRoot.top
                val headerTop = compose.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot.top
                compose.onNodeWithTag("feed-list-latest").performTouchInput {
                    val start = Offset(center.x, height * 0.85f)
                    swipe(start, start - Offset(0f, imageTop - headerTop + 24f), durationMillis = 1000)
                }
                compose.onNodeWithContentDescription("Linux.do 社区 Logo").assertIsNotDisplayed()
                for (pixel in listOf(headerPixel(), headerPixel(statusBar = true))) {
                    assertTrue("吸顶后应采样红色封面：$mode $pixel", pixel.red - pixel.blue > 0.18f)
                }
                val detailContrast = headerDetailContrast()
                println("Pinned header detail contrast: $mode $detailContrast")
                assertTrue("玻璃应保留底层条纹至少一半对比度，不能糊成磨砂：$mode $detailContrast",
                    detailContrast > (255f - 80f) / 255f * 0.5f)
                capture("home-header-glass-${mode.name.lowercase()}-red.png")
                compose.runOnIdle { source = "blue" }
                awaitCover()
                val blue = headerPixel()
                assertTrue("顶部材质应随封面变化：$mode $blue", blue.blue - blue.red > 0.18f)
                capture("home-header-glass-${mode.name.lowercase()}-blue.png")
                swipeFeedDown("feed-list-latest")
                compose.onNodeWithContentDescription("Linux.do 社区 Logo").assertIsDisplayed()
                val restored = headerPixel()
                assertEquals(expanded.red, restored.red, 0.04f)
                assertEquals(expanded.blue, restored.blue, 0.04f)
            }
        } finally {
            SingletonImageLoader.setUnsafe(previous)
            loader.shutdown()
        }
    }

    private fun awaitCover() = compose.waitUntil(5000) {
        compose.onAllNodesWithTag("topic-image-1-loading", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    }

    private fun swipeFeedDown(tag: String) {
        val header = compose.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag(tag)
        val bounds = list.fetchSemanticsNode().boundsInRoot
        list.performTouchInput {
            swipe(Offset(center.x, maxOf(header.bottom - bounds.top + 16f, height * 0.1f)),
                Offset(center.x, height * 0.9f), durationMillis = 800)
        }
    }

    private fun headerPixel(statusBar: Boolean = false): Color {
        compose.waitForIdle()
        val header = compose.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().captureToImage().toPixelMap()
        return root[header.center.x.toInt(), (if (statusBar) header.top + 8f else header.bottom - 3f).toInt()]
    }

    private fun headerDetailContrast(): Float {
        val header = compose.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val samples = ((header.left + header.width * 0.2f).toInt()..(header.left + header.width * 0.8f).toInt())
            .map { pixels[it, (header.top + 8f).toInt()].red }
        return samples.max() - samples.min()
    }

    @Test fun accountTabsScrollSelectAndResetToGuestWithoutPersonalEntries() {
        var user by mutableStateOf<Member?>(null)
        var state by mutableStateOf(FeedState(loading = false))
        var categories = 0
        compose.setContent {
            KudoTheme(DisplaySettings()) {
                FeedScreen(state, user, { state = state.copy(filter = it) }, {}, {}, {}, {}, {}, {}, { categories++ })
            }
        }
        listOf("最新", "热门", "排行榜", "类别").forEach { compose.onNodeWithText(it).assertExists() }
        listOf("未读", "新", "我的帖子", "已读", "书签").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("最新").assertIsSelected()
        capture("home-tabs-guest.png")
        compose.runOnIdle {
            user = Member(1, "fixture", "测试用户", "")
            state = FeedState(filter = "unread", loading = false, accountId = 1, newCount = 5096)
        }
        compose.onNodeWithText("未读").assertIsSelected()
        compose.onNodeWithText("新 (5096)").assertExists()
        capture("home-tabs-member.png")
        for (label in listOf("最新", "新 (5096)", "热门", "排行榜", "我的帖子", "已读", "书签")) {
            compose.onNodeWithText(label).performScrollTo().performClick().assertIsSelected()
        }
        compose.onNodeWithText("类别").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, categories) }
        capture("home-tabs-member-end.png")
        compose.runOnIdle { user = null; state = FeedState(loading = false) }
        compose.onNodeWithText("最新").assertIsDisplayed().assertIsSelected()
        listOf("未读", "新 (5096)", "我的帖子", "已读", "书签").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test fun darkLargeTextAndNarrowWindowKeepLabelsWholeAndAllowRefreshAfterCollapse() {
        var state by mutableStateOf(FeedState(filter = "unread", loading = false, accountId = 1))
        var refreshes = 0
        val user = Member(1, "fixture", "测试用户", "")
        compose.setContent {
            KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
                Box(Modifier.fillMaxSize()) {
                FeedScreen(state, user, { state = state.copy(filter = it) }, { refreshes++ }, {}, {}, {}, {}, {}, {}, Modifier.width(280.dp))
                }
            }
        }
        compose.onNodeWithText("新").assertExists()
        capture("home-tabs-dark-large-initial.png")
        for (label in listOf("未读", "最新", "新", "热门", "排行榜", "我的帖子", "已读", "书签", "类别")) {
            val node = compose.onNodeWithText(label).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val glyphs = label.indices.map(layout::getBoundingBox)
            println("Tab glyph bounds: $label size=${layout.size}, overflow=${layout.hasVisualOverflow}, glyphs=$glyphs")
            assertTrue("$label 字形不应超出布局", glyphs.all { it.left >= -1f && it.top >= -1f && it.right <= layout.size.width + 1f && it.bottom <= layout.size.height + 1f })
        }
        capture("home-tabs-dark-large-end.png")
        compose.onNodeWithText("未读").performScrollTo().performClick()
        compose.onNodeWithTag("feed-list-unread").performTouchInput { swipeUp(durationMillis = 600) }
        compose.onNodeWithText("未读").assertIsDisplayed()
        swipeFeedDown("feed-list-unread")
        swipeFeedDown("feed-list-unread")
        compose.runOnIdle { assertTrue("顶部展开后仍能下拉刷新", refreshes > 0) }
        capture("home-tabs-dark-large.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
