package com.kudo.android

import com.kudo.android.ui.editor.MarkdownPreviewRenderer
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class MarkdownPreviewTest {
    @Test fun commonFormattingTablesAndTasksAreRendered() {
        val doc = Jsoup.parseBodyFragment(MarkdownPreviewRenderer.render("# 标题\n\n**粗体** *斜体* ~~删除~~\n\n> 引用\n\n- [x] 完成\n\n| 甲 | 乙 |\n| --- | --- |\n| 一 | 二 |").html)
        assertEquals("标题", doc.selectFirst("h1")!!.text())
        assertEquals("粗体", doc.selectFirst("strong")!!.text())
        assertEquals("删除", doc.selectFirst("del")!!.text())
        assertEquals("引用", doc.selectFirst("blockquote")!!.text())
        assertTrue(doc.selectFirst("input")!!.hasAttr("checked"))
        assertEquals(2, doc.select("table tr").size)
    }
    @Test fun siteDetailsSpoilersEmojiAndInlineFootnotesArePreserved() {
        val preview = MarkdownPreviewRenderer.render("[details=\"摘要\"]\n**正文**\n[/details]\n\n[spoiler]秘密[/spoiler] :custom:\n\n脚注^[说明]", mapOf("custom" to "/custom.png"))
        val doc = Jsoup.parseBodyFragment(preview.html)
        assertEquals("摘要", doc.selectFirst("details summary")!!.text())
        assertEquals("正文", doc.selectFirst("details strong")!!.text())
        assertEquals("秘密", doc.selectFirst(".spoiler")!!.text())
        assertEquals("/custom.png", doc.selectFirst("img.emoji")!!.attr("src"))
        assertTrue(doc.text().contains("说明"))
    }
    @Test fun codeExamplesAreNeverConvertedToSiteMarkup() {
        val raw = "`[spoiler]秘密[/spoiler] :smile:`\n\n```\n[details=测试]\n:smile: ^[原样]\n[/details]\n```"
        val doc = Jsoup.parseBodyFragment(MarkdownPreviewRenderer.render(raw).html)
        assertEquals("[spoiler]秘密[/spoiler] :smile:", doc.selectFirst("p code")!!.text())
        assertTrue(doc.selectFirst("pre code")!!.wholeText().contains("^[原样]"))
        assertTrue(doc.select("details, .spoiler, img").isEmpty())
    }
    @Test fun uploadedImagesUseKnownDiscourseReadRouteAndUnsafeLinksAreBlocked() {
        val doc = Jsoup.parseBodyFragment(MarkdownPreviewRenderer.render("![图片](upload://Ab123.png)\n\n[链接](javascript:alert(1))\n\n<script>坏代码</script>").html)
        assertEquals("https://linux.do/uploads/short-url/Ab123.png", doc.selectFirst("img")!!.attr("src"))
        assertFalse(doc.selectFirst("a")!!.attr("href").startsWith("javascript:"))
        assertTrue(doc.select("script").isEmpty())
    }
    @Test fun advancedPluginsKeepTheirSourceAndExplainThePreviewLimit() {
        val preview = MarkdownPreviewRenderer.render("```mermaid\ngraph TD\nA --> B\n```\n\n[TOC]")
        assertTrue(preview.notices.isNotEmpty())
        assertTrue(Jsoup.parseBodyFragment(preview.html).text().contains("A --> B"))
        assertTrue(Jsoup.parseBodyFragment(preview.html).text().contains("[TOC]"))
    }
}
