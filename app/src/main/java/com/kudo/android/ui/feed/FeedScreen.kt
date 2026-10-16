package com.kudo.android.ui.feed

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.kudo.android.R
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.LogoYellow
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(state: FeedState, user: Member?, select: (String) -> Unit, refresh: () -> Unit, more: () -> Unit, openTopic: (Int) -> Unit, openUser: (String) -> Unit, search: () -> Unit, login: () -> Unit, categories: () -> Unit, modifier: Modifier = Modifier, category: Category? = null, back: () -> Unit = {}, listStyle: TopicListStyle = TopicListStyle.FEED, pages: Map<String, FeedState> = emptyMap(), categoryContent: (@Composable () -> Unit)? = null, createTopic: (Int) -> Unit = {}, glass: GlassMode = LocalDisplaySettings.current.glass) {
    if (category != null) {
        CategoryFeedScreen(category, state, user, select, refresh, more, openTopic, openUser, search, login, back, modifier, listStyle, pages, createTopic, glass)
        return
    }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val resetHeader = { scrollBehavior.state.heightOffset = 0f; scrollBehavior.state.contentOffset = 0f }
    val backdrop = rememberLayerBackdrop()
    val surface = MaterialTheme.colorScheme.surface
    val canBlur = glass != GlassMode.TRANSLUCENT && Build.VERSION.SDK_INT >= 31
    val canRefract = glass == GlassMode.LIQUID && Build.VERSION.SDK_INT >= 33
    var headerHeight by remember { mutableIntStateOf(0) }
    val contentTopPadding = with(LocalDensity.current) { headerHeight.toDp() }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).nestedScroll(scrollBehavior.nestedScrollConnection)) {
        val tabs = FeedFilter.available(user != null).map {
            it.value to if (it == FeedFilter.NEW && state.newCount != null) "新 (${state.newCount})" else it.label
        } + listOf("categories" to "类别")
        key(user?.id, user?.unifiedNew) {
            val pager = rememberPagerState(initialPage = tabs.indexOfFirst { it.first == state.filter }.coerceAtLeast(0), pageCount = { tabs.size })
            val scope = rememberCoroutineScope()
            val currentSelect by rememberUpdatedState(select)
            val currentCategories by rememberUpdatedState(categories)
            LaunchedEffect(pager) {
                var previousPage = pager.settledPage
                snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
                    val value = tabs.getOrNull(index)?.first ?: return@collect
                    if (value == "categories") currentCategories() else currentSelect(value)
                    if (index != previousPage) {
                        scrollBehavior.state.heightOffset = 0f
                        scrollBehavior.state.contentOffset = 0f
                    }
                    previousPage = index
                }
            }
            LaunchedEffect(state.filter) {
                val index = tabs.indexOfFirst { it.first == state.filter }
                if (index >= 0 && pager.currentPage != index && !pager.isScrollInProgress) pager.scrollToPage(index)
            }
            HorizontalPager(pager, Modifier.fillMaxSize().layerBackdrop(backdrop).testTag("home-pager"), key = { tabs[it].first }) { index ->
                val value = tabs[index].first
                if (value == "categories") {
                    Box(Modifier.fillMaxSize().padding(top = contentTopPadding)) {
                        categoryContent?.invoke() ?: EmptyState("社区分类", "按分类浏览话题", actionLabel = "查看分类", action = categories)
                    }
                } else {
                    val page = if (value == state.filter) state else pages[value]?.takeIf { it.accountId == user?.id }
                        ?: FeedState(filter = value, accountId = user?.id, categories = state.categories)
                    FeedPage(page, pager.settledPage == index && value == state.filter, resetHeader,
                        refresh, more, openTopic, openUser, login, listStyle,
                        expandHeader = {
                            androidx.compose.animation.core.animate(scrollBehavior.state.heightOffset, 0f) { value, _ -> scrollBehavior.state.heightOffset = value }
                            scrollBehavior.state.contentOffset = 0f
                        }, contentTopPadding = contentTopPadding)
                }
            }
            Column(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }
                .drawPlainBackdrop(backdrop, shape = { RoundedCornerShape(0.dp) }, effects = {
                    val collapsed = scrollBehavior.state.collapsedFraction
                    if (canBlur && collapsed > 0f) { vibrancy(); blur(4.dp.toPx() * collapsed) }
                    if (canRefract && collapsed > 0f) lens(24.dp.toPx() * collapsed, 24.dp.toPx() * collapsed)
                }, onDrawSurface = {
                    val collapsed = scrollBehavior.state.collapsedFraction
                    drawRect(surface.copy(alpha = 1f - collapsed * if (canBlur) 0.8f else 0.06f))
                }).testTag("home-header").statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    scrollBehavior.state.heightOffsetLimit = -placeable.height.toFloat()
                    val offset = scrollBehavior.state.heightOffset.roundToInt()
                    layout(placeable.width, (placeable.height + offset).coerceAtLeast(0)) { placeable.placeRelative(0, offset) }
                }.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(40.dp).clickable(onClick = login), contentAlignment = Alignment.Center) {
                        if (user == null) Image(painterResource(R.drawable.ic_linux_do), "Linux.do 社区 Logo", Modifier.size(28.dp))
                        else Avatar(user, 28.dp, presentation = AvatarPresentation.ACCOUNT)
                    }
                    Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = search).testTag("home-search"), contentAlignment = Alignment.Center) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Search, null, Modifier.size(20.dp).testTag("home-search-icon"), MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("真诚 · 友善 · 团结 · 专业", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.width(1.dp).height(14.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)).testTag("home-search-divider"))
                            Text("搜索", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
                        }
                    }
                }
                FeedTabBar(tabs, tabs.getOrNull(pager.currentPage)?.first ?: state.filter, containerColor = Color.Transparent) { value ->
                    val index = tabs.indexOfFirst { it.first == value }
                    if (index >= 0) scope.launch { pager.animateScrollToPage(index) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedPage(state: FeedState, active: Boolean, scrollToTop: () -> Unit,
    refresh: () -> Unit, more: () -> Unit, openTopic: (Int) -> Unit, openUser: (String) -> Unit,
    login: () -> Unit, listStyle: TopicListStyle, nested: NestedScrollConnection? = null,
    expandHeader: suspend () -> Unit = { scrollToTop() }, contentTopPadding: Dp = 0.dp) {
    val ignored = com.kudo.android.ui.topic.LocalIgnoredUsers.current
    val visibleTopics = state.topics.filterNot { it.author?.username?.lowercase(java.util.Locale.ROOT) in ignored }
        val listState = rememberLazyListState()
        val scrollContext = "${state.accountId}:${state.filter}"
        var previousContext by rememberSaveable { mutableStateOf(scrollContext) }
        var consumedScrollRequest by rememberSaveable { mutableLongStateOf(0L) }
        LaunchedEffect(scrollContext, state.scrollToTopRequest) {
            if (previousContext != scrollContext || consumedScrollRequest != state.scrollToTopRequest) {
                listState.scrollToItem(0)
                scrollToTop()
                previousContext = scrollContext
                consumedScrollRequest = state.scrollToTopRequest
            }
        }
        val nearEnd by remember { derivedStateOf { val info = listState.layoutInfo; info.totalItemsCount > 3 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3 } }
        LaunchedEffect(active, nearEnd, state.next) { if (active && nearEnd && state.next != null && state.moreError == null) more() }
        val refreshing = state.loading && state.topics.isNotEmpty()
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { if (active) refresh() }, state = pullState,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), indicator = {
                PullToRefreshDefaults.IndicatorBox(state = pullState, isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = contentTopPadding), containerColor = MaterialTheme.colorScheme.surface) {
                    CommunityLoadingIndicator(Modifier.size(16.dp).graphicsLayer {
                        rotationZ = if (refreshing) 0f else pullState.distanceFraction * 180f
                    }.clearAndSetSemantics {
                        if (refreshing) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        else if (pullState.distanceFraction > 0f) progressBarRangeInfo =
                            ProgressBarRangeInfo(pullState.distanceFraction.coerceIn(0f, 1f), 0f..1f)
                    }, strokeWidth = 2.5.dp, rotating = refreshing)
                }
            }) {
            val traditional = listStyle == TopicListStyle.TITLE
            LazyColumn(state = listState, contentPadding = if (traditional) PaddingValues(top = contentTopPadding, bottom = 112.dp) else PaddingValues(start = 8.dp, end = 8.dp, top = contentTopPadding + 8.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(if (traditional) 0.dp else 8.dp), modifier = Modifier.fillMaxSize().then(if (nested != null) Modifier.nestedScroll(nested) else Modifier).testTag("feed-list-${state.filter}")
                .quickScrollTarget(listState, enabled = active, expandHeader = expandHeader)) {
                if (state.loading && state.topics.isEmpty()) item { LoadingRow() }
                if (state.error != null) item { ErrorState(state.error, refresh, login) }
                if (!state.loading && state.error == null && visibleTopics.isEmpty()) item { EmptyState("暂时没有可显示话题", "换个分类，或者稍后再来看看。") }
                items(visibleTopics, key = { "topic-${it.id}" }, contentType = { listStyle }) { topic ->
                    val category = state.categories.firstOrNull { it.id == topic.categoryId }
                    if (traditional) TopicTitleRow(topic, category, { openTopic(topic.id) }, openUser)
                    else TopicCard(topic, category, { openTopic(topic.id) }, openUser)
                }
                if (state.loadingMore) item { LoadingRow() }
                else if (state.moreError != null) item { ErrorState(state.moreError, more, login) }
                else if (state.next != null) item { TextButton(onClick = more, modifier = Modifier.fillMaxWidth()) { Text("加载更多话题") } }
                else if (state.topics.isNotEmpty()) item { Text("已经看到这里的全部话题", Modifier.fillMaxWidth().padding(14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

}

@Composable
internal fun FeedTabBar(tabs: List<Pair<String, String>>, selected: String, containerColor: Color = MaterialTheme.colorScheme.surface,
    modifier: Modifier = Modifier, select: (String) -> Unit) {
    BalancedTabRow(tabs.map { it.second }, MaterialTheme.typography.bodyMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
        modifier.background(containerColor)) { index, slot ->
        val (value, label) = tabs[index]
            key(value) {
                val active = selected == value
                val bringIntoView = remember { BringIntoViewRequester() }
                LaunchedEffect(active) { if (active) bringIntoView.bringIntoView() }
                Box(slot.bringIntoViewRequester(bringIntoView).selectable(active, role = Role.Tab, onClick = { select(value) }).heightIn(min = 34.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, Modifier.padding(vertical = 5.dp).drawBehind {
                        if (active) drawRoundRect(
                            brush = Brush.horizontalGradient(listOf(LogoYellow.copy(alpha = 0.25f), LogoYellow.copy(alpha = 0.95f), LogoYellow.copy(alpha = 0.4f))),
                            topLeft = Offset(0f, size.height * 0.58f), size = Size(size.width, size.height * 0.34f),
                            cornerRadius = CornerRadius(3.dp.toPx()))
                    }, fontSize = if (active) 17.sp else 15.sp, lineHeight = if (active) 24.sp else 21.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
    }
}
