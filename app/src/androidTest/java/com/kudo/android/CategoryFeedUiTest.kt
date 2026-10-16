package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.feed.*
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CategoryFeedUiTest {
    @get:Rule val compose = createComposeRule()
    private val category = Category(4, "开发调优", "develop", "32c3c3", "此版块包含开发、测试、调试、部署、优化、安全等方面的内容。", 1, "code")
    private fun page(filter: String = "latest", count: Int = 40) = FeedState(filter = filter, loading = false,
        categories = listOf(category), topics = (1..count).map {
            Topic(it, "$filter 话题 $it", "验证分类滚动和独立分页的内容", null, 4, emptyList(), 0, 0, 0, "", "")
        })

    @Test fun categoryHeaderShowsCatalogIdentityAndDescription() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            FeedScreen(page(), null, {}, {}, {}, {}, {}, {}, {}, {}, category = category)
        } }
        compose.onNodeWithText(category.description).assertIsDisplayed()
        compose.onNodeWithTag("category-expanded-icon").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        compose.onNodeWithContentDescription("搜索").assertIsDisplayed()
        capture("category-expanded.png")
    }

    @Test fun yellowPublishButtonSupportsEveryMaterialAndOnlyOpensCurrentCategoryForMembers() {
        var user by mutableStateOf<Member?>(null)
        var mode by mutableStateOf(GlassMode.LIQUID)
        var logins = 0
        val opened = mutableListOf<Int>()
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            Box(Modifier.fillMaxSize()) {
                FeedScreen(page().copy(accountId = user?.id), user, {}, {}, {}, {}, {}, {}, { logins++ }, {},
                    Modifier.width(280.dp), category = category, createTopic = { opened += it }, glass = mode)
            }
        } }
        val button = compose.onNodeWithTag("category-publish")
        button.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, logins); assertTrue(opened.isEmpty()); user = Member(7, "seven", "七", "") }
        for (material in GlassMode.entries) {
            compose.runOnIdle { mode = material }
            val pixels = button.captureToImage().toPixelMap()
            val background = pixels[pixels.width / 2, pixels.height / 4]
            assertTrue("$material 发布背景保持黄色：$background", background.red > .75f && background.green > .4f && background.blue < .45f)
            button.performTouchInput { click() }
            capture("category-publish-${material.name.lowercase()}.png")
        }
        compose.runOnIdle { assertEquals(listOf(4, 4, 4), opened); mode = GlassMode.LIQUID }
        val icon = compose.onNodeWithTag("category-publish-icon", useUnmergedTree = true)
        val resting = icon.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        assertTrue("按压时液态图标放大", icon.fetchSemanticsNode().boundsInRoot.height > resting.height)
        button.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(900)
        assertEquals(resting.height, icon.fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.runOnIdle { assertEquals(3, opened.size) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun swipesAndClicksShareSelectionAndRememberPositionsAcrossDistantPages() {
        val pages = listOf("latest", "hot", "top").associateWith { page(it) }
        var state by mutableStateOf(pages.getValue("latest"))
        compose.setContent { KudoTheme(DisplaySettings()) {
            FeedScreen(state, null, { state = pages.getValue(it) }, {}, {}, {}, {}, {}, {}, {}, category = category, pages = pages)
        } }
        compose.onNodeWithTag("category-header").performTouchInput { swipeUp(durationMillis = 600) }
        compose.onNodeWithTag("feed-list-latest").performScrollToIndex(10)
        val pinned = compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("category-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithText("热门").assertIsSelected()
        compose.onNodeWithText("hot 话题 1").assertIsDisplayed()
        compose.onNodeWithTag("category-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithText("排行榜").assertIsSelected()
        compose.onNodeWithTag("feed-list-top").performScrollToIndex(14)
        compose.onNodeWithText("最新").performClick().assertIsSelected()
        compose.onNodeWithText("latest 话题 11").assertIsDisplayed()
        assertEquals(pinned, compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.onNodeWithText("排行榜").performClick().assertIsSelected()
        compose.onNodeWithText("top 话题 15").assertIsDisplayed()
        compose.onNodeWithTag("category-pager").performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithText("热门").assertIsSelected()
        capture("category-pager-hot.png")
    }

    @Test fun loggedInPagesIncludePersonalFiltersAndLogoutRemovesTheirContent() {
        var user by mutableStateOf<Member?>(Member(7, "seven", "七", ""))
        var state by mutableStateOf(page().copy(accountId = 7))
        compose.setContent { KudoTheme(DisplaySettings()) {
            FeedScreen(state, user, { state = page(it).copy(accountId = user?.id) }, {}, {}, {}, {}, {}, {}, {},
                category = category, pages = mapOf("unread" to page("unread").copy(accountId = 7)))
        } }
        compose.onNodeWithText("最新").assertIsSelected()
        compose.onNodeWithTag("category-pager").performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithText("未读").assertIsSelected()
        compose.onNodeWithText("类别").assertDoesNotExist()
        for (label in listOf("最新", "新", "热门", "排行榜", "我的帖子", "已读", "书签"))
            compose.onNodeWithText(label).performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { user = null; state = page() }
        compose.onNodeWithText("最新").assertIsSelected()
        listOf("未读", "新", "我的帖子", "已读", "书签").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("bookmarks 话题 1").assertDoesNotExist()
    }

    @Test fun listCollapsesFirstThenReturnsToTopBeforeExpandingAndUsesHeaderMaterial() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            FeedScreen(page(), null, {}, {}, {}, {}, {}, {}, {}, {}, category = category)
        } }
        val initial = compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top
        val toolbar = compose.onNodeWithTag("category-toolbar").captureToImage().toPixelMap()
        val background = toolbar[toolbar.width / 2, toolbar.height / 2]
        val list = compose.onNodeWithTag("feed-list-latest")
        list.performTouchInput { swipe(center, center.copy(y = center.y - 90f), durationMillis = 1000) }
        assertTrue(compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top < initial - 50f)
        compose.onNodeWithText("latest 话题 1").assertIsDisplayed()
        repeat(3) { list.performTouchInput { swipeUp(durationMillis = 600) } }
        val pinned = compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("category-toolbar-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("category-toolbar-name").assertTextEquals(category.name)
        compose.onNodeWithText(category.description).assertIsNotDisplayed()
        val collapsed = compose.onNodeWithTag("category-toolbar").captureToImage().toPixelMap()
        assertNotEquals("吸顶后从分类色切换为设置材质", background, collapsed[collapsed.width - 3, collapsed.height / 2])
        compose.onNodeWithTag("category-header").assert(!hasAnyAncestor(hasTestTag("category-pager")))
        capture("category-collapsed.png")
        list.performTouchInput { swipe(center, center.copy(y = center.y + 90f), durationMillis = 1000) }
        assertEquals(pinned, compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top, 2f)
        repeat(8) { list.performTouchInput { swipeDown(durationMillis = 600) } }
        assertEquals(initial, compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test fun listFlingCollapsesHeaderAndShortPageExpandsBeforeRefreshing() {
        var refreshes = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            FeedScreen(page(count = 1), null, {}, { refreshes++ }, {}, {}, {}, {}, {}, {}, category = category)
        } }
        val list = compose.onNodeWithTag("feed-list-latest")
        val initial = compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top
        list.performTouchInput { swipe(center.copy(y = height * .8f), center.copy(y = height * .65f), durationMillis = 80) }
        compose.onNodeWithTag("category-toolbar-icon", useUnmergedTree = true).assertIsDisplayed()
        list.performTouchInput { swipe(center, center.copy(y = height - 10f), durationMillis = 800) }
        assertEquals(initial, compose.onNodeWithText("最新").fetchSemanticsNode().boundsInRoot.top, 2f)
        list.performTouchInput { swipe(center, center.copy(y = height - 10f), durationMillis = 800) }
        compose.runOnIdle { assertTrue("顶部展开后可以继续下拉刷新", refreshes > 0) }
    }

    @Test fun metadataCanArriveLaterAndDarkNarrowLargeTextKeepsActions() {
        var current by mutableStateOf(category.copy(name = "社区分类", color = "bad-color", description = "", icon = "future-icon"))
        var backs = 0
        var searches = 0
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            Box(Modifier.fillMaxSize()) { FeedScreen(page(), null, {}, {}, {}, {}, {}, { searches++ }, {}, {},
                Modifier.width(280.dp), category = current, back = { backs++ }, listStyle = TopicListStyle.TITLE) }
        } }
        compose.onNodeWithText("社区分类").assertIsDisplayed()
        compose.runOnIdle { current = category }
        compose.onNodeWithText(category.description).assertIsDisplayed()
        capture("category-dark-narrow-expanded.png")
        compose.onNodeWithTag("feed-list-latest").performTouchInput { swipeUp(durationMillis = 800) }
        compose.onNodeWithTag("category-toolbar-name").assertIsDisplayed()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertEquals(1, backs); assertEquals(1, searches) }
        capture("category-dark-narrow-collapsed.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
