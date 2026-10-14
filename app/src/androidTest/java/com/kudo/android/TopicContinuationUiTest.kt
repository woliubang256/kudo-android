package com.kudo.android

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TopicContinuationUiTest {
    @get:Rule val compose = createComposeRule()
    private val first = Post(1, 1, Member(1, "author", "作者", ""), "<p>主帖</p>", "", 0, false, null, null, false)
    private val posts = listOf(first) + (2..13).map { first.copy(id = it, number = it, cooked = "<p>评论 $it</p>") }
    private val detail = TopicDetail(5, "连续阅读", 1, posts, (1..14).toList(), false)
    @Composable private fun Screen(state: TopicState, more: () -> Unit = {}, replies: (Post, Boolean) -> Unit = { _, _ -> }) {
        KudoTheme(DisplaySettings()) {
            TopicScreen(state, GlassMode.TRANSLUCENT, {}, {}, more, {}, {}, {}, {}, {}, {}, {}, {}, loadReplies = replies)
        }
    }

    @Test fun mainListWaitsForTheFooterAndStopsOnFailureUntilRetry() {
        var state by mutableStateOf(TopicState(detail, false))
        var requests = 0
        compose.setContent { Screen(state, more = { requests++; state = state.copy(loadingMore = true) }) }
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithTag("topic-posts").performScrollToIndex(posts.size + 1)
        compose.waitUntil { requests == 1 }
        compose.runOnIdle { state = state.copy(loadingMore = false, moreError = IllegalStateException("分页失败")) }
        compose.onNodeWithText("分页失败").assertExists()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("重新加载").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, requests); state = state.copy(loadingMore = false, moreError = null, detail = detail.copy(stream = posts.map(Post::id))) }
        compose.onNodeWithText("上拉继续加载楼层").assertDoesNotExist()
    }

    @Test fun shortPagesContinueUntilCompleteWithoutDuplicateRequests() {
        val short = detail.copy(posts = listOf(first), stream = listOf(1, 2, 3))
        var state by mutableStateOf(TopicState(short, false))
        var requests = 0
        compose.setContent { Screen(state, more = { requests++; state = state.copy(loadingMore = true) }) }
        compose.waitUntil { requests == 1 }
        compose.runOnIdle { state = state.copy(loadingMore = false, detail = short.copy(stream = listOf(1, 3))) }
        compose.waitUntil { requests == 2 }
        compose.runOnIdle { state = state.copy(loadingMore = false, detail = short.copy(stream = listOf(1))) }
        compose.onNodeWithText("已读完全部回复").assertExists()
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun aNewPageThatMovesTheFooterOffscreenDoesNotImmediatelyRequestAnother() {
        var state by mutableStateOf(TopicState(detail.copy(posts = listOf(first)), false))
        var requests = 0
        compose.setContent { Screen(state, more = { requests++; state = state.copy(loadingMore = true) }) }
        compose.waitUntil { requests == 1 }
        compose.runOnIdle { state = state.copy(loadingMore = false, detail = detail) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun openThreadLoadsAtBottomAndClosingStopsContinuation() {
        val root = first.copy(id = 2, number = 2, replyCount = 30)
        val children = (3..12).map { first.copy(id = it, number = it, replyTo = 2) }
        val topic = detail.copy(posts = listOf(first, root) + children, stream = (1..12).toList())
        var state by mutableStateOf(TopicState(topic, false))
        var requests = 0
        compose.setContent { Screen(state, replies = { _, preview -> if (!preview) {
            requests++; state = state.copy(replyLoads = mapOf(2 to ReplyLoad(mapOf(2 to ReplyCursor(12)), loading = true)))
        } }) }
        compose.onNodeWithText("查看更多回复").performScrollTo().performClick()
        compose.waitUntil { requests == 1 }
        compose.runOnIdle { state = state.copy(replyLoads = mapOf(2 to ReplyLoad(mapOf(2 to ReplyCursor(12))))) }
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithTag("thread-posts").performScrollToIndex(children.size)
        compose.waitUntil { requests == 2 }
        compose.onNodeWithTag("sheet-drag-handle").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(0f, 650f), durationMillis = 600)
        }
        compose.onNodeWithTag("thread-posts").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(replyLoads = mapOf(2 to ReplyLoad(mapOf(2 to ReplyCursor(20))))) }
        compose.runOnIdle { assertEquals(2, requests) }
    }
}
