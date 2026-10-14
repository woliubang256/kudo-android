package com.kudo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

internal data class TopicPresentationMetadata(
    val categories: List<Category> = emptyList(),
    val tagIcons: Map<String, TagIconStyle> = DefaultTagIcons,
    val refreshCategories: () -> Unit = {},
)
internal val LocalTopicPresentation = staticCompositionLocalOf { TopicPresentationMetadata() }

@Composable
internal fun TopicPresentationEnvironment(repository: CommunityRepository, accountId: Int?, content: @Composable () -> Unit) {
    val reload = remember(repository, accountId) { Channel<Unit>(Channel.CONFLATED) }
    val metadata = key(repository, accountId) {
        produceState(TopicPresentationMetadata(refreshCategories = { reload.trySend(Unit) }), repository, accountId) {
            repository.useCategoryAccount(accountId)
            coroutineScope {
                launch {
                    repository.categoryCatalog.collect { value = value.copy(categories = it) }
                }
                launch {
                    reload.trySend(Unit)
                    for (request in reload) {
                        repeat(2) { attempt ->
                            if (repository.categoryCatalog.value.isEmpty()) {
                                try { withTimeoutOrNull(8000) { withContext(Dispatchers.IO) { repository.categories() } } }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { /* Reading stays available; retry on the next entry or resume. */ }
                                if (attempt == 0 && repository.categoryCatalog.value.isEmpty()) delay(750)
                            }
                        }
                    }
                }
                launch {
                    try {
                        withTimeoutOrNull(8000) { withContext(Dispatchers.IO) { repository.tagIcons() } }
                            ?.takeIf { it.isNotEmpty() }?.let { value = value.copy(tagIcons = it) }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Keep the checked bundled tag catalog. */ }
                }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload.trySend(Unit) }
    CompositionLocalProvider(LocalTopicPresentation provides metadata.value, content = content)
}

@Composable
private fun iconColor(value: String): Color = runCatching {
    Color(android.graphics.Color.parseColor("#${value.removePrefix("#")}"))
}.getOrDefault(MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun CategoryInline(category: Category, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .testTag("category-inline-${category.id}")) {
        Icon(CategoryIcons.get(category.icon), "分类图标：${category.name}", Modifier.size(12.dp), iconColor(category.color))
        Text(category.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun TopicAuthorMetadata(time: String, category: Category?, edited: Boolean = false, history: (() -> Unit)? = null) {
    val timestamp = relativeTime(time)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (timestamp.isNotBlank()) Text(timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        category?.let {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (timestamp.isNotBlank()) MetadataSeparator()
                CategoryInline(it)
            }
        }
        if (edited) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (timestamp.isNotBlank() || category != null) MetadataSeparator()
            Row(Modifier.clickable(enabled = history != null, role = Role.Button) { history?.invoke() }
                .padding(vertical = 4.dp).testTag("edited-history"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("已编辑", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MetadataSeparator() { Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }

@Composable
internal fun TopicTagChip(tag: TopicTag, onClick: (() -> Unit)? = null) {
    val style = tag.iconStyle(LocalTopicPresentation.current.tagIcons)
    Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
        .testTag("topic-tag-${tag.name}").padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        style?.let { Icon(CategoryIcons.get(it.icon), "标签图标：${tag.name}", Modifier.size(13.dp), iconColor(it.color)) }
        Text(tag.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun TopicTags(tags: List<TopicTag>, modifier: Modifier = Modifier, onTagClick: ((TopicTag) -> Unit)? = null) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        tags.forEach { tag -> TopicTagChip(tag, onClick = onTagClick?.let { click -> { click(tag) } }) }
    }
}
