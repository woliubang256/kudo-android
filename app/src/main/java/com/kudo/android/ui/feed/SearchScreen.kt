package com.kudo.android.ui.feed

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.LogoYellow
import java.util.Locale

@Composable
fun SearchScreen(
    state: SearchState, search: (String, Boolean) -> Unit, back: () -> Unit,
    openTopic: (Int, Int?) -> Unit, openUser: (String) -> Unit, login: () -> Unit,
    listStyle: TopicListStyle = TopicListStyle.FEED, openCategory: (Int) -> Unit = {}, openTag: (TopicTag) -> Unit = {},
    changeKind: (SearchKind) -> Unit = {}, changeSort: (SearchSort) -> Unit = {}, clear: () -> Unit = {},
    refreshHistory: () -> Unit = {}, loadMetadata: () -> Unit = {}, suggestTags: (String) -> Unit = {},
    applyFilters: (String, SearchFilters) -> Unit = { _, _ -> },
) {
    var query by rememberSaveable(state.accountId) { mutableStateOf(state.query) }
    var filtersOpen by remember(state.accountId) { mutableStateOf(false) }
    var draft by remember(state.accountId) { mutableStateOf(SearchFilters()) }
    LaunchedEffect(state.query, state.accountId) { query = state.query }
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberLazyListState()
    LaunchedEffect(state.query, state.kind) { scroll.scrollToItem(0) }
    val ignored = com.kudo.android.ui.topic.LocalIgnoredUsers.current
    val posts = state.hits.posts.filterNot { it.topic.author?.username?.lowercase(Locale.ROOT) in ignored }
    val users = state.hits.users.filterNot { it.username.lowercase(Locale.ROOT) in ignored }
    val submit = { keyboard?.hide(); filtersOpen = false; search(query, false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CommunityIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back)
            Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, Modifier.padding(start = 12.dp).size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                CommunityTextField(query, { query = it }, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp).testTag("search-query"),
                    placeholder = "搜索帖子、分类、标签、用户", singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
                if (query.isNotEmpty()) CommunityIconButton(Icons.Rounded.Close, "清空搜索", { query = ""; clear(); filtersOpen = false })
            }
            Text("搜索", Modifier.clickable(enabled = query.isNotBlank(), role = Role.Button) { submit() }.padding(start = 14.dp, top = 14.dp, bottom = 14.dp).testTag("search-submit"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SearchKind.entries.forEach { kind ->
                Column(Modifier.weight(1f).clickable(role = Role.Tab) { filtersOpen = false; keyboard?.hide(); changeKind(kind) }
                    .semantics { selected = state.kind == kind }.testTag("search-kind-${kind.name}"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(kind.label, Modifier.padding(horizontal = 4.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall,
                        color = if (state.kind == kind) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (state.kind == kind) FontWeight.Bold else FontWeight.Normal)
                    Box(Modifier.width(24.dp).height(3.dp).background(if (state.kind == kind) LogoYellow else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(2.dp)))
                }
            }
        }
        if (state.kind == SearchKind.POSTS) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchSort.available(state.accountId != null).forEach { sort ->
                    Text(sort.label, Modifier.clickable(role = Role.Button) { keyboard?.hide(); changeSort(sort) }.semantics { selected = state.sort == sort }
                        .testTag("search-sort-${sort.name}").padding(horizontal = 10.dp, vertical = 14.dp), style = MaterialTheme.typography.bodySmall,
                        color = if (state.sort == sort) LogoYellow else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (state.sort == sort) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Box {
                CommunityIconButton(Icons.Rounded.Tune, "高级筛选", {
                    keyboard?.hide()
                    if (!filtersOpen) { draft = parseSearchFilters(query); loadMetadata() }
                    filtersOpen = !filtersOpen
                }, Modifier.testTag("search-filter-toggle"))
                if (state.filters.count > 0) Text(state.filters.count.toString(), Modifier.align(Alignment.TopEnd).padding(4.dp), color = LogoYellow, style = MaterialTheme.typography.labelSmall)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
        BoxWithConstraints(Modifier.weight(1f)) {
            val panelHeight = maxHeight * .72f
            Column {
                if (filtersOpen) SearchFiltersPanel(state, draft, { draft = it }, suggestTags, loadMetadata,
                    apply = { keyboard?.hide(); filtersOpen = false; applyFilters(query, draft) }, dismiss = { filtersOpen = false },
                    modifier = Modifier.heightIn(max = panelHeight))
                LazyColumn(state = scroll, modifier = Modifier.weight(1f).quickScrollTarget(scroll).testTag("search-results"),
                    contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(if (listStyle == TopicListStyle.FEED) 8.dp else 0.dp)) {
                    if (!state.searched) {
                        if (state.accountId != null) {
                            item { SectionLabel("最近搜索") }
                            if (state.historyLoading) item { LoadingRow() }
                            if (state.historyError != null) item { ErrorState(state.historyError, refreshHistory, login) }
                            if (state.recent.isNotEmpty()) item {
                                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.recent.forEach { term -> SearchChip(term, false) { query = term; keyboard?.hide(); search(term, false) } }
                                }
                            } else if (!state.historyLoading && state.historyError == null) item {
                                Text("暂无最近搜索", Modifier.padding(horizontal = 18.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else item { EmptyState("搜索社区内容", "输入关键词开始搜索，也支持 Discourse 搜索语法。", Icons.Rounded.Search) }
                    }
                    when (state.kind) {
                        SearchKind.POSTS -> items(posts, key = { "post-${it.postId}" }) { hit ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = if (listStyle == TopicListStyle.FEED) 12.dp else 0.dp).testTag("search-hit-${hit.postId}")) {
                                val open = { openTopic(hit.topic.id, hit.postNumber) }
                                if (listStyle == TopicListStyle.FEED) TopicCard(hit.topic, null, open, openUser)
                                else {
                                    TopicTitleRow(hit.topic, null, open, openUser)
                                    if (hit.topic.excerpt.isNotBlank()) Text(hit.topic.excerpt, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = open)
                                        .padding(start = 66.dp, end = 16.dp, bottom = 10.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                Text("命中第 ${hit.postNumber} 楼", Modifier.clickable(onClick = open).padding(start = 12.dp, top = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        SearchKind.CATEGORIES_TAGS -> {
                            if (state.hits.categories.isNotEmpty()) item { SectionLabel("分类") }
                            items(state.hits.categories, key = { "category-${it.id}" }) { category ->
                                SearchEntityRow(category.name, category.description.ifBlank { "${compactNumber(category.count)} 个话题" }, { openCategory(category.id) }) { CategoryInline(category) }
                            }
                            if (state.hits.tags.isNotEmpty()) item { SectionLabel("标签") }
                            items(state.hits.tags, key = { "tag-${it.name}" }) { tag ->
                                SearchEntityRow(tag.name, "查看此标签下的话题", { openTag(tag) }) { TopicTagChip(tag) }
                            }
                        }
                        SearchKind.USERS -> items(users, key = { "user-${it.id}-${it.username}" }) { user ->
                            SearchEntityRow(user.displayName, "@${user.username}", { openUser(user.username) }) { Avatar(user, 40.dp) { openUser(user.username) } }
                        }
                    }
                    if (state.loading) item { LoadingRow() }
                    if (state.error != null) item { ErrorState(state.error, { search(state.query, false) }, login) }
                    val empty = when (state.kind) {
                        SearchKind.POSTS -> posts.isEmpty()
                        SearchKind.CATEGORIES_TAGS -> state.hits.categories.isEmpty() && state.hits.tags.isEmpty()
                        SearchKind.USERS -> users.isEmpty()
                    }
                    if (state.searched && !state.loading && empty && state.error == null) item {
                        EmptyState("没有找到可显示的${state.kind.label}", "试试其他关键词，或更换搜索条件。", Icons.Rounded.Search)
                    }
                    if (state.moreError != null) item { ErrorState(state.moreError, { search(state.query, true) }, login) }
                    if (state.hasMore && !state.loading && state.moreError == null) item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CommunityButton("更多搜索结果", { search(state.query, true) }, Modifier.testTag("search-more"), compact = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchEntityRow(title: String, subtitle: String, open: () -> Unit, leading: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.widthIn(max = 90.dp)) { leading() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .25f))
    }
}
