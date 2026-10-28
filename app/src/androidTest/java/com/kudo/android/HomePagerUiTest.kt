package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
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

class HomePagerUiTest {
    @get:Rule val compose = createComposeRule()
    private fun page(filter: String) = FeedState(filter = filter, loading = false, topics = (1..30).map {
        Topic(it, "$filter 话题 $it", "横滑保留阅读位置的测试内容", null, 1, emptyList(), 0, 0, 0, "", "")
    })
    @Test fun swipingAndClickingShareSelectionAndPreserveEachListPosition() {
        val pages = listOf("latest", "hot", "top").associateWith(::page)
        var state by mutableStateOf(pages.getValue("latest"))
        var categories = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            FeedScreen(state, null, { state = pages.getValue(it) }, {}, {}, {}, {}, {}, {}, { categories++ }, pages = pages)
        } }
        capture("home-pager-latest.png")
        compose.onNodeWithTag("feed-list-latest").performScrollToIndex(10)
        compose.onNodeWithTag("home-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithText("热门").assertIsSelected()
        compose.onNodeWithText("hot 话题 1").assertIsDisplayed()
        capture("home-pager-hot.png")
        compose.onNodeWithTag("home-pager").performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithText("最新").assertIsSelected()
        compose.onNodeWithText("latest 话题 11").assertIsDisplayed()
        compose.onNodeWithText("排行榜").performClick()
        compose.onNodeWithText("排行榜").assertIsSelected()
        compose.onNodeWithText("类别").performClick()
        compose.onNodeWithText("类别").assertIsSelected()
        compose.onNodeWithText("社区分类").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, categories) }
    }
    @Test fun logoutRemovesPersonalPagesAndCachedPrivateTopics() {
        var user by mutableStateOf<Member?>(Member(1, "one", "用户一", ""))
        var state by mutableStateOf(page("unread").copy(accountId = 1))
        compose.setContent { KudoTheme(DisplaySettings()) {
            FeedScreen(state, user, {}, {}, {}, {}, {}, {}, {}, {}, pages = mapOf("bookmarks" to page("bookmarks").copy(accountId = 1)))
        } }
        compose.onNodeWithText("未读").assertIsSelected()
        compose.runOnIdle { user = null; state = page("latest") }
        compose.onNodeWithText("最新").assertIsSelected()
        compose.onNodeWithText("未读").assertDoesNotExist()
        compose.onNodeWithText("书签").assertDoesNotExist()
        compose.onNodeWithText("unread 话题 1").assertDoesNotExist()
    }

    @Test fun customTabsKeepTheirSelectionAndReadingPositionAcrossReorderAndRemoval() {
        val category = HomeTab.category(Category(4, "开发调优", "dev", "", "", 0))
        val tag = HomeTab.tag(TopicTag("公告", "444-tag", id = 444))
        var tabs by mutableStateOf(listOf(HomeTab.filter(FeedFilter.LATEST), category, tag))
        val pages = tabs.associate { it.key to page(it.key) }
        var state by mutableStateOf(pages.getValue("latest"))
        var edits = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            FeedScreen(state, null, { state = pages.getValue(it) }, {}, {}, {}, {}, {}, {}, {},
                pages = pages, homeTabs = tabs, editTabs = { edits++ })
        } }
        compose.onNodeWithText("开发调优").performClick().assertIsSelected()
        compose.onNodeWithTag("feed-list-${category.key}").performScrollToIndex(10)
        compose.runOnIdle { tabs = listOf(tag, category, HomeTab.filter(FeedFilter.LATEST)) }
        compose.onNodeWithText("开发调优").assertIsSelected()
        compose.onNodeWithText("${category.key} 话题 11").assertIsDisplayed()
        compose.onNodeWithTag("home-pager").performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithText("公告").assertIsSelected()
        compose.onNodeWithTag("home-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithText("${category.key} 话题 11").assertIsDisplayed()
        compose.onNodeWithTag("home-tabs-edit").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, edits); tabs = listOf(tag); state = pages.getValue(tag.key) }
        compose.onNodeWithText("公告").assertIsSelected()
        compose.onNodeWithText("开发调优").assertDoesNotExist()
    }

    @Test fun editButtonStaysFixedWhenTabsScrollAndTheSearchRowCollapses() {
        val tabs = defaultHomeTabs(true)
        val user = Member(7, "tester", "测试用户", "")
        val pages = tabs.filter { it.type != HomeTabType.DIRECTORY }.associate { it.key to page(it.key).copy(accountId = 7) }
        var state by mutableStateOf(pages.getValue("unread"))
        var edits = 0
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            Box(Modifier.width(280.dp).fillMaxHeight()) {
                FeedScreen(state, user, { pages[it]?.let { selected -> state = selected } }, {}, {}, {}, {}, {}, {}, {},
                    pages = pages, homeTabs = tabs, editTabs = { edits++ })
            }
        } }
        val original = compose.onNodeWithTag("home-tabs-edit").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("书签").performScrollTo().performClick()
        compose.onNodeWithText("书签").assertIsSelected()
        assertEquals(original.left, compose.onNodeWithTag("home-tabs-edit").fetchSemanticsNode().boundsInRoot.left, 1f)
        compose.onNodeWithTag("feed-list-bookmarks").performTouchInput { swipeUp(durationMillis = 800) }
        compose.onNodeWithTag("home-search").assertIsNotDisplayed()
        compose.onNodeWithTag("home-tabs-edit").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, edits) }
        capture("home-tabs-edit-pinned-dark-narrow.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
