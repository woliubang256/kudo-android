package com.kudo.android.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** All offsets use UTF-16, just like Compose's selection and IME APIs. */
internal fun TextFieldValue.wrapMarkdown(open: String, close: String = open, placeholder: String = "文字"): TextFieldValue {
    val a = selection.min; val b = selection.max
    val selected = text.substring(a, b)
    if (a >= open.length && text.substring(a - open.length, a) == open && text.substring(b).startsWith(close)) {
        return TextFieldValue(text.removeRange(b, b + close.length).removeRange(a - open.length, a),
            TextRange(selection.start - open.length, selection.end - open.length))
    }
    if (selected.startsWith(open) && selected.endsWith(close) && selected.length >= open.length + close.length) {
        return replaceMarkdown(selected.substring(open.length, selected.length - close.length), select = true)
    }
    val body = selected.ifEmpty { placeholder }
    return TextFieldValue(text.replaceRange(a, b, open + body + close),
        if (selection.reversed) TextRange(a + open.length + body.length, a + open.length)
        else TextRange(a + open.length, a + open.length + body.length))
}

internal fun TextFieldValue.replaceMarkdown(insert: String, select: Boolean = false): TextFieldValue {
    val start = selection.min
    return TextFieldValue(text.replaceRange(start, selection.max, insert),
        if (select) TextRange(start, start + insert.length) else TextRange(start + insert.length))
}

internal fun TextFieldValue.blockMarkdown(open: String, close: String = "", placeholder: String = "内容"): TextFieldValue {
    val a = selection.min; val b = selection.max
    val before = if (a > 0 && text[a - 1] != '\n') "\n\n" else ""
    val after = if (b < text.length && text[b] != '\n') "\n\n" else "\n"
    val body = text.substring(a, b).ifEmpty { placeholder }
    return TextFieldValue(text.replaceRange(a, b, before + open + body + close + after),
        TextRange(a + before.length + open.length, a + before.length + open.length + body.length))
}

internal enum class MarkdownLines { QUOTE, BULLET, NUMBERED, TASK, H1, H2, H3, H4, PARAGRAPH }

internal fun TextFieldValue.lineMarkdown(kind: MarkdownLines): TextFieldValue {
    val start = text.lastIndexOf('\n', (selection.min - 1).coerceAtLeast(-1)) + 1
    val last = if (!selection.collapsed && selection.max > start && text[selection.max - 1] == '\n') selection.max - 1 else selection.max
    val end = text.indexOf('\n', last).takeIf { it >= 0 } ?: text.length
    val lines = text.substring(start, end).split('\n')
    val target = when (kind) {
        MarkdownLines.QUOTE -> Regex("^> ?")
        MarkdownLines.BULLET -> Regex("^[-+*] (?!\\[[ xX]] )")
        MarkdownLines.NUMBERED -> Regex("^\\d+\\. ")
        MarkdownLines.TASK -> Regex("^- \\[([ xX])] ")
        MarkdownLines.H1, MarkdownLines.H2, MarkdownLines.H3, MarkdownLines.H4 -> Regex("^${"#".repeat(kind.ordinal - MarkdownLines.H1.ordinal + 1)} ")
        MarkdownLines.PARAGRAPH -> Regex("^#{1,6} ")
    }
    val remove = kind == MarkdownLines.PARAGRAPH || lines.all { target.containsMatchIn(it) }
    val replacement = lines.mapIndexed { i, line ->
        if (remove) line.replaceFirst(target, "") else {
            val prefix = when (kind) {
                MarkdownLines.QUOTE -> "> "
                MarkdownLines.BULLET -> "- "
                MarkdownLines.NUMBERED -> "${i + 1}. "
                MarkdownLines.TASK -> "- [ ] "
                else -> "#".repeat(kind.ordinal - MarkdownLines.H1.ordinal + 1) + " "
            }
            val oldPrefix = when (kind) {
                MarkdownLines.BULLET, MarkdownLines.NUMBERED, MarkdownLines.TASK -> Regex("^(?:[-+*] (?:\\[[ xX]] )?|\\d+\\. )")
                MarkdownLines.H1, MarkdownLines.H2, MarkdownLines.H3, MarkdownLines.H4 -> Regex("^#{1,6} ")
                else -> null
            }
            prefix + (oldPrefix?.let { line.replaceFirst(it, "") } ?: line)
        }
    }.joinToString("\n")
    val updated = text.replaceRange(start, end, replacement)
    return TextFieldValue(updated, if (selection.collapsed) TextRange((selection.start + replacement.length - (end - start)).coerceIn(start, start + replacement.length))
        else if (selection.reversed) TextRange(start + replacement.length, start) else TextRange(start, start + replacement.length))
}

internal fun TextFieldValue.linkMarkdown(label: String, url: String): TextFieldValue =
    replaceMarkdown("[${label.replace("[", "\\[").replace("]", "\\]")}](${url.replace(" ", "%20").replace("(", "%28").replace(")", "%29")})")

/** Rebase an insertion point through one user edit, without capturing an old draft snapshot. */
internal fun rebaseMarkdownOffset(old: String, new: String, offset: Int): Int {
    if (old == new) return offset.coerceIn(0, new.length)
    var prefix = 0
    while (prefix < minOf(old.length, new.length) && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(old.length, new.length) - prefix && old[old.length - suffix - 1] == new[new.length - suffix - 1]) suffix++
    return when {
        offset < prefix -> offset
        offset >= old.length - suffix -> offset + new.length - old.length
        else -> new.length - suffix
    }.coerceIn(0, new.length)
}

internal fun TextFieldValue.syncMarkdown(raw: String): TextFieldValue = if (text == raw) this else TextFieldValue(raw,
    TextRange(rebaseMarkdownOffset(text, raw, selection.start), rebaseMarkdownOffset(text, raw, selection.end)))

internal fun insertUploadedMarkdown(raw: String, offset: Int, markdown: String): String =
    TextFieldValue(raw, TextRange(offset.coerceIn(0, raw.length))).blockMarkdown("", placeholder = markdown).text

/** Lives with the draft owner so closing/reopening the editor during upload stays safe. */
internal class EditorImageInsertion {
    private var offset: Int? = null
    fun prepare(raw: String, selection: TextRange) { offset = selection.max.coerceIn(0, raw.length) }
    fun edited(old: String, new: String) { offset = offset?.let { rebaseMarkdownOffset(old, new, it) } }
    fun insert(raw: String, markdown: String): String {
        val target = offset ?: raw.length
        offset = null
        return insertUploadedMarkdown(raw, target, markdown)
    }
}
