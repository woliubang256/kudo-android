package com.kudo.android.ui.feed

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.components.liquid.LiquidModal
import com.kudo.android.ui.components.liquid.LiquidModalAction
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kudo.android.ui.theme.LogoYellow
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.util.Locale

@Composable
fun SearchScreen(
    state: SearchState, search: (String, Boolean) -> Unit, back: () -> Unit,
    openTopic: (Int, Int?) -> Unit, openUser: (String) -> Unit, login: () -> Unit,
    listStyle: TopicListStyle = TopicListStyle.FEED, openCategory: (Int) -> Unit = {}, openTag: (TopicTag) -> Unit = {},
    changeKind: (SearchKind) -> Unit = {}, changeSort: (SearchSort) -> Unit = {}, clear: () -> Unit = {},
    refreshHistory: () -> Unit = {}, loadMetadata: () -> Unit = {}, suggestTags: (String) -> Unit = {},
    applyFilters: (String, SearchFilters) -> Unit = { _, _ -> },
    clearHistory: () -> Unit = {}, initialScrollIndex: Int = 0, initialScrollOffset: Int = 0,
    saveScrollPosition: (Int, Int) -> Unit = { _, _ -> },
) {
    var query by rememberSaveable(state.accountId) { mutableStateOf(state.query) }
    var filtersOpen by remember(state.accountId) { mutableStateOf(false) }
    var draft by remember(state.accountId) { mutableStateOf(SearchFilters()) }
    val clearVisibility = remember(state.accountId) { MutableTransitionState(false) }
    val backdrop = rememberLayerBackdrop()
    val contentBackdrop = rememberLayerBackdrop()
    var headerHeight by remember { mutableIntStateOf(0) }
    val contentTopPadding = with(LocalDensity.current) { headerHeight.toDp() }
    val showing = clearVisibility.currentState || clearVisibility.targetState
    fun dismissClear() { if (!state.historyClearing) clearVisibility.targetState = false }
    androidx.activity.compose.BackHandler(enabled = filtersOpen && !showing) { filtersOpen = false }
    LaunchedEffect(state.accountId, state.searched, state.recent.isEmpty()) {
        if (state.accountId == null || state.searched || state.recent.isEmpty()) clearVisibility.targetState = false
    }
    LaunchedEffect(state.query, state.accountId) { query = state.query }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scrolls = SearchKind.entries.associateWith { kind ->
        key(state.accountId, kind) { rememberLazyListState(
            if (kind == state.kind) initialScrollIndex else 0, if (kind == state.kind) initialScrollOffset else 0) }
    }
    val pager = key(state.accountId) { rememberPagerState(initialPage = state.kind.ordinal, pageCount = { SearchKind.entries.size }) }
    val scope = rememberCoroutineScope()
    val currentKind by rememberUpdatedState(state.kind)
    val currentSave by rememberUpdatedState(saveScrollPosition)
    val currentChange by rememberUpdatedState(changeKind)
    val currentAccount by rememberUpdatedState(state.accountId)
    fun saveCurrentPosition() {
        val scroll = scrolls.getValue(currentKind)
        currentSave(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
    }
    DisposableEffect(state.accountId) {
        val owner = state.accountId
        onDispose { if (owner == currentAccount) saveCurrentPosition() }
    }
    LaunchedEffect(state.kind, state.scrollRevision) {
        scrolls.getValue(state.kind).scrollToItem(initialScrollIndex, initialScrollOffset)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            val kind = SearchKind.entries[index]
            if (kind != currentKind) {
                saveCurrentPosition()
                filtersOpen = false; focus.clearFocus(); keyboard?.hide(); currentChange(kind)
            }
        }
    }
    LaunchedEffect(state.kind) {
        if (!pager.isScrollInProgress && pager.currentPage != state.kind.ordinal) pager.scrollToPage(state.kind.ordinal)
    }
    val ignored = com.kudo.android.ui.topic.LocalIgnoredUsers.current
    val submit = { focus.clearFocus(); keyboard?.hide(); filtersOpen = false; search(query, false) }
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background).imePadding()
            .then(if (showing) Modifier.clearAndSetSemantics {} else Modifier)
            .focusProperties { onEnter = { if (showing) cancelFocusChange() } }.focusGroup()) {
            val panelHeight = (maxHeight - contentTopPadding).coerceAtLeast(0.dp) * .72f
            HorizontalPager(pager, Modifier.fillMaxSize().layerBackdrop(contentBackdrop).testTag("search-pager"), key = { SearchKind.entries[it].name }) { index ->
                val kind = SearchKind.entries[index]
                val page = state.forKind(kind)
                val active = pager.settledPage == index && state.kind == kind
                val scroll = scrolls.getValue(kind)
                val posts = page.hits.posts.filterNot { it.topic.author?.username?.lowercase(Locale.ROOT) in ignored }
                val users = page.hits.users.filterNot { it.username.lowercase(Locale.ROOT) in ignored }
                LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().quickScrollTarget(scroll, enabled = active && !showing).testTag(if (active) "search-results" else "search-results-${kind.name}"),
                    contentPadding = PaddingValues(top = contentTopPadding, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(if (listStyle == TopicListStyle.FEED) 8.dp else 0.dp)) {
                    if (!page.searched) {
                        if (page.accountId != null && (page.recent.isNotEmpty() || page.historyLoading || page.historyError != null)) {
                            item {
                                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("最近搜索", Modifier.weight(1f).padding(vertical = 14.dp), style = MaterialTheme.typography.titleMedium)
                                    if (page.recent.isNotEmpty()) CommunityIconButton(Icons.Rounded.DeleteOutline, "清空最近搜索", {
                                        focus.clearFocus(); keyboard?.hide(); filtersOpen = false; clearVisibility.targetState = true
                                    }, Modifier.testTag("search-clear-history"), enabled = !page.historyClearing)
                                }
                            }
                            if (page.historyLoading) item { LoadingRow() }
                            if (page.historyError != null) item { ErrorState(page.historyError, refreshHistory, login) }
                            if (page.recent.isNotEmpty()) item {
                                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    page.recent.forEach { term -> SearchChip(term, false) { query = term; focus.clearFocus(); keyboard?.hide(); search(term, false) } }
                                }
                            }
                        } else if (page.accountId == null) item { EmptyState("搜索社区内容", "输入关键词开始搜索，也支持 Discourse 搜索语法。", Icons.Rounded.Search) }
                    }
                    when (page.kind) {
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
                            if (page.hits.categories.isNotEmpty()) item { SectionLabel("分类") }
                            items(page.hits.categories, key = { "category-${it.id}" }) { category ->
                                SearchEntityRow(category.name, category.description.ifBlank { "${compactNumber(category.count)} 个话题" }, { openCategory(category.id) }) {
                                    Icon(CategoryIcons.get(category.icon), "分类图标：${category.name}", Modifier.size(24.dp))
                                }
                            }
                            if (page.hits.tags.isNotEmpty()) item { SectionLabel("标签") }
                            items(page.hits.tags, key = { "tag-${it.name}" }) { tag ->
                                SearchEntityRow(tag.name, "查看此标签下的话题", { openTag(tag) }) { Icon(Icons.Rounded.Tag, null, Modifier.size(24.dp)) }
                            }
                        }
                        SearchKind.USERS -> items(users, key = { "user-${it.id}-${it.username}" }) { user ->
                            SearchEntityRow(user.displayName, "@${user.username}", { openUser(user.username) }) { Avatar(user, 40.dp) { openUser(user.username) } }
                        }
                    }
                    if (page.loading) item { LoadingRow() }
                    if (page.error != null) item { ErrorState(page.error, { if (active) search(page.query, false) }, login) }
                    val empty = when (page.kind) {
                        SearchKind.POSTS -> posts.isEmpty()
                        SearchKind.CATEGORIES_TAGS -> page.hits.categories.isEmpty() && page.hits.tags.isEmpty()
                        SearchKind.USERS -> users.isEmpty()
                    }
                    if (page.searched && !page.loading && empty && page.error == null) item {
                        EmptyState("没有找到可显示的${page.kind.label}", "试试其他关键词，或更换搜索条件。", Icons.Rounded.Search)
                    }
                    if (page.moreError != null) item { ErrorState(page.moreError, { if (active) search(page.query, true) }, login) }
                    if (page.hasMore && !page.loading && page.moreError == null) item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CommunityButton("更多搜索结果", { if (active) search(page.query, true) }, Modifier.testTag("search-more"), compact = true)
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }.screenMaterial(contentBackdrop).testTag("search-header")) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TopBarIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back, backdrop = contentBackdrop)
                        Row(Modifier.weight(1f).height(40.dp).clip(CircleShape).testTag("search-input").background(MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Search, null, Modifier.padding(start = 12.dp).size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            CommunityTextField(query, { query = it }, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp).testTag("search-query"),
                                placeholder = "搜索社区", singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
                            if (query.isNotEmpty()) Box(Modifier.size(32.dp).clickable(role = Role.Button) {
                                query = ""; clear(); filtersOpen = false
                            }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, "清空搜索", Modifier.size(20.dp))
                            }
                            Box(Modifier.width(1.dp).height(14.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f)))
                            Text("搜索", Modifier.clickable(enabled = query.isNotBlank(), role = Role.Button) { submit() }.height(40.dp).padding(horizontal = 12.dp, vertical = 10.dp).testTag("search-submit"),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }

                    }
                    FeedTabBar(SearchKind.entries.map { it.name to it.label }, SearchKind.entries[pager.currentPage].name, containerColor = Color.Transparent) { value ->
                        scope.launch { pager.animateScrollToPage(SearchKind.valueOf(value).ordinal) }
                    }
                    if (state.kind == SearchKind.POSTS) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            SearchSort.available(state.accountId != null).forEach { sort ->
                                Text(sort.label, Modifier.clickable(role = Role.Button) { focus.clearFocus(); keyboard?.hide(); changeSort(sort) }.semantics { selected = state.sort == sort }
                                    .testTag("search-sort-${sort.name}").padding(horizontal = 10.dp, vertical = 14.dp), style = MaterialTheme.typography.bodySmall,
                                    color = if (state.sort == sort) LogoYellow else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (state.sort == sort) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                        Box(Modifier.padding(end = 8.dp, bottom = 2.dp)) {
                            TopBarIconButton(Icons.Rounded.Tune, "高级筛选", {
                                focus.clearFocus(); keyboard?.hide()
                                if (!filtersOpen) { draft = parseSearchFilters(query); loadMetadata() }
                                filtersOpen = !filtersOpen
                            }, Modifier.testTag("search-filter-toggle"), backdrop = contentBackdrop,
                                touchSize = 36.dp, visualSize = 32.dp, iconSize = 20.dp)
                            if (state.filters.count > 0) Text(state.filters.count.toString(), Modifier.align(Alignment.TopEnd).padding(4.dp), color = LogoYellow, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                }
                if (filtersOpen) SearchFiltersPanel(state, draft, { draft = it }, suggestTags, loadMetadata,
                    apply = { focus.clearFocus(); keyboard?.hide(); filtersOpen = false; applyFilters(query, draft) }, dismiss = { filtersOpen = false },
                    modifier = Modifier.heightIn(max = panelHeight).screenMaterial(contentBackdrop), backdrop = contentBackdrop)
            }
        }
        LiquidModal(clearVisibility, "清空最近搜索？", false, backdrop, LocalDisplaySettings.current.glass, ::dismissClear,
            actions = {
                if (state.historyClearing) Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    Text("正在清空…", style = MaterialTheme.typography.labelLarge)
                } else {
                    LiquidModalAction("取消", ::dismissClear, Modifier.weight(1f))
                    LiquidModalAction("清空", {
                        if (clearVisibility.targetState && !state.historyClearing) clearHistory()
                    }, Modifier.weight(1f).testTag("search-confirm-clear-history"), primary = true)
                }
            }) {
            Text("清空后，最近搜索记录将从账号中删除。", style = MaterialTheme.typography.bodyMedium)
            state.historyClearError?.let { Text(it.message ?: "清空失败，请稍后重试", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
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
