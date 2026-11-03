package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class PostReplyRecipientUiTest {
    @get:Rule val compose = createComposeRule()
    private val alice = Member(1, "alice", "原评论作者", "", avatarDetailsLoaded = true)
    private val bob = Member(2, "bob", "回复作者", "", avatarDetailsLoaded = true)
    private fun post(number: Int, member: Member = bob, parent: Int? = null) =
        Post(number, number, member, "<p>正文$number</p>", "", 0, false, null, parent, true)

    @Test fun timelineDisplaysTheActualReplyRecipient() {
        val parent = post(2, alice)
        val child = post(3, parent = 2)
        val detail = TopicDetail(5, "话题", 1, listOf(parent, child), listOf(2, 3), false)
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicPost(child, detail, TopicState(detail, false), true, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("回复原评论作者，第 2 楼").assertIsDisplayed()
    }

    @Test fun recipientStaysToTheRightOfTheAuthorInTheSameHeaderRow() {
        val child = post(11).copy(replyTo = 10, replyToUser = alice)
        val detail = TopicDetail(5, "话题", 1, listOf(child), listOf(11), false)
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            TopicPost(child, detail, TopicState(detail, false), true, {}, {}, {}, {}, {}, {}, {})
        } }
        assertInlineRecipient(child, bob.displayName)
        capture("reply-recipient-inline-light.png")
    }

    @Test fun longRecipientKeepsTheAuthorDeviceAndActionsVisibleOnNarrowLargeText() {
        val recipient = alice.copy(name = "这是很长的被回复用户昵称")
        val child = post(12).copy(replyTo = 10, replyToUser = recipient,
            device = PostDevice("iPhone 14 Pro Max", "phone"))
        val detail = TopicDetail(5, "话题", 1, listOf(child), listOf(12), false)
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.width(320.dp)) {
                    TopicPost(child, detail, TopicState(detail, false), true, {}, {}, {}, {}, {}, {}, {})
                }
            }
        } }
        assertInlineRecipient(child, bob.displayName)
        compose.onNodeWithContentDescription("查看第 12 楼的设备").assertIsDisplayed()
        compose.onNodeWithContentDescription("更多操作").assertIsDisplayed()
        capture("reply-recipient-inline-dark-large.png")
    }

    private fun assertInlineRecipient(post: Post, authorName: String) {
        val author = compose.onNodeWithText(authorName).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val recipient = compose.onNodeWithTag("post-reply-recipient-${post.number}")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithContentDescription("更多操作").fetchSemanticsNode().boundsInRoot
        assertTrue("回复对象应在作者右侧", recipient.left >= author.right)
        assertTrue("回复对象与作者应在同一行", author.center.y in recipient.top..recipient.bottom)
        assertTrue("回复对象不能挤占更多按钮", recipient.right <= more.left)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun missingReplyRecipientFallsBackToTheActualFloorOnNarrowLargeText() {
        val child = post(88, parent = 25)
        val detail = TopicDetail(5, "话题", 1, listOf(child), listOf(88), false)
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.width(320.dp)) {
                    TopicPost(child, detail, TopicState(detail, false), true, {}, {}, {}, {}, {}, {}, {})
                }
            }
        } }
        compose.onNodeWithContentDescription("回复第 25 楼").assertIsDisplayed()
        compose.onNodeWithText("回复作者").assertIsDisplayed()
    }

    @Test fun serverRecipientSurvivesMissingParentAndClickUsesFloorNavigation() {
        val child = post(88, parent = 25).copy(replyToUser = alice)
        val detail = TopicDetail(5, "话题", 1, listOf(child), listOf(88), false)
        var requested = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicScreen(TopicState(detail, false), GlassMode.TRANSLUCENT, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                readingEnabled = false, jumpTo = { requested = it })
        } }
        compose.onNodeWithContentDescription("回复原评论作者，第 25 楼").performClick()
        compose.runOnIdle { assertEquals(25, requested) }
    }

    @Test fun loadedRecipientActuallyScrollsToTheParentOnEveryClick() {
        val parent = post(4, alice)
        val child = post(30, parent = 4).copy(replyToUser = alice)
        val loaded = (1..30).map { when (it) { 4 -> parent; 30 -> child; else -> post(it) } }
        val detail = TopicDetail(5, "话题", 1, loaded, loaded.map(Post::id), false, highestPostNumber = 30)
        var requests = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicScreen(TopicState(detail, false), GlassMode.TRANSLUCENT, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                readingEnabled = false, jumpTo = { requests++ })
        } }
        repeat(2) {
            compose.onNodeWithTag("topic-posts").performScrollToIndex(30)
            compose.onNodeWithTag("post-reply-recipient-30").performClick()
            awaitFloor(4, 30)
            compose.onNodeWithTag("post-4").assertIsDisplayed()
            compose.onNodeWithText("4 / 30").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals("已加载目标应直接定位，不重新请求", 0, requests) }
    }

    @Test fun cachedRecipientStillLoadsItsMainWindowAndScrollsToTheActualFloor() {
        val parent = post(4, alice)
        val child = post(40, parent = 4).copy(replyToUser = alice)
        val loaded = (30..50).map { if (it == 40) child else post(it) }
        val detail = TopicDetail(5, "话题", 1, loaded, (1..50).toList(), false, highestPostNumber = 50)
        var state by mutableStateOf(TopicState(detail, false, targetNumber = 40, targetRevision = 1, related = listOf(parent)))
        var requested = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicScreen(state, GlassMode.TRANSLUCENT, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                readingEnabled = false, jumpTo = { number ->
                    requested = number
                    state = state.copy(detail = detail.copy(posts = (1..24).map { if (it == 4) parent else post(it) }),
                        related = loaded, targetNumber = number, targetRevision = state.targetRevision + 1)
                })
        } }
        compose.onNodeWithTag("post-reply-recipient-40").performClick()
        val topLimit = with(compose.density) { 100.dp.toPx() }
        compose.waitUntil(5_000) {
            val viewport = compose.onNodeWithTag("topic-posts").fetchSemanticsNode().boundsInRoot
            compose.onAllNodesWithTag("post-4").fetchSemanticsNodes().any {
                it.boundsInRoot.top in viewport.top..(viewport.top + topLimit)
            }
        }
        compose.onNodeWithTag("post-4").assertIsDisplayed()
        compose.runOnIdle { assertEquals(4, requested) }
        capture("reply-recipient-cross-page-arrival.png")
        awaitFloor(4, 50)
        compose.onNodeWithText("4 / 50").assertIsDisplayed()
    }

    private fun awaitFloor(number: Int, highest: Int) {
        // The scroll starts after the next layout; the click itself can become idle before that delay ends.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("$number / $highest").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun replyToOriginalUsesOriginalAuthorButInvalidTargetsNeverShowARecipient() {
        var target by androidx.compose.runtime.mutableStateOf<Int?>(1)
        val base = post(3)
        val detail = TopicDetail(5, "话题", 1, listOf(base), listOf(3), false, author = alice)
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicPost(base.copy(replyTo = target), detail, TopicState(detail, false), true, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("回复原评论作者，第 1 楼").assertIsDisplayed()
        for (invalid in listOf(null, 0, -1, 3, 4)) {
            compose.runOnIdle { target = invalid }
            compose.onNodeWithTag("post-reply-recipient-3").assertDoesNotExist()
        }
    }
}
