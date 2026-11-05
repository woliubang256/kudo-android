package com.kudo.android.ui.apps

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.data.MiniAppEntry
import com.kudo.android.ui.components.MiniAppStars

internal fun miniAppCompactCount(count: Long): String {
    val unit = when {
        count >= 100_000_000L -> 100_000_000L
        count >= 10_000L -> 10_000L
        else -> return count.toString()
    }
    val tenth = count % unit / (unit / 10)
    return "${count / unit}${if (tenth > 0) ".$tenth" else ""}${if (unit == 10_000L) "万" else "亿"}"
}

@Composable
internal fun AppSquareStatistics(app: MiniAppEntry, modifier: Modifier = Modifier) {
    val average = app.ratingAverage?.takeIf { it > 0 && app.ratingCount != 0L }
    val ratings = app.ratingCount?.takeIf { it > 0 }
    if (average == null && ratings == null && app.usageCount == null) return
    Column(modifier.testTag("app-statistics-${app.id}"), horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (average != null || ratings != null) Row(Modifier.testTag("app-rating-${app.id}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (average != null) MiniAppStars(average, size = 12.dp, gap = 1.dp)
            if (ratings != null) Text("${miniAppCompactCount(ratings)} 评", Modifier.clearAndSetSemantics {
                contentDescription = "$ratings 人评分"
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        app.usageCount?.let { count ->
            Text("${miniAppCompactCount(count)} 人次", Modifier.testTag("app-usage-${app.id}").clearAndSetSemantics {
                contentDescription = "使用 $count 人次"
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
