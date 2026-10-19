package com.kudo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.theme.LocalDisplaySettings

@Composable
fun TopicTitleRow(topic: Topic, category: Category?, onClick: () -> Unit, onUser: (String) -> Unit) {
    if (topic.pinned && LocalDisplaySettings.current.compactPinnedTopics) {
        CompactPinnedTopicRow(topic, category, onClick)
        return
    }
    val resolvedCategory = category ?: LocalTopicPresentation.current.categories.firstOrNull { it.id == topic.categoryId }
    val transition = topicTransitionAnchor(topic.id, 0.dp, onClick)
    Column(Modifier.fillMaxWidth().then(transition.modifier).background(MaterialTheme.colorScheme.surface)
        .clickable(onClick = transition.open)
        .testTag("topic-title-${topic.id}")) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(topic.author, 44.dp) { topic.author?.username?.let(onUser) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (topic.pinned) Icon(Icons.Rounded.PushPin, "置顶", Modifier.padding(top = 3.dp).size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    if (topic.closed) Icon(Icons.Rounded.Lock, "已关闭", Modifier.padding(top = 3.dp).size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    TopicTitleText(topic.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.padding(top = 4.dp)) { TopicUnreadBadge(topic) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    resolvedCategory?.let { CategoryInline(it) }
                    topic.tagDetails.forEach { TopicTagChip(it) }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(compactNumber(topic.replies), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = if (topic.replies > 100) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("topic-count-${topic.id}"))
                Text(relativeTime(topic.bumpedAt), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    }
}
