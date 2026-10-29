package com.kudo.android.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.kudo.android.data.validMentionUsername
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

internal data class MentionQuery(val start: Int, val end: Int, val term: String)

private val mentionParser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
private fun mentionCharacter(char: Char) = char.isLetterOrDigit() || char in "_.-"
private fun mentionBoundary(text: String, start: Int): Boolean = start == 0 ||
    (!text[start - 1].isLetterOrDigit() && text[start - 1] !in "_.-+/%\\@")

private fun protectedMentionRanges(raw: String): List<IntRange> {
    val starts = mutableListOf(0).apply { raw.forEachIndexed { index, char -> if (char == '\n') add(index + 1) } }
    return buildList {
        fun protect(node: Node) { node.sourceSpans.forEach { span ->
            val start = starts[span.lineIndex] + span.columnIndex
            add(start until start + span.length)
        } }
        mentionParser.parse(raw).accept(object : AbstractVisitor() {
            override fun visit(node: Code) = protect(node)
            override fun visit(node: FencedCodeBlock) = protect(node)
            override fun visit(node: IndentedCodeBlock) = protect(node)
            override fun visit(node: Link) = protect(node)
            override fun visit(node: Image) = protect(node)
            override fun visit(node: HtmlInline) = protect(node)
            override fun visit(node: HtmlBlock) = protect(node)
        })
        Regex("<(a|code|pre|script|style)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(raw).forEach { add(it.range) }
    }
}

internal fun TextFieldValue.mentionQuery(): MentionQuery? {
    if (!selection.collapsed) return null
    val cursor = selection.start.coerceIn(0, text.length)
    var start = cursor
    while (start > 0 && mentionCharacter(text[start - 1])) start--
    if (start == 0 || text[start - 1] != '@') return null
    start--
    if (!mentionBoundary(text, start) || cursor - start > 61) return null
    if (protectedMentionRanges(text).any { start in it }) return null
    val prefix = text.substring(text.lastIndexOf('\n', start).let { it + 1 }, start)
    var ticks = 0
    Regex("(?<!\\\\)`+").findAll(prefix).forEach { run ->
        if (ticks == 0) ticks = run.value.length else if (ticks == run.value.length) ticks = 0
    }
    if (ticks != 0) return null
    var end = cursor
    while (end < text.length && mentionCharacter(text[end])) end++
    composition?.takeUnless { it.collapsed }?.let { composing ->
        if (composing.min < start || composing.max > end || text.substring(composing.min, composing.max).any {
                it.code > 127 || (!mentionCharacter(it) && it != '@')
            }) return null
    }
    return MentionQuery(start, end, text.substring(start + 1, cursor))
}

internal fun TextFieldValue.completeMention(query: MentionQuery, username: String): TextFieldValue {
    if (!validMentionUsername(username) || mentionQuery() != query) return this
    val mention = "@$username"
    val space = if (text.getOrNull(query.end) == ' ') "" else " "
    return TextFieldValue(text.replaceRange(query.start, query.end, mention + space), TextRange(query.start + mention.length + 1))
}

internal fun mentionPreviewMarkup(raw: String): String {
    val protected = protectedMentionRanges(raw)
    val pattern = Regex("@([\\p{L}\\p{N}_][\\p{L}\\p{N}_.-]{0,59})(?![\\p{L}\\p{N}_.-])")
    return pattern.replace(raw) { match ->
        val start = match.range.first
        val name = match.groupValues[1].trimEnd('.')
        if (!mentionBoundary(raw, start) || protected.any { start in it } || name.isEmpty()) match.value
        else "<a class=\"mention\" href=\"https://linux.do/u/$name\">@$name</a>" + match.value.drop(1 + name.length)
    }
}
