package com.kudo.android.ui.editor

import com.kudo.android.data.AvatarMetadataCache
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode

internal data class MarkdownPreview(val html: String, val notices: List<String>)

/** Local read-only approximation. Never cook by creating a server draft/post. */
internal object MarkdownPreviewRenderer {
    private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create())
    private val parser = Parser.builder().extensions(extensions).includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).sanitizeUrls(true).build()

    fun render(raw: String, emojis: Map<String, String> = emptyMap()): MarkdownPreview {
        val notices = linkedSetOf<String>()
        val lineStarts = mutableListOf(0).apply { raw.forEachIndexed { index, c -> if (c == '\n') add(index + 1) } }
        val protected = mutableListOf<IntRange>()
        val original = parser.parse(raw)
        fun protect(node: Node) { node.sourceSpans.forEach { span ->
            val start = lineStarts[span.lineIndex] + span.columnIndex
            protected += start until (start + span.length)
        } }
        original.accept(object : AbstractVisitor() {
            override fun visit(node: Code) { protect(node) }
            override fun visit(node: FencedCodeBlock) { protect(node) }
            override fun visit(node: IndentedCodeBlock) { protect(node) }
        })
        val notes = mutableListOf<String>()
        fun convert(segment: String): String {
            var text = segment
            text = Regex("(?<!\\\\)\\[details(?:=([^]\\n]*))?]", RegexOption.IGNORE_CASE).replace(text) {
                "\n\n<details>\n<summary>${Entities.escape(it.groupValues[1].trim().removeSurrounding("\"").ifBlank { "详细内容" })}</summary>\n\n"
            }
            text = text.replace(Regex("(?<!\\\\)\\[/details]", RegexOption.IGNORE_CASE), "\n\n</details>\n\n")
                .replace(Regex("(?<!\\\\)\\[spoiler]", RegexOption.IGNORE_CASE), "<span class=\"spoiler\">")
                .replace(Regex("(?<!\\\\)\\[/spoiler]", RegexOption.IGNORE_CASE), "</span>")
            text = Regex("(?<!\\\\)\\^\\[([^]\\n]+)]").replace(text) { notes += it.groupValues[1]; "<sup>${notes.size}</sup>" }
            return text
        }
        // BBCode conversion never touches fenced, indented or inline code source spans.
        val converted = buildString {
            var cursor = 0
            protected.sortedBy { it.first }.forEach { range ->
                if (range.first >= cursor) {
                    append(convert(raw.substring(cursor, range.first))); append(raw.substring(range.first, range.last + 1)); cursor = range.last + 1
                }
            }
            append(convert(raw.substring(cursor)))
        }
        val document = parser.parse(converted)
        document.accept(object : AbstractVisitor() {
            override fun visit(node: Image) {
                val destination = node.destination
                if (destination.startsWith("upload://")) {
                    val short = destination.removePrefix("upload://")
                    if (short.matches(Regex("[A-Za-z0-9]+(?:\\.[A-Za-z0-9]+)?"))) node.destination = "https://linux.do/uploads/short-url/$short"
                }
                visitChildren(node)
            }
            override fun visit(node: FencedCodeBlock) {
                if (node.info.lowercase().trim() in setOf("mermaid", "graphviz", "math")) notices += "公式与图表显示源码，发布后由站点渲染。"
            }
        })
        if (Regex("(?im)^\\s*(?:\\$\\$|\\[graphviz)").containsMatchIn(raw)) notices += "公式与图表显示源码，发布后由站点渲染。"
        if (Regex("(?i)\\[TOC]|\\[date[= ]").containsMatchIn(raw)) notices += "目录、日期等站点插件显示原始标记，最终以发布效果为准。"
        val body = Jsoup.parseBodyFragment(renderer.render(document)).body()
        body.select("script, style, iframe, object, embed").remove()
        fun emojiText(element: Element) {
            if (element.normalName() in setOf("pre", "code")) return
            element.childNodes().toList().forEach { node ->
                if (node is Element) emojiText(node)
                if (node is TextNode) {
                    val text = node.wholeText
                    var cursor = 0
                    Regex(":([A-Za-z0-9_+\\-]+(?::t[2-6])?):").findAll(text).forEach { match ->
                        val name = match.groupValues[1]
                        val url = emojis[name] ?: AvatarMetadataCache.standardEmojiUrl(name) ?: return@forEach
                        if (match.range.first > cursor) node.before(TextNode(text.substring(cursor, match.range.first)))
                        node.before(Element("img").addClass("emoji").attr("src", url).attr("alt", ":$name:"))
                        cursor = match.range.last + 1
                    }
                    if (cursor > 0) { if (cursor < text.length) node.before(TextNode(text.substring(cursor))); node.remove() }
                }
            }
        }
        emojiText(body)
        if (notes.isNotEmpty()) {
            body.appendElement("hr")
            notes.forEachIndexed { index, note -> body.append("<p><sup>${index + 1}</sup> ${renderer.render(parser.parse(note))}</p>") }
        }
        return MarkdownPreview(body.html(), notices.toList())
    }
}
