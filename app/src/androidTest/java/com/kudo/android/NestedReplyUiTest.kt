package com.kudo.android

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NestedReplyUiTest {
    @get:Rule val compose = createComposeRule()
    private val author = Member(1, "root", "一级作者", "", avatarDetailsLoaded = true)
    private val first = Post(1, 1, author, "<p>主帖正文</p>", "", 0, false, null, null, true)
    private val root = first.copy(id = 2, number = 2, cooked = "<p>一级评论正文</p>", replyTo = 1)
    private val children = (3..7).map { root.copy(id = it, number = it, author = author.copy(id = it, username = "child$it", name = "子作者$it"), cooked = "<p>子回复正文$it</p>", replyTo = if (it == 7) 3 else 2) }
    private val detail = TopicDetail(5, "楼中楼话题", 1, listOf(first, root) + children, (1..7).toList(), false)
    private val repository = CommunityRepository(DiscourseClient(object : SessionCookies {
        override fun get(url: String) = ""
        override fun set(url: String, cookie: String) {}
    }))
    @Composable private fun Screen(target: Int? = null, reply: (Post?) -> Unit = {}, bookmark: (Post) -> Unit = {}, settings: DisplaySettings = DisplaySettings(), ignored: Set<String> = emptySet()) {
        KudoTheme(settings) { TopicActionsHost(repository, author.copy(id = 99, username = "viewer", ignoredUsers = ignored), GlassMode.TRANSLUCENT, {}, { _, _ -> }, {}) {
            TopicScreen(TopicState(detail, false, targetNumber = target), GlassMode.TRANSLUCENT, {}, {}, {}, {}, {}, bookmark, reply, {}, {}, {}, {})
        } }
    }
    @Test fun previewShowsThreeRepliesAndOriginalFloorActionsUseActualFloors() {
        var replied = 0; var saved = 0
        compose.setContent { Screen(reply = { replied = it!!.number }, bookmark = { saved = it.number }) }
        compose.onNodeWithTag("reply-preview-2").performScrollTo().assertIsDisplayed()
        (3..5).forEach { compose.onNodeWithTag("reply-preview-post-$it").assertExists() }
        compose.onNodeWithTag("reply-preview-post-6").assertDoesNotExist()
        compose.onNodeWithText("回复 #2").assertDoesNotExist()
        capture("nested-reply-preview.png")
        compose.onNodeWithTag("reply-preview-post-3").performClick()
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithTag("post-3").assertIsDisplayed()
        capture("nested-reply-original.png")
        compose.onNode(hasContentDescription("更多操作") and hasAnyAncestor(hasTestTag("post-3"))).performClick()
        compose.onNodeWithText("收藏").performClick()
        compose.onNode(hasText("回复") and hasClickAction() and hasAnyAncestor(hasTestTag("post-3"))).performClick()
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(3, replied); assertEquals(3, saved) }
    }
    @Test fun deepLinkTargetsOriginalFloorAndSystemBackClosesReadOnlyDiscussion() {
        compose.setContent { Screen(target = 7) }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithTag("post-7").assertIsDisplayed()
        compose.onNodeWithTag("topic-posts").performScrollToNode(hasTestTag("reply-preview-2"))
        compose.onNodeWithText("查看更多回复").performClick()
        compose.onNodeWithTag("liquid-action-sheet").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("post-2").assertExists()
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
    }

    @Test fun darkLargeTextKeepsIgnoredRepliesHiddenUntilExplicitlyRevealed() {
        compose.setContent { Screen(settings = DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f), ignored = setOf("child3")) }
        compose.onNodeWithTag("reply-preview-2").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("子作者3：内容已忽略") and hasAnyAncestor(hasTestTag("reply-preview-post-3")))
            .assertIsDisplayed().performClick()
        compose.onNodeWithTag("post-3").assertIsDisplayed()
        compose.onNodeWithText("子回复正文3").assertDoesNotExist()
        compose.onNode(hasText("查看此楼层") and hasAnyAncestor(hasTestTag("post-3"))).performClick()
        compose.onNodeWithText("子回复正文3").assertIsDisplayed()
        capture("nested-reply-dark-large-text.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
