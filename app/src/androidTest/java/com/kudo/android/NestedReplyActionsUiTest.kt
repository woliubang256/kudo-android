package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NestedReplyActionsUiTest {
    @get:Rule val compose = createComposeRule()
    private val author = Member(2, "root", "原评论作者", "", avatarDetailsLoaded = true)
    private val root = Post(20, 2, author, "<p>原评论正文</p>", "2026-10-04T00:00:00Z", 2, false, null, 1, true)
    private val direct = root.copy(id = 30, number = 3, author = author.copy(id = 3, username = "first", name = "乙"),
        cooked = "<p>直接回复正文</p>", replyTo = 2, replyCount = 1)
    private val nested = root.copy(id = 40, number = 4, author = author.copy(id = 4, username = "second", name = "丙"),
        cooked = "<p>回复乙的正文</p>", replyTo = 3)
    private val posts = listOf(root.copy(id = 10, number = 1, replyTo = null), root, direct, nested)
    private val detail = TopicDetail(5, "楼中楼操作", 1, posts, posts.map(Post::id), false)
    private val initial = TopicState(detail, false, viewerId = 99,
        replyLoads = mapOf(root.id to ReplyLoad(cursors = mapOf(direct.id to ReplyCursor(4, true)))))

    @Composable private fun Screen(state: TopicState = initial, like: (Post) -> Unit = {}, reply: (Post?) -> Unit = {},
        react: (Post, String) -> Unit = { _, _ -> }) {
        TopicScreen(state, GlassMode.TRANSLUCENT, {}, {}, {}, {}, like, {}, reply, {}, {}, {}, {}, react = react)
    }
    private fun open() = compose.onNodeWithText("查看 2 条回复").performScrollTo().performClick()
    private fun showFloor(number: Int) = compose.onNodeWithTag("thread-posts").performScrollToNode(hasTestTag("thread-preview-post-$number"))

    @Test fun discussionDoesNotRepeatTheOriginalComment() {
        compose.setContent { KudoTheme(DisplaySettings()) { Screen() } }
        open()
        compose.onNodeWithTag("thread-preview-post-2").assertDoesNotExist()
        compose.onNodeWithTag("thread-preview-post-3").assertIsDisplayed()
    }

    @Test fun discussionShowsTwoActionsAndFloorWithJumpIconForEachPost() {
        compose.setContent { KudoTheme(DisplaySettings()) { Screen() } }
        open()
        for (number in 3..4) {
            showFloor(number)
            compose.onNodeWithTag("thread-like-$number").assertIsDisplayed()
            compose.onNodeWithTag("thread-reply-$number").assertIsDisplayed()
            compose.onNodeWithTag("thread-floor-$number").assertTextEquals("#$number").assertIsDisplayed()
            compose.onNodeWithTag("thread-jump-$number").assertIsDisplayed()
        }
        compose.onNode(hasContentDescription("更多操作") and hasAnyAncestor(hasTestTag("thread-posts"))).assertDoesNotExist()
        compose.onNode(hasContentDescription("Boost 此帖子") and hasAnyAncestor(hasTestTag("thread-posts"))).assertDoesNotExist()
        capture("nested-reply-actions-light.png")
    }

    @Test fun discussionUsesTheSameHeartAsTheOriginalFloor() {
        compose.setContent { KudoTheme(DisplaySettings()) { Column {
            TopicReactionButton(nested, true, null, {}, Modifier.testTag("original-heart"))
            ThreadPreviewPost(nested, true, {}, {}, {}, "丙 回复 乙", initial, {}, {}, {})
        } } }
        val original = buttonPixels(compose.onNodeWithTag("original-heart"))
        val discussion = buttonPixels(compose.onNodeWithTag("thread-like-4"))
        assertEquals(original.size, discussion.size)
        var foreground = 0
        var different = 0
        original.indices.forEach { index ->
            fun ink(pixel: Int) = android.graphics.Color.red(pixel) < 210 && android.graphics.Color.green(pixel) < 210 &&
                android.graphics.Color.blue(pixel) < 210
            val expected = ink(original[index])
            val actual = ink(discussion[index])
            if (expected || actual) foreground++
            if (expected != actual) different++
        }
        assertTrue("弹窗应复用原楼层的爱心图标与计数，允许位置取整的抗锯齿差异：$different/$foreground",
            foreground > 0 && different < foreground * .2f)
    }

    @Test fun longPressChoosesAnEmojiForTheActualReplyWithoutLikingOrClosingTheDiscussion() {
        var shortTaps = 0
        val selected = mutableListOf<Pair<Int, String>>()
        val withReactions = initial.copy(detail = detail.copy(validReactions = listOf("heart", "clap"),
            posts = posts.map { if (it.id == nested.id) it.copy(reactionsAvailable = true) else it }))
        compose.setContent { KudoTheme(DisplaySettings()) {
            Screen(withReactions, like = { shortTaps++ }, react = { post, name -> selected += post.id to name })
        } }
        open(); showFloor(4)
        compose.onNodeWithTag("thread-like-4").performTouchInput { longClick(durationMillis = 650) }
        compose.onNodeWithTag("reaction-choice-clap").assertIsDisplayed()
        compose.onNodeWithTag("thread-posts").assertDoesNotExist()
        compose.onNodeWithTag("reaction-choice-clap").performClick()
        compose.runOnIdle { assertEquals(listOf(40 to "clap"), selected); assertEquals(0, shortTaps) }
        compose.onNodeWithTag("thread-posts").assertIsDisplayed()
        compose.onNodeWithTag("thread-like-4").performClick()
        compose.runOnIdle { assertEquals(1, shortTaps) }
    }

    @Test fun likeKeepsDiscussionOpenLocksDuplicateClicksAndUpdatesOriginalFloor() {
        var state by mutableStateOf(initial)
        val likes = mutableListOf<Post>()
        compose.setContent { KudoTheme(DisplaySettings()) {
            Screen(state, like = { likes += it; state = state.copy(busy = setOf(it.id)) })
        } }
        open(); showFloor(4)
        compose.onNodeWithTag("thread-like-4").performClick()
        compose.runOnIdle { assertEquals(listOf(40), likes.map(Post::id)) }
        compose.onNodeWithTag("thread-posts").assertIsDisplayed()
        compose.onNodeWithTag("thread-like-4").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, likes.size)
            val updated = nested.copy(liked = true, canLike = false, likes = 27, reactionCount = 27)
            state = state.copy(busy = emptySet(), detail = detail.copy(posts = posts.map { if (it.id == updated.id) updated else it }))
        }
        compose.onNodeWithTag("thread-like-4").assertIsEnabled()
        compose.onNode(hasText("27") and hasAnyAncestor(hasTestTag("thread-like-4")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("thread-like-4").performClick()
        compose.runOnIdle { assertEquals(2, likes.size); assertEquals("heart", likes.last().activeReaction) }
        compose.runOnIdle { state = state.copy(busy = emptySet()) }
        compose.onNodeWithTag("thread-jump-4").performClick()
        compose.onNodeWithTag("thread-posts").assertDoesNotExist()
        compose.onNode(hasText("27") and hasAnyAncestor(hasTestTag("post-4")), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun replyClosesDiscussionAndUsesTheSelectedChildOrOriginalComment() {
        val replied = mutableListOf<Int>()
        compose.setContent { KudoTheme(DisplaySettings()) { Screen(reply = { replied += it!!.number }) } }
        open(); showFloor(4)
        compose.onNodeWithTag("thread-reply-4").performClick()
        compose.onNodeWithTag("thread-posts").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(4), replied) }
        open(); showFloor(3)
        compose.onNodeWithTag("thread-reply-3").performClick()
        compose.onNodeWithTag("thread-posts").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(4, 3), replied) }
    }

    @Test fun closedAndArchivedTopicsDisableReplyAndServerPermissionsDisableLikes() {
        var state by mutableStateOf(initial.copy(detail = detail.copy(closed = true,
            posts = posts.map { if (it.number == 4) it.copy(canLike = false) else it })))
        compose.setContent { KudoTheme(DisplaySettings()) { Screen(state) } }
        open(); showFloor(4)
        compose.onNodeWithTag("thread-like-4").assertIsNotEnabled()
        compose.onNodeWithTag("thread-reply-4").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(detail = state.detail!!.copy(closed = false, archived = true)) }
        compose.onNodeWithTag("thread-reply-4").assertIsNotEnabled()
        compose.onNodeWithTag("thread-jump-4").assertIsEnabled()
    }

    @Test fun unauthenticatedButtonsRouteThroughExistingAuthenticatedCallbacks() {
        var signIns = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            Screen(initial.copy(viewerId = null), like = { signIns++ }, reply = { signIns++ })
        } }
        open(); showFloor(4)
        compose.onNodeWithTag("thread-like-4").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, signIns) }
        compose.onNodeWithTag("thread-reply-4").performClick()
        compose.runOnIdle { assertEquals(2, signIns) }
        compose.onNodeWithTag("thread-posts").assertDoesNotExist()
    }

    @Test fun ignoredReplyStaysHiddenWithoutNewInteractionButtons() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalIgnoredUsers provides setOf("second")) { Screen() }
        } }
        open(); showFloor(4)
        compose.onNode(hasText("内容已忽略") and hasAnyAncestor(hasTestTag("thread-preview-post-4"))).assertIsDisplayed()
        compose.onNodeWithTag("thread-like-4").assertDoesNotExist()
        compose.onNodeWithTag("thread-reply-4").assertDoesNotExist()
        compose.onNodeWithTag("thread-jump-4").assertIsDisplayed()
    }

    @Test fun narrowDarkLargeFontKeepsActionsAndJumpInsideTheComment() {
        val long = nested.copy(author = nested.author.copy(name = "很长的作者名字".repeat(4)))
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                Box(Modifier.width(280.dp)) { Screen(initial.copy(detail = detail.copy(posts = posts.map { if (it.id == long.id) long else it }))) }
            }
        } }
        open(); showFloor(4)
        val bounds = compose.onNodeWithTag("thread-preview-post-4").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("thread-like-4", "thread-reply-4", "thread-jump-4", "thread-floor-4")) {
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must fit in its comment", node.left >= bounds.left && node.right <= bounds.right + 1f)
        }
        capture("nested-reply-actions-dark-large.png")
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun buttonPixels(node: SemanticsNodeInteraction): IntArray {
        val screenshot = node.assertIsDisplayed().captureToImage().asAndroidBitmap()
        val width = screenshot.width
        val height = screenshot.height
        return IntArray(width * height).also {
            screenshot.getPixels(it, 0, width, 0, 0, width, height)
            screenshot.recycle()
        }
    }
}
