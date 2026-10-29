package com.kudo.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import com.kudo.android.ui.editor.MarkdownPreviewRenderer
import com.kudo.android.ui.topic.parseInlineArticle
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class MentionRenderingTest {
    @Test fun previewRecognizesUsersWithoutChangingCodeEmailsLinksOrEscapes() {
        val raw = "你好 @neo 和 @ne_yu-1。\n\n邮箱 a@neo.com `@code` \\@escaped [@linked](https://example.com)\n\n```\n@fenced\n```"
        val document = Jsoup.parseBodyFragment(MarkdownPreviewRenderer.render(raw).html)
        assertEquals(listOf("@neo", "@ne_yu-1"), document.select("a.mention").map { it.text() })
        assertEquals("https://linux.do/u/neo", document.selectFirst("a.mention")!!.attr("href"))
        assertTrue(document.text().contains("a@neo.com"))
        assertEquals("@code", document.selectFirst("p code")!!.text())
        assertEquals("@linked", document.selectFirst("a[href=https://example.com]")!!.text())
    }

    @Test fun cookedMentionKeepsItsUsernameAndLinkWithoutAnUnderline() {
        val article = parseInlineArticle("前<a class='mention' href='/u/neo'>@neo</a>后", Color.Blue)
        assertEquals("前@neo后", article.text.text)
        assertEquals(1, article.text.getLinkAnnotations(0, article.text.length).size)
        assertEquals(1, article.text.getStringAnnotations("mention", 0, article.text.length).size)
        assertFalse(article.text.getLinkAnnotations(0, article.text.length).any {
            (it.item as androidx.compose.ui.text.LinkAnnotation.Url).styles?.style?.textDecoration == TextDecoration.Underline
        })
    }

    @Test fun ordinaryAndOffsiteLinksDoNotAcquireMentionStyling() {
        val article = parseInlineArticle("<a href='/u/neo'>@neo</a> <a class='mention' href='https://example.com/u/neo'>@neo</a>", Color.Blue)
        assertTrue(article.text.getStringAnnotations("mention", 0, article.text.length).isEmpty())
        assertEquals(2, article.text.getLinkAnnotations(0, article.text.length).size)
    }
}
