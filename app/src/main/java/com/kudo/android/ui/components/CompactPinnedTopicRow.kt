package com.kudo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*

@Composable
internal fun CompactPinnedTopicRow(topic: Topic, category: Category?, onClick: () -> Unit) {
    val resolved = category ?: LocalTopicPresentation.current.categories.firstOrNull { it.id == topic.categoryId }
    val categoryColor = resolved?.color?.let { value ->
        runCatching { Color(android.graphics.Color.parseColor("#${value.removePrefix("#")}")) }.getOrNull()
    } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val blue = Color(0xFF3D8BFF)
    val transition = topicTransitionAnchor(topic.id, 12.dp, onClick)
    Row(Modifier.fillMaxWidth().then(transition.modifier).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
        .clickable(onClick = transition.open).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp)
        .testTag("topic-pinned-${topic.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(Icons.Rounded.PushPin, "置顶", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(CategoryIcons.get(resolved?.icon.orEmpty()), resolved?.let { "分类图标：${it.name}" } ?: "分类图标",
            Modifier.size(15.dp), tint = categoryColor)
        TopicTitleText(topic.title, Modifier.weight(1f).testTag("topic-pinned-title-${topic.id}"),
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (topic.unseen) Box(Modifier.size(7.dp).background(blue, CircleShape)
            .testTag("topic-pinned-unseen-${topic.id}").semantics { contentDescription = "未看过的话题" })
        if (topic.unreadPosts > 0) Text(compactNumber(topic.unreadPosts),
            Modifier.testTag("topic-unread-${topic.id}").semantics { contentDescription = "${topic.unreadPosts} 条新回复" },
            color = blue, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
