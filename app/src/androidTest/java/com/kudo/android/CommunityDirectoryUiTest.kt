package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.account.*
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CommunityDirectoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val categories = (0 until 40).map { Category(100 + it, "类别$it", "category-$it", "32c3c3", "服务器分类介绍", 1, "code") }
    private val tags = listOf("人工智能", "公告", "原创", "快问快答", "抽奖", "精华神帖", "集中帖", "特别特别长的标签名称").map { DirectoryTag(TopicTag(it)) }
    private fun state() = CommunityDirectoryState(accountId = 7,
        directory = CommunityDirectory(7, "fixture", categories.take(2), categories, tags, true))

    @Test fun swipingDirectoryPreservesExpandedCategoryPositionAndDoesNotPreloadAllTags() {
        var loads = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            CommunityDirectoryScreen(state(), {}, {}, { loads++ }, {}, {}, {})
        } }
        compose.onNodeWithText("所有类别").performClick()
        compose.onNodeWithTag("category-directory-list").performScrollToIndex(15)
        compose.onNodeWithTag("directory-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithText("标签").assertIsSelected()
        compose.onNodeWithTag("directory-tag-公告").assertIsDisplayed()
        compose.onNodeWithTag("directory-pager").performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithText("类别").assertIsSelected()
        compose.onNodeWithText("类别15").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, loads) }
    }

    @Test fun embeddedDirectoryConsumesInnerSwipesAndReturnsEdgeSwipesToHome() {
        var feed by mutableStateOf(com.kudo.android.ui.feed.FeedState(filter = "latest", loading = false))
        compose.setContent { KudoTheme(DisplaySettings()) {
            com.kudo.android.ui.feed.FeedScreen(feed, null, { feed = feed.copy(filter = it) }, {}, {}, {}, {}, {}, {}, {},
                categoryContent = { CommunityDirectoryScreen(state(), {}, {}, {}, {}, {}, {}, inHome = true) })
        } }
        compose.onNodeWithText("类别").performClick()
        compose.onNodeWithTag("directory-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithText("标签").assertIsSelected()
        compose.onNodeWithTag("directory-tag-公告").assertIsDisplayed()
        compose.onNodeWithTag("directory-pager").performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithTag("category-directory-list").assertIsDisplayed()
        compose.onNodeWithTag("directory-pager").performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithText("排行榜").assertIsSelected()
    }

    @Test fun directoryTabsUseTheSelectedMaterialBar() {
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, glass = GlassMode.LIQUID)) {
            CommunityDirectoryScreen(state(), {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("directory-toolbar-material").assertIsDisplayed()
        compose.onNodeWithText("类别").assertIsSelected()
        compose.onNodeWithText("标签").performClick().assertIsSelected()
    }

    @Test fun directoryMaterialSurvivesSwitchingIntoTheDirectory() {
        var directoryVisible by mutableStateOf(false)
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.DARK, glass = GlassMode.LIQUID))
        var inHome by mutableStateOf(false)
        compose.setContent { KudoTheme(settings) {
            if (directoryVisible) CommunityDirectoryScreen(state(), {}, {}, {}, {}, {}, {}, inHome)
            else androidx.compose.foundation.layout.Box(Modifier.fillMaxSize())
        } }
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            for (glass in GlassMode.entries) {
                for (embedded in listOf(false, true)) {
                    compose.runOnIdle { settings = settings.copy(theme = theme, glass = glass); inHome = embedded; directoryVisible = true }
                    compose.onNodeWithTag("directory-toolbar-material").assertIsDisplayed()
                        .assert(!hasAnyAncestor(hasTestTag("directory-content-backdrop")))
                    val toolbar = compose.onNodeWithTag("directory-toolbar-material").fetchSemanticsNode().boundsInRoot
                    val category = compose.onNodeWithText("类别0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue(category.top >= toolbar.bottom)
                    compose.onNodeWithText("标签").performClick().assertIsSelected()
                    compose.onNodeWithTag("directory-tag-公告").assertIsDisplayed()
                    compose.onRoot().captureToImage()
                    compose.runOnIdle { directoryVisible = false }
                }
            }
        }
    }

    @Test fun tabsKeepSidebarMembershipAndTagsUseThreeColumnsInServerOrder() {
        val clicks = mutableListOf<String>()
        compose.setContent { KudoTheme(DisplaySettings()) {
            CommunityDirectoryScreen(state(), {}, {}, {}, {}, { clicks.add(it.name) }, { clicks.add(it.tag.name) })
        } }
        compose.onNodeWithText("类别0").assertIsDisplayed()
        compose.onNodeWithText("类别2").assertDoesNotExist()
        capture("directory-categories-light.png")
        compose.onNodeWithText("所有类别").performClick()
        compose.onNodeWithText("类别2").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("返回侧栏目录").performClick()
        compose.onNodeWithText("标签").performClick()
        val bounds = tags.take(4).map { compose.onNodeWithTag("directory-tag-${it.tag.name}").fetchSemanticsNode().boundsInRoot }
        assertEquals(bounds[0].top, bounds[1].top, 1f)
        assertEquals(bounds[0].top, bounds[2].top, 1f)
        assertTrue(bounds[0].left < bounds[1].left && bounds[1].left < bounds[2].left)
        assertTrue(bounds[3].top > bounds[0].bottom)
        compose.onNodeWithTag("directory-tag-公告").performClick()
        assertEquals(listOf("类别2", "公告"), clicks)
        capture("directory-tags-light.png")
    }

    @Test fun fullTagGroupsKeepGridOrderAndRetryIndependentlyOfCategories() {
        var value by mutableStateOf(state())
        var loads = 0
        var retries = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            CommunityDirectoryScreen(value, {}, {}, {
                loads++; value = value.copy(tagsError = ApiFailure(FailureKind.NETWORK, "标签加载失败"))
            }, {
                retries++; value = value.copy(tagsError = null, tagGroups = listOf(
                    DirectoryTagGroup("服务器第二组", listOf(DirectoryTag(TopicTag("乙标签")), DirectoryTag(TopicTag("甲标签")))),
                    DirectoryTagGroup("服务器第一组", listOf(DirectoryTag(TopicTag("另一标签"))))))
            }, {}, {})
        } }
        compose.onNodeWithText("标签").performClick()
        compose.onNodeWithText("所有标签").performClick()
        compose.onNodeWithText("重新加载").performClick()
        assertEquals(1, loads); assertEquals(1, retries)
        val second = compose.onNodeWithText("服务器第二组").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("服务器第一组").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("directory-tag-乙标签").fetchSemanticsNode().boundsInRoot
        val a = compose.onNodeWithTag("directory-tag-甲标签").fetchSemanticsNode().boundsInRoot
        assertTrue(second.top < first.top); assertTrue(b.left < a.left); assertEquals(b.top, a.top, 1f)
        compose.onNodeWithContentDescription("返回侧栏目录").performClick()
        compose.onNodeWithTag("directory-tag-人工智能").assertIsDisplayed()
        compose.onNodeWithText("类别").performClick()
        compose.onNodeWithText("类别0").assertIsDisplayed()
    }

    @Test fun categoryAndTagDetailsReturnToTheSameTabModeAndScrollPosition() {
        lateinit var nav: NavHostController
        val long = state().copy(directory = state().directory!!.copy(categories = categories,
            tags = (0 until 60).map { DirectoryTag(TopicTag("导航标签$it")) }))
        compose.setContent { KudoTheme(DisplaySettings()) {
            nav = rememberNavController()
            NavHost(nav, "directory") {
                composable("directory") { CommunityDirectoryScreen(long, {}, {}, {}, {}, { nav.navigate("detail") }, { nav.navigate("detail") }) }
                composable("detail") { androidx.compose.material3.Text("详情夹具") }
            }
        } }
        compose.onNodeWithTag("category-directory-list").performScrollToIndex(18)
        val category = compose.onNodeWithText("类别18")
        val categoryTop = category.fetchSemanticsNode().boundsInRoot.top
        category.performClick()
        compose.onNodeWithText("详情夹具").assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        assertEquals(categoryTop, compose.onNodeWithText("类别18").fetchSemanticsNode().boundsInRoot.top, 1.5f)
        compose.onNodeWithText("标签").performClick()
        compose.onNodeWithTag("directory-tag-grid").performScrollToIndex(24)
        val tag = compose.onNodeWithTag("directory-tag-导航标签24")
        val tagTop = tag.fetchSemanticsNode().boundsInRoot.top
        tag.performClick()
        compose.onNodeWithText("详情夹具").assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        assertEquals(tagTop, compose.onNodeWithTag("directory-tag-导航标签24").fetchSemanticsNode().boundsInRoot.top, 1.5f)
        compose.onNodeWithText("类别").performClick()
        assertEquals(categoryTop, compose.onNodeWithText("类别18").fetchSemanticsNode().boundsInRoot.top, 1.5f)
    }

    @Test fun narrowDarkLargeTextStaysInMultipleColumnsAndWideWindowAddsColumns() {
        var wide by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(if (wide) density.density / 2 else density.density, density.fontScale)) {
                KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
                    Box(if (wide) Modifier.fillMaxSize() else Modifier.width(280.dp).fillMaxHeight()) {
                        CommunityDirectoryScreen(state(), {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("标签").performClick()
        val first = compose.onNodeWithTag("directory-tag-人工智能").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("directory-tag-公告").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("directory-tag-原创").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f); assertTrue(third.top > first.bottom)
        compose.onNodeWithTag("directory-tag-grid").performScrollToIndex(tags.lastIndex)
        compose.onNodeWithTag("directory-tag-特别特别长的标签名称").assertIsDisplayed().assertHasClickAction()
        capture("directory-tags-dark-narrow-large.png")
        compose.onNodeWithTag("directory-tag-grid").performScrollToIndex(0)
        compose.runOnIdle { wide = true }
        val row = tags.take(4).map { compose.onNodeWithTag("directory-tag-${it.tag.name}").fetchSemanticsNode().boundsInRoot }
        row.forEach { assertEquals(row.first().top, it.top, 1f) }
        capture("directory-tags-wide-large.png")
    }

    @Test fun allDirectoriesKeepModeAndScrollPositionAfterDetails() {
        lateinit var nav: NavHostController
        val value = state().copy(tagGroups = listOf(DirectoryTagGroup("", (0 until 60).map {
            DirectoryTag(TopicTag("完整标签$it"))
        })))
        compose.setContent { KudoTheme(DisplaySettings()) {
            nav = rememberNavController()
            NavHost(nav, "directory") {
                composable("directory") { CommunityDirectoryScreen(value, {}, {}, {}, {}, { nav.navigate("detail") }, { nav.navigate("detail") }) }
                composable("detail") { androidx.compose.material3.Text("详情夹具") }
            }
        } }
        compose.onNodeWithText("所有类别").performClick()
        compose.onNodeWithTag("category-directory-list").performScrollToIndex(18)
        val category = compose.onNodeWithText("类别18")
        val categoryTop = category.fetchSemanticsNode().boundsInRoot.top
        category.performClick()
        compose.onNodeWithText("详情夹具").assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("所有类别").assertIsDisplayed()
        assertEquals(categoryTop, compose.onNodeWithText("类别18").fetchSemanticsNode().boundsInRoot.top, 1.5f)
        compose.onNodeWithText("标签").performClick()
        compose.onNodeWithText("所有标签").performClick()
        compose.onNodeWithTag("directory-tag-grid").performScrollToIndex(24)
        val tag = compose.onNodeWithTag("directory-tag-完整标签24")
        val tagTop = tag.fetchSemanticsNode().boundsInRoot.top
        tag.performClick()
        compose.onNodeWithText("详情夹具").assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("所有标签").assertIsDisplayed()
        assertEquals(tagTop, compose.onNodeWithTag("directory-tag-完整标签24").fetchSemanticsNode().boundsInRoot.top, 1.5f)
        compose.onNodeWithText("类别").performClick()
        compose.onNodeWithText("所有类别").assertIsDisplayed()
        assertEquals(categoryTop, compose.onNodeWithText("类别18").fetchSemanticsNode().boundsInRoot.top, 1.5f)
        compose.onNodeWithContentDescription("返回侧栏目录").performClick()
        compose.onNodeWithText("类别0").assertIsDisplayed()
        compose.onNodeWithText("类别2").assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350) // Let the released tab ripple finish before visual review.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
