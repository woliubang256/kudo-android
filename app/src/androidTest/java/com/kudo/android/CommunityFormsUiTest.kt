package com.kudo.android

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.components.CommunityButton
import com.kudo.android.ui.editor.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class CommunityFormsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val categories = listOf(Category(1, "开发调优", "dev", "fff", "", 0), Category(2, "日常闲聊", "chat", "fff", "", 0))

    @Before fun useProductionWindowConfiguration() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun replyHintUsesTargetNicknameAndUsernameFallbackWithoutChangingDraft() {
        val topicAuthor = Member(1, "topic_user", "主题昵称", "")
        val floorAuthor = Member(2, "floor_user", "楼层昵称", "")
        var target by mutableStateOf<Member?>(topicAuthor)
        var draft by mutableStateOf(Draft())
        compose.setContent { KudoTheme(DisplaySettings()) {
            EditorForm(draft, emptyList(), { draft = it }, {}, {}, {}, reply = true,
                replyRules = ReplyRules(16, 64000), replyAuthor = target)
        } }
        compose.onNodeWithText("回复 主题昵称").assertIsDisplayed()
        compose.onNodeWithText("回复 topic_user").assertDoesNotExist()
        compose.runOnIdle { target = floorAuthor }
        compose.onNodeWithText("回复 楼层昵称").assertIsDisplayed()
        compose.onNodeWithTag("editor-body").performTextInput("继续写草稿")
        compose.onNodeWithText("回复 楼层昵称").assertDoesNotExist()
        compose.runOnIdle { target = topicAuthor; assertEquals("继续写草稿", draft.raw) }
        compose.onNodeWithTag("editor-body").assertTextEquals("继续写草稿")
        compose.onNodeWithTag("editor-body").performTextClearance()
        compose.runOnIdle { target = floorAuthor.copy(name = "") }
        compose.onNodeWithText("回复 floor_user").assertIsDisplayed()
        compose.runOnIdle { target = null }
        compose.onNodeWithText("回复", substring = false).assertIsDisplayed()
    }

    @Test fun newTopicUsesCategorySheetAndOpenInputsAndPreservesToolCallbacks() {
        var draft by mutableStateOf(Draft())
        var published = 0; var images = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            EditorForm(draft, categories, { draft = it }, { published++ }, { images++ }, {})
        } }
        compose.onNodeWithText("发布", substring = false).assertIsNotEnabled()
        compose.onNodeWithTag("editor-category").performClick()
        compose.onNodeWithTag("sheet-drag-handle").assertIsDisplayed()
        compose.onNodeWithText("开发调优").performClick()
        compose.onNodeWithTag("editor-title").performTextInput("一个清楚的标题")
        compose.onNodeWithTag("editor-body").performTextInput("正文内容")
        compose.onNodeWithContentDescription("插入代码块").performScrollTo().performClick()
        compose.onNodeWithContentDescription("上传图片").performScrollTo().performClick()
        compose.onNodeWithText("发布", substring = false).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, draft.categoryId); assertTrue(draft.raw.contains("```")); assertEquals(1, images); assertEquals(1, published) }
        capture("coolapk-new-topic.png")
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun replyFocusesAboveKeyboardAndDraggingKeepsDraftForReopening() {
        val visibility = MutableTransitionState(false)
        var draft by mutableStateOf(Draft())
        var ime = false; var imeBottom = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            val density = LocalDensity.current
            val visible = WindowInsets.isImeVisible
            val bottom = WindowInsets.ime.getBottom(density)
            SideEffect { ime = visible; imeBottom = bottom }
            Box(Modifier.fillMaxSize()) {
                Text("讨论背景")
                ReplyEditorSheet(visibility, false, { visibility.targetState = false }) {
                    EditorForm(draft, emptyList(), { draft = it }, {}, {}, {}, reply = true,
                        replyRules = ReplyRules(16, 64000))
                }
            }
        } }
        val expandedBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        compose.runOnIdle { visibility.targetState = true }
        compose.onNodeWithTag("editor-body").assertIsFocused()
        try { compose.waitUntil(5000) { ime } } catch (failure: Throwable) {
            capture("coolapk-reply-keyboard-failure.png")
            throw failure
        }
        compose.onNodeWithTag("editor-body").performTextInput("关闭后继续写")
        compose.onNodeWithText("发布", substring = false).assertIsNotEnabled()
        compose.onNodeWithTag("editor-body").performTextInput("，这是一段满足回帖字数的草稿")
        compose.onNodeWithText("发布", substring = false).assertIsEnabled().assertIsDisplayed()
        val rootBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(compose.onNodeWithTag("liquid-action-sheet").fetchSemanticsNode().boundsInRoot.bottom <= minOf(rootBottom, expandedBottom - imeBottom) + 2f)
        capture("coolapk-reply-keyboard.png")
        compose.onNodeWithTag("sheet-drag-handle").performTouchInput { swipe(center, center + Offset(0f, 650f), 600) }
        compose.onNodeWithTag("editor-body").assertDoesNotExist()
        compose.runOnIdle { assertEquals("关闭后继续写，这是一段满足回帖字数的草稿", draft.raw); visibility.targetState = true }
        compose.onNodeWithTag("editor-body").assertTextEquals("关闭后继续写，这是一段满足回帖字数的草稿")
    }

    @Test fun reportIsFullPageAndRetainsWebsiteValidationInDarkLargeText() {
        val illegal = FlagReason(10, "illegal", "涉嫌违法", "请说明具体情况", true)
        val config = FlagConfiguration(listOf(illegal), 3)
        var state by mutableStateOf(TopicActionState(flags = config))
        var submissions = 0
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            TopicReportPage(state, {}, { state = state.copy(selectedFlag = it) }, { state = state.copy(message = it) },
                { state = state.copy(confirmed = it) }, { submissions++ }, {})
        } }
        compose.onNodeWithTag("topic-report-page").assertIsDisplayed()
        compose.onNodeWithText("提交举报").assertIsNotEnabled()
        compose.onNodeWithText("涉嫌违法").performClick()
        compose.onNodeWithTag("report-message").performTextInput("具体说明")
        compose.onNodeWithText("提交举报").assertIsNotEnabled()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("提交举报").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, submissions) }
        capture("coolapk-report-dark-large.png")
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
