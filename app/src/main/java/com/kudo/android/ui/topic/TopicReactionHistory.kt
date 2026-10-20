package com.kudo.android.ui.topic

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LogoYellow
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.distinctUntilChanged

internal val LocalReactionHistory = staticCompositionLocalOf<(Post) -> Unit> { {} }

@Composable
internal fun TopicReactionHistoryHost(accountKey: Any?, screenKey: Any?, glass: GlassMode,
    load: suspend (Post, Int) -> ReactionHistory, user: (String) -> Unit,
    retainedController: ReactionHistoryController? = null, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val currentLoad by rememberUpdatedState(load)
    val controller = retainedController ?: remember(accountKey, screenKey) { ReactionHistoryController(scope) { post, page -> currentLoad(post, page) } }
    DisposableEffect(controller) { onDispose { if (retainedController == null) controller.dismiss() else controller.stop() } }
    LaunchedEffect(controller) {
        val state = controller.state
        if (state.post != null && state.history == null && state.error == null && !state.loading) controller.retry()
    }
    val visibility = remember(controller) { MutableTransitionState(controller.state.post != null) }
    val current = controller.state
    var retained by remember(controller) { mutableStateOf(current) }
    SideEffect { if (current.post != null) retained = current }
    val state = if (current.post != null) current else retained
    LaunchedEffect(current.post?.id) { visibility.targetState = current.post != null }
    val showing = current.post != null || visibility.currentState || visibility.targetState
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)
            .then(if (showing) Modifier.clearAndSetSemantics {} else Modifier)
            .focusProperties { onEnter = { if (showing) cancelFocusChange() } }.focusGroup()) {
            CompositionLocalProvider(LocalReactionHistory provides controller::open,
                LocalReadingBlocked provides (LocalReadingBlocked.current || showing), content = content)
        }
        CommunitySheet(visibility, "点赞与回应", controller::dismiss, showTitle = false, scrollContent = false,
            maxHeightFraction = .78f, backdrop = backdrop, glass = glass) {
            TopicReactionHistoryContent(state, controller::select, controller::more, controller::retry,
                user = { if (retainedController == null) controller.dismiss(); user(it) }, enabled = visibility.targetState)
        }
    }
}

@Composable
internal fun ColumnScope.TopicReactionHistoryContent(state: ReactionHistoryState, select: (String?) -> Unit,
    more: () -> Unit, retry: () -> Unit, user: (String) -> Unit, enabled: Boolean) {
    val history = state.history
    val pages = remember(history) { listOf<String?>(null) + history?.groups.orEmpty().map { it.reaction } }
    val pager = rememberPagerState(pageCount = { pages.size })
    val currentPages by rememberUpdatedState(pages)
    val currentSelect by rememberUpdatedState(select)
    val selectedPage = pages.indexOf(state.reaction).coerceAtLeast(0)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage to pager.isScrollInProgress }.distinctUntilChanged().collect { (index, scrolling) ->
            if (!scrolling && index in currentPages.indices) currentSelect(currentPages[index])
        }
    }
    LaunchedEffect(selectedPage, pages) {
        if (pager.currentPage != selectedPage && !pager.isScrollInProgress) pager.animateScrollToPage(selectedPage)
    }
    if (history != null && history.groups.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .testTag("reaction-history-filters"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ReactionHistoryFilter(null, "全部 ${history.total}", state.reaction == null, enabled) { select(null) }
        history.groups.forEach { group ->
            ReactionHistoryFilter(group.reaction, group.count.toString(), state.reaction == group.reaction, enabled) { select(group.reaction) }
        }
    }
    if (pages.size > 1) {
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 440.dp).wrapContentHeight()
            .testTag("reaction-history-pager"), key = { pages[it] ?: "all" }) { index ->
            ReactionHistoryUsersPage(history, pages[index], state, Modifier.fillMaxWidth(), more, retry, user, enabled,
                pager.settledPage == index)
        }
    } else {
        ReactionHistoryUsersPage(history, null, state, Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 440.dp),
            more, retry, user, enabled, true)
    }
}

@Composable
private fun ReactionHistoryUsersPage(history: ReactionHistory?, reaction: String?, state: ReactionHistoryState,
    modifier: Modifier, more: () -> Unit, retry: () -> Unit, user: (String) -> Unit, enabled: Boolean, active: Boolean) {
    val users = history?.users(reaction).orEmpty()
    val userTagSuffix = if (active) "" else "-${reaction ?: "all"}"
    LazyColumn(modifier.testTag("reaction-history-users$userTagSuffix"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(users.take(state.visibleCount), key = { it.member.username.lowercase(java.util.Locale.ROOT) }) { entry ->
            AvatarProfileLink(entry.member, enabled, { user(entry.member.username) }) { open ->
            Row(Modifier.fillMaxWidth().clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = open)
                .testTag("reaction-history-user-${entry.member.username}$userTagSuffix").padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(entry.member, 36.dp, presentation = AvatarPresentation.PLAIN)
                Column(Modifier.weight(1f)) {
                    Text(entry.member.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@${entry.member.username}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (entry.createdAt.isNotBlank()) Text(relativeTime(entry.createdAt), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SiteEmoji(entry.reaction, Modifier.size(22.dp))
            }
            }
        }
        if (active && state.loading) item { LoadingRow() }
        if (active && state.error != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.error.message ?: "暂时无法读取回应历史", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = retry, enabled = enabled && !state.loading, modifier = Modifier.testTag("reaction-history-retry")) { Text("重试") }
            }
        }
        if (active && history != null && !state.loading && state.error == null) {
            if (users.isEmpty()) item { Text("暂无可显示的记录", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium) }
            if (state.visibleCount < users.size || history.nextPage != null) item {
                TextButton(onClick = more, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("reaction-history-more")) { Text("加载更多") }
            }
            val partial = if (reaction == null) history.partial
                else history.nextPage == null && history.groups.any { it.reaction == reaction && it.count > it.users.size }
            if (partial) item {
                Text("站点仅返回部分记录，人数以站点统计为准。", Modifier.padding(vertical = 10.dp).testTag("reaction-history-partial"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReactionHistoryFilter(reaction: String?, label: String, active: Boolean, enabled: Boolean, click: () -> Unit) {
    Row(Modifier.clip(CircleShape).background(if (active) LogoYellow else MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
        .clickable(enabled = enabled, role = Role.Tab, onClick = click).semantics { selected = active }
        .testTag("reaction-history-filter-${reaction ?: "all"}").heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (reaction != null) SiteEmoji(reaction, Modifier.size(20.dp))
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = if (active) Ink else MaterialTheme.colorScheme.onSurface)
    }
}
