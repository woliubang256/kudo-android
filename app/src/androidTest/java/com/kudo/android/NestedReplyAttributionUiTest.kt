package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
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

class NestedReplyAttributionUiTest {
    @get:Rule val compose = createComposeRule()
    private val author = Member(2, "root", "原评论作者", "", avatarDetailsLoaded = true)
    private val root = Post(20, 2, author, "<p>原评论正文</p>", "", 0, false, null, 1, true)
    private val direct = root.copy(id = 30, number = 3, author = author.copy(id = 3, username = "first", name = "乙"),
        cooked = "<p>直接回复</p>", replyTo = 2)
    private val nested = root.copy(id = 40, number = 4, author = author.copy(id = 4, username = "second", name = "丙"),
        cooked = "<p>回复乙</p>", replyTo = 3)
    private val deeper = root.copy(id = 50, number = 5, author = author.copy(id = 5, username = "third", name = "丁"),
        cooked = "<p>回复丙</p>", replyTo = 4)
    private val thread = PostThread(root, listOf(direct, nested, deeper))

    @Test fun previewUsesOnlyAuthorForDirectRepliesAndActualParentForDeeperReplies() {
        var jumped: Int? = null
        compose.setContent { KudoTheme(DisplaySettings()) { ReplyPreview(thread, null, false, true) { jumped = it } } }
        compose.onNodeWithText("乙：直接回复").assertIsDisplayed()
        compose.onNodeWithText("丙 回复 乙：回复乙").assertIsDisplayed().performClick()
        compose.onNodeWithText("丁 回复 丙：回复丙").assertIsDisplayed()
        compose.runOnIdle { assertEquals(4, jumped) }
        capture("nested-reply-attribution-preview.png")
    }

    @Test fun expandedDiscussionUsesTheSameReplyNames() {
        val posts = listOf(root.copy(id = 10, number = 1, replyTo = null), root, direct, nested, deeper)
        val detail = TopicDetail(5, "回复对象", 1, posts, posts.map(Post::id), false)
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicScreen(TopicState(detail, false), GlassMode.TRANSLUCENT, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("查看 3 条回复").performScrollTo().performClick()
        compose.onNode(hasText("乙", substring = false) and hasAnyAncestor(hasTestTag("thread-preview-post-3"))).assertIsDisplayed()
        compose.onNodeWithTag("thread-posts").performScrollToNode(hasTestTag("thread-preview-post-4"))
        compose.onNode(hasText("丙 回复 乙") and hasAnyAncestor(hasTestTag("thread-preview-post-4"))).assertIsDisplayed()
        compose.onNodeWithTag("thread-floor-4").assertTextEquals("#4").assertIsDisplayed()
        compose.onNodeWithContentDescription("跳到第 4 楼").assertIsDisplayed().performClick()
        compose.onNodeWithTag("thread-posts").assertDoesNotExist()
        compose.onNodeWithTag("post-4").assertIsDisplayed()
    }

    @Test fun absentParentsUseActualFloorAndInvalidParentsDoNotInventARecipient() {
        val missing = nested.copy(replyTo = 3)
        val invalid = deeper.copy(replyTo = 99)
        val unspecified = direct.copy(id = 60, number = 6, replyTo = null)
        compose.setContent { KudoTheme(DisplaySettings()) {
            ReplyPreview(PostThread(root, listOf(missing, invalid, unspecified)), null, false, true) {}
        } }
        compose.onNodeWithText("丙 回复 #3：回复乙").assertIsDisplayed()
        compose.onNodeWithText("丁：回复丙").assertIsDisplayed()
        compose.onNodeWithText("乙：直接回复").assertIsDisplayed()
    }

    @Test fun ignoredNestedReplyKeepsNamesAndHidesItsTextAndImages() {
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            CompositionLocalProvider(LocalIgnoredUsers provides setOf(nested.author.username)) {
                ReplyPreview(thread.copy(replies = listOf(direct, nested.copy(cooked = "<p>隐藏文字</p><img src='/secret.png'>"))),
                    null, false, true) {}
            }
        } }
        compose.onNodeWithText("丙 回复 乙：内容已忽略").assertIsDisplayed()
        compose.onNodeWithText("隐藏文字", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("reply-preview-images-40", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun longReplyNamesAtLargeFontKeepTheInlineImageActionUsable() {
        val gallery = ImageGalleryState()
        val post = nested.copy(author = nested.author.copy(name = "很长的回复者名称".repeat(3)),
            cooked = "<p>正文内容较长" + "继续阅读".repeat(10) + "</p><img src='/uploads/original/nested.png' width='800' height='400'>")
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f), LocalImageGallery provides gallery) {
                Box(Modifier.width(280.dp)) { ReplyPreview(PostThread(root, listOf(direct, post)), null, false, true) {} }
            }
        } }
        compose.onNodeWithTag("reply-preview-images-40", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(gallery.session!!.current.originalUrl.endsWith("/nested.png")) }
        capture("nested-reply-attribution-large-font.png")
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
