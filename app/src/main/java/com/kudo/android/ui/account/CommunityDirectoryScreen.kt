package com.kudo.android.ui.account

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.feed.FeedTabBar
import com.kudo.android.ui.theme.LogoYellow
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

@Composable
internal fun CommunityDirectoryHost(model: CommunityDirectoryViewModel, accountId: Int?, sessionReady: Boolean,
    login: () -> Unit, openCategory: (Category) -> Unit, openTag: (TopicTag) -> Unit,
    openWeb: (String) -> Unit, inHome: Boolean = false) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(accountId, sessionReady) {
        model.useAccount(accountId)
        if (sessionReady) model.loadIfStale()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        model.useAccount(accountId)
        if (sessionReady) model.loadIfStale()
    }
    CommunityDirectoryScreen(state.forAccount(accountId).let { if (sessionReady) it else it.copy(loading = true) }, { model.load(refresh = true) }, login,
        { model.loadTags() }, { model.loadTags(refresh = true) }, openCategory, { entry ->
            if (entry.pmOnly) {
                val username = state.directory?.username.orEmpty()
                if (username.isNotBlank()) openWeb("https://linux.do/u/${Uri.encode(username)}/messages/tags/${Uri.encode(entry.tag.name)}")
            } else openTag(entry.tag)
        }, inHome)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityDirectoryScreen(state: CommunityDirectoryState, refresh: () -> Unit, login: () -> Unit,
    loadTags: () -> Unit, refreshTags: () -> Unit, openCategory: (Category) -> Unit, openTag: (DirectoryTag) -> Unit,
    inHome: Boolean = false) {
    val tabs = listOf("categories", "tags")
    val pager = key(state.accountId) { rememberPagerState(pageCount = { tabs.size }) }
    val scope = rememberCoroutineScope()
    val tab = tabs[pager.currentPage]
    var allCategories by rememberSaveable(state.accountId) { mutableStateOf(false) }
    var allTags by rememberSaveable(state.accountId) { mutableStateOf(false) }
    val sidebarCategoriesScroll = key(state.accountId) { rememberLazyListState() }
    val allCategoriesScroll = key(state.accountId) { rememberLazyListState() }
    val sidebarTagsScroll = key(state.accountId) { rememberLazyGridState() }
    val allTagsScroll = key(state.accountId) { rememberLazyGridState() }
    val directory = state.directory
    val expanded = if (tab == "categories") allCategories else allTags
    fun collapse() { if (tab == "categories") allCategories = false else allTags = false }
    BackHandler(expanded, ::collapse)
    LaunchedEffect(state.accountId, pager.settledPage, allTags, directory?.showTags) {
        if (pager.settledPage == 1 && allTags && directory?.showTags == true) loadTags()
    }
    val settings = LocalDisplaySettings.current
    val glass = settings.glass
    val backdrop = rememberLayerBackdrop()
    val canBlur = glass != GlassMode.TRANSLUCENT && Build.VERSION.SDK_INT >= 31
    val canRefract = glass == GlassMode.LIQUID && Build.VERSION.SDK_INT >= 33
    val surface = MaterialTheme.colorScheme.surface
    var toolbarHeight by remember { mutableIntStateOf(0) }
    val toolbarPadding = with(LocalDensity.current) { toolbarHeight.toDp() }
    Box(Modifier.fillMaxSize().testTag("community-directory")) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background)
            .testTag("directory-content-backdrop")) {
            HorizontalPager(pager, Modifier.fillMaxSize().testTag("directory-pager"), key = { tabs[it] }) { index ->
                val pageTab = tabs[index]
                val active = pager.settledPage == index
                val refreshing = (state.loading && directory != null) || (pageTab == "tags" && allTags && state.tagsLoading && state.tagGroups != null)
                val pull = rememberPullToRefreshState()
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
                    if (active) {
                        refresh()
                        if (pageTab == "tags" && allTags) refreshTags()
                    }
                }, state = pull, modifier = Modifier.fillMaxSize(), indicator = {
                    PullToRefreshDefaults.IndicatorBox(state = pull, isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = toolbarPadding), containerColor = MaterialTheme.colorScheme.surface) {
                        CommunityLoadingIndicator(Modifier.size(16.dp), rotating = refreshing)
                    }
                }) {
                    if (pageTab == "categories") {
                        CategoriesScreen(if (allCategories) directory?.allCategories.orEmpty() else directory?.categories.orEmpty(),
                            state.loading, state.error, refresh, login, inHome = true,
                            scroll = if (allCategories) allCategoriesScroll else sidebarCategoriesScroll,
                            footer = if (allCategories || directory == null) null else ({
                                DirectoryMoreRow("所有类别") { allCategories = true }
                            }), contentTopPadding = toolbarPadding, active = active, select = openCategory)
                    } else {
                        val groups = if (allTags) state.tagGroups.orEmpty() else listOf(DirectoryTagGroup("", directory?.tags.orEmpty()))
                        val scroll = if (allTags) allTagsScroll else sidebarTagsScroll
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val minimum = minOf(100.dp * LocalDensity.current.fontScale.coerceAtLeast(1f), (maxWidth - 34.dp) / 2)
                            LazyVerticalGrid(columns = GridCells.Adaptive(minimum), state = scroll,
                                modifier = Modifier.fillMaxSize().quickScrollTarget(scroll, enabled = active).testTag("directory-tag-grid"),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = toolbarPadding + 14.dp, bottom = 112.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if ((state.loading && directory == null) || (allTags && state.tagsLoading && state.tagGroups == null)) {
                                    item(span = { GridItemSpan(maxLineSpan) }) { LoadingRow() }
                                }
                                state.error?.let { error -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(error, refresh, login) } }
                                if (allTags) state.tagsError?.let { error ->
                                    item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(error, refreshTags, login) }
                                }
                                if (!state.loading && !state.tagsLoading && state.error == null && (!allTags || state.tagsError == null) && groups.all { it.tags.isEmpty() }) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Text(if (directory?.showTags == false) "当前未显示侧栏标签" else "暂无标签",
                                            Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                groups.forEachIndexed { groupIndex, group ->
                                    if (group.name.isNotBlank()) item(key = "group-$groupIndex", span = { GridItemSpan(maxLineSpan) }) {
                                        Text(group.name, Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                                    }
                                    items(group.tags, key = { "$groupIndex:${it.tag.name}" }) { entry -> DirectoryTagCell(entry) { openTag(entry) } }
                                }
                                if (!allTags && directory?.showTags == true) item(key = "all-tags", span = { GridItemSpan(maxLineSpan) }) {
                                    DirectoryMoreRow("所有标签") { allTags = true }
                                }
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().onSizeChanged { toolbarHeight = it.height }
            .drawPlainBackdrop(backdrop, shape = { RoundedCornerShape(0.dp) }, onDrawSurface = {
                drawRect(surface.copy(alpha = if (canBlur) .2f else .94f))
            }, effects = {
                if (canBlur) { vibrancy(); blur(4.dp.toPx()) }
                if (canRefract) lens(24.dp.toPx(), 24.dp.toPx())
            }).then(if (inHome) Modifier else Modifier.statusBarsPadding())
            .testTag("directory-toolbar-material")) {
            FeedTabBar(listOf("categories" to "类别", "tags" to "标签"), tab, containerColor = Color.Transparent) { value -> scope.launch { pager.animateScrollToPage(tabs.indexOf(value)) } }
            if (expanded) Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TopBarIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回侧栏目录", ::collapse, backdrop = backdrop)
                Text(if (tab == "categories") "所有类别" else "所有标签", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun DirectoryTagCell(entry: DirectoryTag, click: () -> Unit) {
    val tag = entry.tag
    val style = tag.iconStyle(LocalTopicPresentation.current.tagIcons)
    val color = runCatching { Color("#${style?.color.orEmpty().removePrefix("#")}".toColorInt()) }.getOrDefault(LogoYellow)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
        .clickable(role = Role.Button, onClick = click).heightIn(min = 110.dp).testTag("directory-tag-${tag.name}")
        .padding(horizontal = 8.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(40.dp).background(color.copy(alpha = .14f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(CategoryIcons.get(style?.icon.orEmpty()), null, Modifier.size(23.dp), tint = color)
        }
        Text(tag.name, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DirectoryMoreRow(title: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
        .clickable(role = Role.Button, onClick = click).heightIn(min = 54.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.AutoMirrored.Rounded.List, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
    }
}
