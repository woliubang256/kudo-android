package com.kudo.android.ui.topic

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp

internal fun DrawScope.drawMentionHighlights(layout: TextLayoutResult?, text: AnnotatedString, color: Color,
    hidden: List<AnnotatedString.Range<String>> = emptyList()) {
    if (layout == null) return
    text.getStringAnnotations("mention", 0, text.length).forEach { range ->
        if (range.start >= range.end || hidden.any { it.start < range.end && it.end > range.start }) return@forEach
        val first = layout.getLineForOffset(range.start)
        val last = layout.getLineForOffset(range.end - 1)
        for (line in first..last) {
            val start = maxOf(range.start, layout.getLineStart(line))
            val end = minOf(range.end, layout.getLineEnd(line, visibleEnd = true))
            if (start >= end) continue
            val bounds = layout.getPathForRange(start, end).getBounds()
            val inset = minOf(2.dp.toPx(), bounds.height * .15f)
            drawRoundRect(color, Offset(bounds.left - 2.dp.toPx(), bounds.top + inset),
                Size(bounds.width + 4.dp.toPx(), (bounds.height - inset * 2).coerceAtLeast(0f)), CornerRadius(4.dp.toPx()))
        }
    }
}
