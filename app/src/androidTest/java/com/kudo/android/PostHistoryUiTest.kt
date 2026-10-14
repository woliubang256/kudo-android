package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.topic.*
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PostHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val author = Member(2, "me", "测试作者", "")
    private val post = Post(81, 8, author, "<p>正文</p>", "", 0, false, null, null, false, version = 3, canViewEditHistory = true, canEdit = true)
    private val repository = CommunityRepository(DiscourseClient(object : SessionCookies {
        override fun get(url: String) = ""; override fun set(url: String, cookie: String) {}
    }))
    private fun capture(name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun fullVersionListShowsImagesAndOriginalInOrderAndSupportsBack() {
        var returned = false
        val latest = HistoryVersion(3, author, "2026-10-15T02:00:00Z", "修正图片", "话题标题",
            "<p>最新完整正文</p><img src='https://linux.do/history-test.png' width='240' height='120'>")
        val original = HistoryVersion(1, author, "2026-10-15T01:00:00Z", "", "话题标题", "<p>最初完整正文</p>", original = true)
        compose.setContent { KudoTheme(DisplaySettings(glass = GlassMode.TRANSLUCENT)) {
            PostHistoryScreen(81, PostHistoryState(listOf(latest, original), loading = false), {}, {}, { returned = true }, {}, {}, {})
        } }
        compose.onNodeWithTag("post-history-page").assertIsDisplayed()
        compose.onNodeWithText("最新完整正文").assertIsDisplayed()
        compose.onNodeWithTag("article-image-history-81-3-0").assertExists()
        compose.onNodeWithText("编辑说明：修正图片").assertExists()
        capture("post-history-list-light.png")
        compose.onNodeWithTag("post-history-list").performScrollToNode(hasTestTag("history-version-1"))
        compose.onNodeWithText("最初完整正文").assertIsDisplayed()
        compose.onNodeWithText("没有更多内容").assertExists()
        compose.onNodeWithContentDescription("返回").performClick(); assertTrue(returned)
    }
    @Test fun menuNavigatesUsingExactReplyIdAndOnlyOwnerGetsEdit() {
        var history: Pair<Int, Int>? = null
        var edited: Pair<Int, Int>? = null
        var viewer by mutableStateOf(author)
        compose.setContent { KudoTheme(DisplaySettings(glass = GlassMode.TRANSLUCENT)) {
            TopicActionsHost(repository, viewer, GlassMode.TRANSLUCENT, {}, { _, _ -> }, {},
                editPost = { id, target -> edited = id to target.id }, openHistory = { id, target -> history = id to target.id }) {
                val actions = LocalTopicActions.current
                Button({ actions.openReply(5, post) {} }) { Text("打开回复操作") }
            }
        } }
        compose.onNodeWithText("打开回复操作").performClick()
        compose.onNodeWithText("编辑").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(5 to 81, edited) }
        compose.onNodeWithText("打开回复操作").performClick()
        compose.onNodeWithText("编辑历史").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(5 to 81, history); viewer = author.copy(id = 3, username = "other") }
        compose.onNodeWithText("打开回复操作").performClick()
        compose.onNodeWithText("编辑").assertDoesNotExist()
    }
    @Test fun editedIndicatorOpensHistoryAndHiddenVersionDoesNotShowBody() {
        var opens = 0
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            Column {
                TopicAuthorMetadata("2026-10-15T01:00:00Z", null, true) { opens++ }
                PostHistoryScreen(81, PostHistoryState(listOf(HistoryVersion(3, author, "2026-10-15T01:00:00Z", "", "", null,
                    unavailable = "此版本包含隐藏内容，无法展示正文")), loading = false), {}, {}, {}, {}, {}, {})
            }
        } }
        compose.onNodeWithTag("edited-history").performClick(); assertEquals(1, opens)
        compose.onNodeWithText("此版本包含隐藏内容，无法展示正文").assertIsDisplayed()
        capture("post-history-hidden-dark-large.png")
    }
}
