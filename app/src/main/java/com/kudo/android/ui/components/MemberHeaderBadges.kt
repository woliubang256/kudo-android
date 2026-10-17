package com.kudo.android.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kudo.android.data.PostHeaderBadge

@Composable
internal fun MemberHeaderBadge(badge: PostHeaderBadge, modifier: Modifier = Modifier) {
    val label = badge.name.ifBlank { badge.description }.ifBlank { "徽章" }
    val decorated = modifier.testTag("post-header-badge-${badge.id}").semantics { contentDescription = label }
    if (badge.image.isNotBlank()) {
        AvatarImage(mediaUrl(badge.image), pixels = with(LocalDensity.current) { 16.dp.roundToPx() }, animate = false,
            contentScale = ContentScale.Fit, modifier = decorated.size(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    } else if (badge.icon in CategoryIcons.supportedNames) {
        val color = when (badge.type) {
            1 -> Color(0xFFE5A100)
            2 -> Color(0xFF9A9A9A)
            3 -> Color(0xFFCD7F32)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(CategoryIcons.get(badge.icon), null, decorated.size(16.dp), tint = color)
    } else if (label.isNotBlank()) {
        Text(label, decorated, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
