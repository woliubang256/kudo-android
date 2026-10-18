package com.kudo.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.editor.EditorForm
import com.kudo.android.ui.editor.ReplyEditorSheet
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import java.io.File

class PostQuoteUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val source = Post(80, 8, Member(6, "quote_user", "引用作者", "", avatarDetailsLoaded = true),
        "<p>before needle after</p>", "2026-10-18T00:00:00Z", 0, false, null, null, true)
    private val sentence = "before needle after"
    private val clipboard get() = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Before fun includeNativeMenuWindows() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    @Test fun quotingOpensReplyAppendsDraftAndDoesNotTouchTheSystemClipboard() {
        var draft by mutableStateOf(Draft(raw = "已有草稿"))
        val visibility = MutableTransitionState(false)
        compose.setContent { KudoTheme(DisplaySettings()) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp)) {
                    PostQuoteSelection(700, source, { markdown ->
                        draft = draft.copy(raw = appendPostQuote(draft.raw, markdown)); visibility.targetState = true
                    }) { RichContent(source.cooked, {}, {}) }
                }
                ReplyEditorSheet(visibility, false, { visibility.targetState = false }) {
                    EditorForm(draft, emptyList(), { draft = it }, { fail("引用不能自动发布") }, {}, {},
                        reply = true, replyRules = ReplyRules(1, 64000), replyAuthor = source.author)
                }
            }
        } }
        compose.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("原内容", "保留剪贴板"))
        }
        selectWord(sentence, "needle")
        clickMenu("引用")
        val expected = "已有草稿\n\n${postQuoteMarkdown(700, source, "needle")}\n\n"
        compose.onNodeWithTag("editor-body").assertTextEquals(expected)
        compose.runOnIdle {
            assertEquals(expected, draft.raw)
            assertEquals("保留剪贴板", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        capture("post-quote-reply.png")
    }

    @Test fun copyingQuoteOnlyWritesFinalMarkdownAndShowsTheSharedToast() {
        var replies = 0
        article(reply = { replies++ })
        compose.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("原内容", "旧内容"))
        }
        SystemClock.sleep(4000)
        selectWord(sentence, "needle")
        revealMenu("复制引用")
        capture("post-quote-menu.png")
        clickMenu("复制引用")
        val expected = postQuoteMarkdown(700, source, "needle")
        compose.runOnIdle {
            assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(0, replies)
        }
        compose.waitUntil(5000) { hasYellowToast() }
        compose.onNodeWithTag("editor-body").assertDoesNotExist()
    }

    @Test fun onlyCopySelectAndQuoteActionsRemainInTheSelectionMenu() {
        article()
        selectWord(sentence, "needle")
        revealMenu("复制引用")
        assertNotNull(nativeNode("复制", "Copy"))
        assertNotNull(nativeNode("全选", "Select all"))
        assertNull(nativeNode("翻译", "Translate"))
        clickMenu("复制", "Copy")
        assertClipboard("needle")
        selectWord(sentence, "needle")
        clickMenu("全选", "Select all")
        clickMenu("复制引用")
        assertClipboard(postQuoteMarkdown(700, source, sentence))
    }

    @Test fun codeQuotePreservesChineseSpacesAndLineBreaksWithoutCopyingOtherBlocks() {
        val code = "  中文😀 needle\n\tsecond line\n"
        val post = source.copy(number = 1, cooked = "<p>不应复制的相邻正文</p><pre><code>$code</code></pre>")
        article(post)
        selectWord(code, "needle")
        clickMenu("全选", "Select all")
        clickMenu("复制引用")
        assertClipboard(postQuoteMarkdown(700, post, code))
    }

    @Test fun laterParagraphSelectionNeverReusesPreviousParagraph() {
        val second = "next selection paragraph"
        val post = source.copy(cooked = "${source.cooked}<p>$second</p>")
        article(post)
        selectWord(sentence, "needle")
        clickMenu("复制引用")
        assertClipboard(postQuoteMarkdown(700, post, "needle"))
        selectWord(second, "selection")
        clickMenu("复制引用")
        assertClipboard(postQuoteMarkdown(700, post, "selection"))
    }

    @Test fun closedAndArchivedTopicsOmitReplyQuoteOnly() {
        var archived by mutableStateOf(false)
        compose.setContent { KudoTheme(DisplaySettings()) {
            val detail = TopicDetail(700, "不可回复", 1, listOf(source), listOf(source.id), !archived, archived = archived)
            Column(Modifier.padding(20.dp)) {
                TopicPost(source, detail, TopicState(detail, false), true, {}, {}, {}, {}, {}, {}, {},
                    quote = { _, _ -> fail("不可回复的话题不应提供引用回复") })
            }
        } }
        repeat(2) {
            selectWord(sentence, "needle")
            revealMenu("复制引用")
            assertNull(nativeNode("引用"))
            assertNotNull(nativeNode("复制", "Copy"))
            assertNotNull(nativeNode("全选", "Select all"))
            clickMenu("复制引用")
            assertClipboard(postQuoteMarkdown(700, source, "needle"))
            compose.runOnIdle { archived = true }
        }
    }

    @Test fun nestedReplyQuoteUsesActualChildAndClosesTheDiscussion() {
        val first = source.copy(id = 10, number = 1, cooked = "<p>主帖正文</p>")
        val parent = source.copy(id = 20, number = 2, cooked = "<p>原评论正文</p>", replyTo = 1)
        val child = source.copy(id = 30, number = 3, replyTo = 2)
        val posts = listOf(first, parent, child)
        val detail = TopicDetail(700, "引用楼中楼", 1, posts, posts.map(Post::id), false)
        var quoted: Pair<Int, String>? = null
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicScreen(TopicState(detail, false, viewerId = 99), GlassMode.TRANSLUCENT,
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, quoteReply = { post, markdown -> quoted = post.number to markdown })
        } }
        compose.onNodeWithText("查看 1 条回复").performScrollTo().performClick()
        compose.onNodeWithTag("thread-posts").assertIsDisplayed()
        selectWord(sentence, "needle", "thread-posts")
        clickMenu("引用")
        compose.onNodeWithTag("thread-posts").assertDoesNotExist()
        compose.runOnIdle { assertEquals(3 to postQuoteMarkdown(700, child, "needle"), quoted) }
    }

    private fun article(post: Post = source, reply: (String) -> Unit = {}) {
        compose.setContent { KudoTheme(DisplaySettings()) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                PostQuoteSelection(700, post, reply) { RichContent(post.cooked, {}, {}) }
            }
        } }
    }

    private fun selectWord(text: String, word: String, ancestor: String? = null) {
        val layouts = mutableListOf<TextLayoutResult>()
        val matcher = if (ancestor == null) hasText(text) else hasText(text) and hasAnyAncestor(hasTestTag(ancestor))
        val node = compose.onNode(matcher, useUnmergedTree = true)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val offset = layouts.single().getBoundingBox(text.indexOf(word) + word.length / 2).center
        node.performTouchInput { longClick(offset, durationMillis = 650) }
        compose.waitForIdle()
    }

    private fun nativeNode(vararg labels: String): AccessibilityNodeInfo? {
        val localized = labels.toSet() + labels.map { when (it) {
            "复制" -> "複製"
            "全选" -> "全選"
            "翻译" -> "翻譯"
            "更多选项" -> "更多選項"
            "更多" -> "更多"
            else -> it
        } }
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() in localized || node.contentDescription?.toString() in localized) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { result -> return result } }
            return null
        }
        val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
        return roots.firstNotNullOfOrNull(::find)
    }

    private fun revealMenu(vararg labels: String) {
        compose.waitUntil(5000) { nativeNode(*labels) != null || nativeNode("更多选项", "More options", "更多", "More") != null }
        if (nativeNode(*labels) == null) {
            assertTrue(nativeNode("更多选项", "More options", "更多", "More")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        compose.waitUntil(5000) { nativeNode(*labels) != null }
    }

    private fun clickMenu(vararg labels: String) {
        revealMenu(*labels)
        assertTrue("菜单项应可点击：${labels.toList()}", nativeNode(*labels)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitForIdle()
    }

    private fun assertClipboard(expected: String) = compose.runOnIdle {
        assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    private fun capture(name: String) {
        val bitmap = automation.takeScreenshot()!!
        try { File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }

    private fun hasYellowToast(): Boolean {
        val bitmap = automation.takeScreenshot() ?: return false
        return try {
            (0 until bitmap.height / 3 step 4).any { row ->
                (bitmap.width / 4 until bitmap.width * 3 / 4 step 4).count { column ->
                    val pixel = bitmap.getPixel(column, row)
                    val red = pixel shr 16 and 0xff
                    val green = pixel shr 8 and 0xff
                    val blue = pixel and 0xff
                    red >= 220 && green in 130..210 && blue <= 80
                } > bitmap.width / 40
            }
        } finally { bitmap.recycle() }
    }
}
