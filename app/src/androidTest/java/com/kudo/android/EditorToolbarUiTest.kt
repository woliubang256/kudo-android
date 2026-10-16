package com.kudo.android

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.editor.*
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class EditorToolbarUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun window() { compose.runOnUiThread {
        compose.activity.enableEdgeToEdge()
        compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    } }
    private fun tool(label: String) = compose.onNodeWithContentDescription(label).performScrollTo().performClick()
    private fun capture(tag: String, name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
        compose.onNodeWithTag(tag).assertIsDisplayed()
        file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun previewIsFirstScrollableToolbarAction() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            Box(Modifier.width(320.dp).height(640.dp)) {
                EditorForm(Draft(title = "标题", raw = "预览内容", categoryId = 1), emptyList(), {}, {}, {}, {})
            }
        } }
        val preview = compose.onNode(
            hasContentDescription("预览正文") and hasAnyAncestor(hasTestTag("editor-toolbar")),
            useUnmergedTree = true,
        )
        preview.assertExists()
        val bold = compose.onNodeWithContentDescription("粗体", useUnmergedTree = true)
        assertTrue("预览应位于第一个格式按钮之前", preview.fetchSemanticsNode().boundsInRoot.left < bold.fetchSemanticsNode().boundsInRoot.left)
    }

    @Test fun newTopicPreviewIsSeparateAndPreservesSelectionWithoutPublishing() {
        var draft by mutableStateOf(Draft(title = "预览标题", raw = "前中文后", categoryId = 1))
        var publishes = 0
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) { EditorForm(draft, emptyList(), { draft = it }, { publishes++ }, {}, {}) } }
        compose.onNodeWithTag("editor-body").performTextInputSelection(TextRange(1, 3))
        tool("粗体")
        compose.runOnIdle { assertEquals("前**中文**后", draft.raw) }
        tool("预览正文")
        compose.onNodeWithTag("markdown-preview").assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithText("前中文后").fetchSemanticsNodes().isNotEmpty() }
        capture("markdown-preview", "editor-preview-light.png")
        compose.onNodeWithContentDescription("关闭预览").performClick()
        compose.onNodeWithTag("editor-body").assertTextEquals("前**中文**后")
        compose.onNodeWithTag("editor-body").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3, 5)))
        compose.runOnIdle { assertEquals(0, publishes) }
    }
    @Test fun compactReplyCanOpenLinkAndListSheetsWithFixedPublishButton() {
        var draft by mutableStateOf(Draft(raw = "这是一个满足最低字数的回复正文，用来验证工具栏。"))
        val visibility = MutableTransitionState(false)
        compose.setContent { KudoTheme(DisplaySettings()) {
            Box(Modifier.fillMaxSize()) { ReplyEditorSheet(visibility, false, { visibility.targetState = false }) {
                EditorForm(draft, emptyList(), { draft = it }, {}, {}, {}, reply = true, replyRules = ReplyRules(16, 64000))
            } }
        } }
        compose.runOnIdle { visibility.targetState = true }
        compose.onNodeWithTag("editor-body").performTextInputSelection(TextRange(0, 2))
        tool("插入链接")
        compose.onNodeWithTag("editor-link-url").performTextInput("linux.do")
        compose.onNodeWithText("插入", substring = false).performClick()
        compose.waitUntil(5000) { draft.raw.startsWith("[这是](https://linux.do)") }
        compose.onNodeWithText("发布", substring = false).assertIsDisplayed().assertIsEnabled()
        tool("列表")
        compose.onNodeWithText("任务列表").performClick()
        compose.waitUntil(5000) { draft.raw.startsWith("- [ ] ") }
        compose.waitUntil(5000) { compose.onAllNodesWithText("任务列表").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        capture("editor-form", "editor-reply-toolbar.png")
    }
    @Test fun editingUsesEmojisAndMoreTemplatesInDarkLargeText() {
        var draft by mutableStateOf(Draft(raw = "原始文字"))
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            EditorForm(draft, emptyList(), { draft = it }, {}, {}, {}, editing = true, editingReply = true)
        } }
        tool("表情")
        compose.onNodeWithContentDescription(":smile:").performClick()
        compose.waitUntil(5000) { draft.raw.endsWith(":smile:") }
        tool("更多格式")
        compose.onNodeWithText("折叠内容").performScrollTo().performClick()
        compose.waitUntil(5000) { draft.raw.contains("[details=") }
        tool("预览正文")
        compose.onNodeWithTag("markdown-preview").assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithText("摘要").fetchSemanticsNodes().isNotEmpty() }
        capture("markdown-preview", "editor-preview-dark-large.png")
    }
    @Test fun emojiPickerMirrorsWebsiteGroupsSearchAndRecentUse() {
        var draft by mutableStateOf(Draft(raw = ""))
        compose.setContent { KudoTheme(DisplaySettings()) {
            EditorForm(draft, emptyList(), { draft = it }, {}, {}, {}, editing = true, editingReply = true)
        } }
        tool("表情")
        compose.onNodeWithTag("editor-emoji-groups").assertIsDisplayed()
        compose.onNodeWithText("最近使用").assertDoesNotExist()
        compose.onNodeWithTag("editor-emoji-search").performTextInput("smile")
        compose.onNodeWithText("搜索结果").assertDoesNotExist()
        compose.onNodeWithContentDescription(":smile:").performClick()
        compose.waitUntil(5000) { draft.raw == ":smile:" }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("editor-emoji-search").fetchSemanticsNodes().isEmpty() }
        tool("表情")
        compose.onNodeWithText("最近使用").assertDoesNotExist()
        compose.onNodeWithContentDescription(":smile:").assertIsDisplayed()
    }

    @Test fun emojiGroupTabsUseIconsInsteadOfTextLabels() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            EditorForm(Draft(raw = ""), emptyList(), {}, {}, {}, {}, editing = true, editingReply = true)
        } }
        tool("表情")
        compose.onNodeWithTag("editor-emoji-tab-recent").assertIsDisplayed()
        compose.onNode(
            hasText("最近使用") and hasAnyAncestor(hasTestTag("editor-emoji-tab-recent")),
            useUnmergedTree = true,
        ).assertDoesNotExist()
        compose.onNode(
            hasAnyAncestor(hasTestTag("editor-emoji-tab-recent")) and hasContentDescription("star"),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag("editor-emoji-tab-recent").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performTouchInput { longClick() }
        compose.onNodeWithTag("editor-emoji-tab-0").assertIsDisplayed()
        compose.onNode(
            hasText("表情与情绪") and hasAnyAncestor(hasTestTag("editor-emoji-tab-0")),
            useUnmergedTree = true,
        ).assertDoesNotExist()
        compose.onNodeWithTag("editor-emoji-tab-0").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
    }

    @Test fun emojiGroupsSwipeWithTheirIconTabsAndLeaveSearchWithoutInserting() {
        var draft by mutableStateOf(Draft(raw = ""))
        compose.setContent { KudoTheme(DisplaySettings()) {
            EditorForm(draft, emptyList(), { draft = it }, {}, {}, {}, editing = true, editingReply = true)
        } }
        tool("表情")
        compose.onNodeWithTag("editor-emoji-tab-recent").performClick()
        compose.onNodeWithTag("editor-emoji-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithTag("editor-emoji-tab-0").assertIsSelected()
        compose.onNodeWithContentDescription(":smile:").assertIsDisplayed()
        compose.onNodeWithTag("editor-emoji-pager").performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithTag("editor-emoji-tab-recent").assertIsSelected()
        compose.onNodeWithTag("editor-emoji-search").performTextReplacement("smile")
        compose.onNodeWithTag("editor-emoji-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithTag("editor-emoji-tab-0").assertIsSelected()
        compose.onNodeWithTag("editor-emoji-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.runOnIdle { assertEquals("", draft.raw) }
    }

    @Test fun sendingLocksAllFormattingAndPreviewAndImageControls() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            EditorForm(Draft(raw = "待发布内容"), emptyList(), {}, {}, {}, {}, sending = true, reply = true, replyRules = ReplyRules(1, 64000))
        } }
        compose.onNodeWithTag("editor-body").assertIsNotEnabled()
        compose.onNodeWithContentDescription("粗体").assertIsNotEnabled()
        compose.onNodeWithContentDescription("上传图片").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("预览正文").assertIsNotEnabled()
    }
}
