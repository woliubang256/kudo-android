package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReactionHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val post = Post(42, 1, Member(1, "author", "作者", ""), "", "", 0, false, null, null, true)
    private val history = ReactionHistory(listOf(
        ReactionHistoryGroup("heart", 80, listOf(ReactionHistoryUser(Member(0, "alice", "甲", ""), "heart"))),
        ReactionHistoryGroup("laughing", 20, listOf(ReactionHistoryUser(Member(0, "bob", "乙", ""), "laughing"))),
    ))

    @Test fun sheetFiltersRealUsersAndNavigatesWithoutWritingAReaction() {
        val visited = mutableListOf<String>()
        var reads = 0
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            TopicReactionHistoryHost(1, "topic", GlassMode.TRANSLUCENT, { _, _ -> reads++; history }, { visited += it }) {
                val open = LocalReactionHistory.current
                TextButton(onClick = { open(post) }) { Text("打开历史") }
            }
        } }
        compose.onNodeWithText("打开历史").performClick()
        compose.onNodeWithText("点赞与回应").assertDoesNotExist()
        compose.onNodeWithTag("liquid-action-sheet").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "点赞与回应"))
        compose.onNodeWithTag("reaction-history-filter-all").assert(hasText("全部 100"))
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-user-bob").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-filter-laughing").performClick()
        compose.onNodeWithTag("reaction-history-user-alice").assertDoesNotExist()
        compose.onNodeWithTag("reaction-history-user-bob").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-partial").assertIsDisplayed()
        capture("reaction-history-light.png")
        compose.onNodeWithTag("reaction-history-user-bob").performClick()
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("bob"), visited); assertEquals(1, reads) }
    }

    @Test fun sheetSwipesBetweenReactionTypes() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicReactionHistoryHost(1, "topic", GlassMode.TRANSLUCENT, { _, _ -> history }, {}) {
                val open = LocalReactionHistory.current
                TextButton(onClick = { open(post) }) { Text("打开历史") }
            }
        } }
        compose.onNodeWithText("打开历史").performClick()
        val pager = compose.onNodeWithTag("reaction-history-pager")
        pager.performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithTag("reaction-history-filter-heart").assertIsSelected()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-user-bob").assertIsNotDisplayed()
        pager.performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithTag("reaction-history-filter-laughing").assertIsSelected()
        compose.onNodeWithTag("reaction-history-user-bob").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsNotDisplayed()
        pager.performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithTag("reaction-history-filter-heart").assertIsSelected()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-user-bob").assertIsNotDisplayed()
        pager.performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithTag("reaction-history-filter-all").assertIsSelected()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-user-bob").assertIsDisplayed()
    }

    @Test fun sheetShowsLoadingRetryAndCanBeDraggedClosed() {
        val release = CompletableDeferred<Unit>()
        var requests = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicReactionHistoryHost(1, "topic", GlassMode.TRANSLUCENT, { _, _ ->
                requests++
                if (requests == 1) { release.await(); error("测试网络失败") }
                history
            }, {}) {
                val open = LocalReactionHistory.current
                TextButton(onClick = { open(post) }) { Text("打开历史") }
            }
        } }
        compose.onNodeWithText("打开历史").performClick()
        compose.onNodeWithTag("liquid-action-sheet").assertIsDisplayed()
        compose.runOnIdle { release.complete(Unit) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("reaction-history-retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reaction-history-retry").performClick()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        compose.onNodeWithTag("sheet-drag-handle").performTouchInput { swipeDown(startY = centerY, endY = centerY + 450f) }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun changingAccountOrScreenClosesTheOldHistory() {
        var account by mutableStateOf(1)
        var screen by mutableStateOf("topic1")
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicReactionHistoryHost(account, screen, GlassMode.TRANSLUCENT, { _, _ -> history }, {}) {
                val open = LocalReactionHistory.current
                TextButton(onClick = { open(post) }) { Text("打开历史") }
            }
        } }
        compose.onNodeWithText("打开历史").performClick()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        compose.runOnIdle { account = 2 }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithText("打开历史").performClick()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        compose.runOnIdle { screen = "topic2" }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
    }

    @Test fun singleReactionHidesFiltersButKeepsTheUserList() {
        val single = ReactionHistory(listOf(history.groups.first().copy(count = 1)))
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicReactionHistoryHost(1, "topic", GlassMode.TRANSLUCENT, { _, _ -> single }, {}) {
                val open = LocalReactionHistory.current
                TextButton(onClick = { open(post) }) { Text("打开历史") }
            }
        } }
        compose.onNodeWithText("打开历史").performClick()
        compose.onNodeWithTag("liquid-action-sheet").assertIsDisplayed()
        compose.onNodeWithText("点赞与回应").assertDoesNotExist()
        compose.onNodeWithTag("reaction-history-filters").assertDoesNotExist()
        compose.onNodeWithTag("reaction-history-filter-all").assertDoesNotExist()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        capture("reaction-history-single.png")
    }

    @Test fun shortReactionHistoryKeepsAdaptivePagerHeight() {
        val single = ReactionHistory(listOf(history.groups.first().copy(count = 1)))
        var density = 1f
        compose.setContent { KudoTheme(DisplaySettings()) {
            val currentDensity = LocalDensity.current.density
            SideEffect { density = currentDensity }
            TopicReactionHistoryHost(1, "topic", GlassMode.TRANSLUCENT, { _, _ -> single }, {}) {
                val open = LocalReactionHistory.current
                TextButton(onClick = { open(post) }) { Text("打开历史") }
            }
        } }
        compose.onNodeWithText("打开历史").performClick()
        val pagerHeight = compose.onNodeWithTag("reaction-history-pager").fetchSemanticsNode().boundsInRoot.height
        assertTrue("短名单不应使用 Pager 的最大高度", pagerHeight < 440f * density - 1f)
    }

    @Test fun emptyHistoryHidesFiltersButKeepsTheEmptyState() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicReactionHistoryHost(1, "topic", GlassMode.TRANSLUCENT, { _, _ -> ReactionHistory(emptyList()) }, {}) {
                val open = LocalReactionHistory.current
                TextButton(onClick = { open(post) }) { Text("打开历史") }
            }
        } }
        compose.onNodeWithText("打开历史").performClick()
        compose.onNodeWithText("点赞与回应").assertDoesNotExist()
        compose.onNodeWithTag("reaction-history-filters").assertDoesNotExist()
        compose.onNodeWithText("暂无可显示的记录").assertIsDisplayed()
    }

    @Test fun filtersScrollInOneRowOnNarrowLargeFontScreens() {
        val many = history.copy(groups = history.groups + listOf("+1", "clap", "eyes", "tada", "hugs").mapIndexed { index, reaction ->
            ReactionHistoryGroup(reaction, 1, listOf(ReactionHistoryUser(Member(0, "user$index", "用户$index", ""), reaction)))
        })
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.width(320.dp).fillMaxHeight()) {
                    TopicReactionHistoryHost(1, "topic", GlassMode.TRANSLUCENT, { _, _ -> many }, {}) {
                        val open = LocalReactionHistory.current
                        TextButton(onClick = { open(post) }) { Text("打开历史") }
                    }
                }
            }
        } }
        compose.onNodeWithText("打开历史").performClick()
        compose.onNodeWithTag("reaction-history-filter-all").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-filter-hugs").assertIsNotDisplayed()
        val filters = compose.onNodeWithTag("reaction-history-filters")
        val initial = compose.onNodeWithTag("reaction-history-filter-all").fetchSemanticsNode().boundsInRoot
        filters.performTouchInput { swipeLeft() }
        val scrolled = filters.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue("分类应支持横向手势滚动", scrolled > 0f)
        compose.onNodeWithTag("reaction-history-filter-hugs").performScrollTo().assertIsDisplayed()
        val last = compose.onNodeWithTag("reaction-history-filter-hugs").fetchSemanticsNode().boundsInRoot
        assertEquals("分类不能换行", initial.top, last.top, 1f)
        assertEquals("分类应保持同一行高度", initial.height, last.height, 1f)
        compose.onNodeWithTag("reaction-history-filter-hugs").performClick().assertIsSelected()
        compose.onNodeWithTag("reaction-history-user-user4").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-user-alice").assertDoesNotExist()
        compose.onNodeWithTag("liquid-action-sheet").assertIsDisplayed()
        capture("reaction-history-narrow.png")
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
