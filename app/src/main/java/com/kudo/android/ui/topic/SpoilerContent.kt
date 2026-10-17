package com.kudo.android.ui.topic

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

@Composable
internal fun SpoilerRegion(contentKey: Any, tag: String, blurSupported: Boolean = Build.VERSION.SDK_INT >= 31,
    content: @Composable (() -> Unit) -> Unit) {
    SpoilerRegion(contentKey, tag, blurSupported, hideOnContentClick = true) { _, toggle -> content(toggle) }
}

@Composable
internal fun SpoilerRegion(contentKey: Any, tag: String, blurSupported: Boolean = Build.VERSION.SDK_INT >= 31,
    hideOnContentClick: Boolean, content: @Composable (Boolean, () -> Unit) -> Unit) {
    var revealed by rememberSaveable(contentKey) { mutableStateOf(false) }
    val semantics: SemanticsPropertyReceiver.() -> Unit = {
        contentDescription = "剧透内容"
        stateDescription = if (revealed) "剧透已显示" else "剧透已隐藏"
        if (!revealed || hideOnContentClick) onClick(if (revealed) "隐藏剧透" else "显示剧透") { revealed = !revealed; true }
    }
    Box(Modifier.fillMaxWidth().testTag(tag).then(if (revealed) Modifier.semantics(mergeDescendants = hideOnContentClick, properties = semantics)
        else Modifier.clearAndSetSemantics(semantics))
        .pointerInput(revealed, hideOnContentClick) { if (revealed && hideOnContentClick) detectTapGestures { revealed = false } }) {
        Box(Modifier.fillMaxWidth().then(if (!revealed && blurSupported) Modifier.blur(8.dp, BlurredEdgeTreatment.Rectangle) else Modifier)) {
            content(revealed) { revealed = !revealed }
        }
        if (!revealed) Box(Modifier.matchParentSize()
            .then(if (!revealed && !blurSupported) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .clickable { revealed = true })
    }
}

private fun spoilerPath(layout: TextLayoutResult?, ranges: List<AnnotatedString.Range<String>>): Path = Path().apply {
    if (layout != null) ranges.forEach { range ->
        if (range.start < range.end) addPath(layout.getPathForRange(range.start, range.end))
    }
}

internal fun redactedSpoilerText(text: AnnotatedString, hidden: List<AnnotatedString.Range<String>>): String = buildString {
    var index = 0
    while (index < text.length) {
        val region = hidden.filter { index >= it.start && index < it.end }.maxByOrNull { it.end }
        if (region == null) append(text[index++]) else { append("剧透内容"); index = region.end }
    }
}

@Composable
internal fun SpoilerArticleText(article: InlineArticle, contentKey: String, tag: String, style: TextStyle, color: Color,
    blurSupported: Boolean = Build.VERSION.SDK_INT >= 31, modifier: Modifier = Modifier,
    textIndent: TextIndent = TextIndent.None) {
    val ranges = remember(article) { article.text.getStringAnnotations("spoiler", 0, article.text.length).filter { it.start < it.end } }
    var shown by rememberSaveable(contentKey) { mutableStateOf(emptyList<String>()) }
    val hidden = ranges.filter { it.item !in shown }
    var layout by remember(article) { mutableStateOf<TextLayoutResult?>(null) }
    val inline = remember(article.emojis) { article.emojis.mapValues { (_, emoji) ->
        InlineTextContent(Placeholder(1.25.em, 1.25.em, PlaceholderVerticalAlign.TextCenter)) {
            SiteEmoji(emoji.name, Modifier.fillMaxSize(), emoji.url)
        }
    } }
    fun toggle(id: String) { shown = if (id in shown) shown - id else shown + id }
    val safeText = remember(article, hidden) { redactedSpoilerText(article.text, hidden) }
    val semantics = if (ranges.isEmpty()) Modifier else Modifier.clearAndSetSemantics {
        text = AnnotatedString(safeText)
        stateDescription = if (hidden.isEmpty()) "剧透已显示" else "剧透已隐藏"
        onClick(if (hidden.isEmpty()) "隐藏剧透" else "显示剧透") { toggle((hidden.firstOrNull() ?: ranges.first()).item); true }
        customActions = ranges.mapIndexed { index, range -> CustomAccessibilityAction(
            (if (range.item in shown) "隐藏" else "显示") + "第 ${index + 1} 处剧透") { toggle(range.item); true } }
    }
    Box(Modifier.fillMaxWidth().testTag(tag).then(semantics).then(modifier)
        .pointerInput(article.text, shown) {
            if (ranges.isEmpty()) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val currentLayout = layout ?: return@awaitEachGesture
                val offset = currentLayout.getOffsetForPosition(down.position)
                val candidates = ranges.filter { offset >= it.start && offset < it.end }
                val target = candidates.filter { it.item !in shown }.maxByOrNull { it.end - it.start }
                    ?: candidates.minByOrNull { it.end - it.start } ?: return@awaitEachGesture
                // Only glyph/selection bounds are hit targets; nearby paragraph whitespace remains normal.
                if (!currentLayout.getPathForRange(target.start, target.end).getBounds().contains(down.position)) return@awaitEachGesture
                down.consume()
                var moved = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (event.changes.count { it.pressed } > 1 || change == null ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (change != null && !change.pressed && !moved) { change.consume(); toggle(target.item) }
                } while (event.changes.any { it.pressed })
            }
        }) {
        val body: @Composable () -> Unit = {
            Text(article.text, Modifier.fillMaxWidth().drawWithContent {
                if (hidden.isEmpty()) drawContent()
                else if (layout != null) clipPath(spoilerPath(layout, hidden), ClipOp.Difference) { this@drawWithContent.drawContent() }
            }, inlineContent = inline, style = style.copy(textIndent = textIndent), color = color, onTextLayout = { layout = it })
        }
        if (hidden.isEmpty()) SelectionContainer(LocalPostQuoteSelection.current) { body() } else body()
        if (hidden.isNotEmpty()) {
            if (blurSupported) {
                // Keep inline emoji annotations but remove links from this visual-only layer.
                val blurredText = remember(article) { buildAnnotatedString {
                    append(article.text.text)
                    article.text.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
                    article.text.paragraphStyles.forEach { addStyle(it.item, it.start, it.end) }
                    article.text.getStringAnnotations(0, article.text.length).forEach { addStringAnnotation(it.tag, it.item, it.start, it.end) }
                } }
                Text(blurredText, Modifier.fillMaxWidth().drawWithContent {
                    if (layout != null) clipPath(spoilerPath(layout, hidden)) { this@drawWithContent.drawContent() }
                }.blur(7.dp, BlurredEdgeTreatment.Unbounded).clearAndSetSemantics {},
                    inlineContent = inline, style = style.copy(textIndent = textIndent), color = color)
            } else Box(Modifier.matchParentSize().drawWithContent { drawPath(spoilerPath(layout, hidden), color.copy(alpha = 1f)) })
        }
    }
}
