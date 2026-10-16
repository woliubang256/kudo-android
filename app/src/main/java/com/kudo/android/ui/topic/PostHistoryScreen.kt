package com.kudo.android.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun historyTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

@Composable
fun PostHistoryScreen(postId: Int, state: PostHistoryState, reload: () -> Unit, more: () -> Unit, back: () -> Unit,
    login: () -> Unit, user: (String) -> Unit, openLink: (String) -> Unit, image: (String) -> Unit = {}) {
    val scroll = rememberLazyListState()
    LaunchedEffect(scroll, state.versions.size, state.previous, state.loadingMore, state.moreError) {
        if (state.previous == null || state.loadingMore || state.moreError != null) return@LaunchedEffect
        snapshotFlow { scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.distinctUntilChanged().collect { index ->
            if (index != null && index >= state.versions.lastIndex) more()
        }
    }
    MaterialPageScaffold(modifier = Modifier.testTag("post-history-page"), header = { PageTopBar("编辑历史", back) }) { padding ->
        when {
            state.loading -> Box(Modifier.padding(padding)) { LoadingRow() }
            state.error != null -> Box(Modifier.padding(padding)) { ErrorState(state.error, reload, login, back) }
            else -> LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("post-history-list").quickScrollTarget(scroll),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp)) {
                itemsIndexed(state.versions, key = { _, version -> version.number }) { index, version ->
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)
                        .testTag("history-version-${version.number}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(version.editor, 36.dp, presentation = AvatarPresentation.PUBLIC)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(version.editor.displayName.ifBlank { "编辑者" },
                                    Modifier.clickable(enabled = version.editor.username.isNotBlank()) { user(version.editor.username) },
                                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(historyTime(version.createdAt), if (version.original) "原始版本" else if (index == 0) "最新版本" else "编辑")
                                    .filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (version.title.isNotBlank()) Text(version.title, style = MaterialTheme.typography.titleMedium)
                        if (version.cooked != null) RichContent(version.cooked, image, openLink, galleryKey = "history-$postId-${version.number}")
                        else Text(version.unavailable ?: "此版本正文暂不可用", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (version.reason.isNotBlank()) Text("编辑说明：${version.reason}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.fillMaxWidth().height(10.dp))
                }
                item {
                    when {
                        state.loadingMore -> LoadingRow()
                        state.moreError != null -> ErrorState(state.moreError, more, login)
                        state.previous != null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CommunityButton("加载更早版本", more, compact = true)
                        }
                        else -> Text("没有更多内容", Modifier.fillMaxWidth().padding(24.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
    }
}
