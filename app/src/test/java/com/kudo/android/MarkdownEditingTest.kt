package com.kudo.android

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.kudo.android.ui.editor.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownEditingTest {
    @Test fun wrapsMiddleAndReverseSelectionAndToggles() {
        val source = TextFieldValue("前面中文后面", TextRange(4, 2))
        val bold = source.wrapMarkdown("**")
        assertEquals("前面**中文**后面", bold.text)
        assertEquals(TextRange(6, 4), bold.selection)
        assertEquals(source, bold.wrapMarkdown("**"))
    }
    @Test fun emptyInsertionSelectsPlaceholderAndCommitsCompositionOnlyOnCommand() {
        val source = TextFieldValue("你好", TextRange(1), TextRange(0, 1))
        assertEquals(source, source.syncMarkdown("你好"))
        val formatted = source.wrapMarkdown("*")
        assertEquals("你*文字*好", formatted.text)
        assertEquals(TextRange(2, 4), formatted.selection)
        assertNull(formatted.composition)
    }
    @Test fun listOperatesOnCompleteLinesAndDoesNotTouchFollowingLine() {
        val source = TextFieldValue("一\n二\n三", TextRange(0, 4))
        assertEquals("1. 一\n2. 二\n三", source.lineMarkdown(MarkdownLines.NUMBERED).text)
        val task = source.lineMarkdown(MarkdownLines.TASK)
        assertEquals("- [ ] 一\n- [ ] 二\n三", task.text)
        assertEquals(source.text, task.lineMarkdown(MarkdownLines.TASK).text)
    }
    @Test fun headingsReplacePreviousLevelAndParagraphRemovesIt() {
        val source = TextFieldValue("## 中文", TextRange(4))
        val h1 = source.lineMarkdown(MarkdownLines.H1)
        assertEquals("# 中文", h1.text)
        assertEquals("中文", h1.lineMarkdown(MarkdownLines.PARAGRAPH).text)
    }
    @Test fun switchingTaskListToBulletDoesNotLeaveACheckboxInPlainText() {
        val source = TextFieldValue("- [x] 完成", TextRange(7))
        assertEquals("- 完成", source.lineMarkdown(MarkdownLines.BULLET).text)
    }
    @Test fun unicodeBlockAndLinkPreserveSurroundings() {
        val source = TextFieldValue("前😀后", TextRange(1, 3))
        assertEquals("前\n\n```\n😀\n```\n\n后", source.blockMarkdown("```\n", "\n```").text)
        assertEquals("前[😀](https://a.test/a%28b%29)后", source.linkMarkdown("😀", "https://a.test/a(b)").text)
    }
    @Test fun uploadAnchorFollowsSuccessiveEditsWithoutLosingText() {
        var raw = "前后"; var anchor = 1
        for (new in listOf("前新增后", "开头前新增后", "开头前新增后结尾")) {
            anchor = rebaseMarkdownOffset(raw, new, anchor); raw = new
        }
        assertEquals("开头前新增\n\n![图](upload://a)\n\n后结尾", insertUploadedMarkdown(raw, anchor, "![图](upload://a)"))
    }
}
