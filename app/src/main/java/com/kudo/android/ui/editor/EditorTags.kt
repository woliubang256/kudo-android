package com.kudo.android.ui.editor

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.data.TopicTag
import com.kudo.android.ui.components.*
import com.kudo.android.ui.components.liquid.LiquidModalAction
import com.kudo.android.ui.theme.LogoYellow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

@Composable
internal fun EditorTags(visibility: MutableTransitionState<Boolean>, categoryId: Int, tags: List<String>,
    search: suspend (String, Int, List<TopicTag>) -> List<TopicTag>, sending: Boolean, apply: (List<String>) -> Unit) {
    if (!visibility.currentState && !visibility.targetState) return
    var selected by remember(categoryId) { mutableStateOf(tags.distinct().map { TopicTag(it) }) }
    var query by remember(categoryId) { mutableStateOf("") }
    var results by remember(categoryId) { mutableStateOf(emptyList<TopicTag>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val currentSearch by rememberUpdatedState(search)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun dismiss() {
        if (!sending) { focus.clearFocus(force = true); keyboard?.hide(); visibility.targetState = false }
    }
    LaunchedEffect(query, selected, categoryId, attempt, visibility.targetState) {
        if (!visibility.targetState) return@LaunchedEffect
        val requestJob = currentCoroutineContext()
        loading = true; error = null; results = emptyList()
        try {
            if (query.isNotBlank()) delay(250)
            val found = currentSearch(query, categoryId, selected)
            ensureActive()
            results = found.distinctBy { it.name }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            if (currentCoroutineContext().isActive) error = failure.message ?: "标签加载失败，请重试"
        }
        finally { if (requestJob.isActive) loading = false }
    }
    CommunitySheet(visibility, "标签", ::dismiss, showTitle = true, dismissEnabled = !sending,
        scrollContent = false, showActions = true, maxHeightFraction = .85f, actions = {
            LiquidModalAction("取消", ::dismiss, Modifier.weight(1f).testTag("editor-tags-cancel"))
            LiquidModalAction("确定", {
                if (!sending && visibility.targetState) { apply(selected.map { it.name }); dismiss() }
            }, Modifier.weight(1f).testTag("editor-tags-confirm"), primary = true)
        }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            CommunityTextField(query, { query = it }, Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
                .testTag("editor-tag-query"), placeholder = "搜索标签", enabled = !sending, singleLine = true)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("可多选 · 已选 ${selected.size} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 360.dp).testTag("editor-tag-results")) {
            items(selected, key = { it.name }) { tag ->
                EditorTagRow(tag, true, !sending) { selected = selected.filterNot { it.name == tag.name } }
            }
            if (selected.isNotEmpty()) item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            if (loading) item {
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    CommunityLoadingIndicator(Modifier.size(20.dp))
                    Text("正在加载标签…", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            error?.let { message -> item {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    CommunityButton("重试", { attempt++ }, enabled = !sending, compact = true)
                }
            } }
            val available = results.filterNot { result -> selected.any { it.name == result.name } }
            items(available, key = { it.name }) { tag ->
                EditorTagRow(tag, false, !sending) { selected = selected + tag }
            }
            if (!loading && error == null && available.isEmpty()) item {
                Text(if (selected.isEmpty()) "没有可选标签，试试搜索其他名称" else "没有更多可选标签", Modifier.padding(vertical = 18.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EditorTagRow(tag: TopicTag, selected: Boolean, enabled: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { toggle() })
        .heightIn(min = 52.dp).padding(vertical = 12.dp).testTag("editor-tag-${tag.name}"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Tag, null, Modifier.size(20.dp), tint = if (selected) LogoYellow else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(tag.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge,
            color = if (selected) LogoYellow else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(22.dp), tint = LogoYellow)
    }
}
