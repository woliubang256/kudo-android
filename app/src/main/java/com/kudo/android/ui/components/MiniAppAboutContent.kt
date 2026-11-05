package com.kudo.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import coil3.compose.SubcomposeAsyncImage
import com.kudo.android.data.MiniAppDetails
import com.kudo.android.ui.theme.LogoYellow

@Composable
internal fun MiniAppAboutContent(info: MiniAppDetails) {
    Row(Modifier.fillMaxWidth().testTag("mini-app-about"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SubcomposeAsyncImage(info.iconUrl, null, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))
            .semantics { contentDescription = "应用图标" },
            loading = { Icon(Icons.Rounded.Apps, null, Modifier.padding(14.dp)) },
            error = { Icon(Icons.Rounded.Apps, null, Modifier.padding(14.dp)) })
        Text(info.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
    }
    if (info.description.isNotBlank()) Text(info.description, style = MaterialTheme.typography.bodyMedium)
    MiniAppRating(info.ratingAverage, info.ratingCount)
    if (info.version.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("版本", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(info.version, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
    if (info.publisher.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("发布人", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Avatar(info.publisherMember, 28.dp, presentation = AvatarPresentation.PLAIN)
            Text(info.publisher, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MiniAppRating(average: Double?, count: Long?) {
    if (average == null && count == null) return
    Row(Modifier.fillMaxWidth().testTag("mini-app-rating"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (average != null) MiniAppStars(average, unrated = count == 0L)
        if (count != null) Text(if (count == 0L) "暂无评分" else "$count 人评分",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MiniAppStars(average: Double, size: Dp = 22.dp, gap: Dp = 2.dp, unrated: Boolean = false) {
    Row(Modifier.clearAndSetSemantics {
        contentDescription = if (unrated) "暂无评分" else "评分 $average 分，满分 5 分"
    }, horizontalArrangement = Arrangement.spacedBy(gap)) {
        repeat(5) { index ->
            val fraction = if (unrated) 0f else (average - index).coerceIn(0.0, 1.0).toFloat()
            Box(Modifier.size(size)) {
                Icon(Icons.Rounded.Star, null, Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .2f))
                Icon(Icons.Rounded.Star, null, Modifier.fillMaxSize().drawWithContent {
                    clipRect(right = this.size.width * fraction) { this@drawWithContent.drawContent() }
                }, tint = LogoYellow)
            }
        }
    }
}
