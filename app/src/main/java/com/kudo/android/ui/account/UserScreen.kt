package com.kudo.android.ui.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.relocation.*
import androidx.compose.foundation.selection.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.LogoYellow
import com.kudo.android.ui.topic.RichContent
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun UserScreen(username: String, repository: CommunityRepository, back: () -> Unit, login: () -> Unit,
    openLink: (String) -> Unit, viewerId: Int? = null, statusBarStyle: (Boolean?) -> Unit = {},
    search: () -> Unit = {}, message: () -> Unit = {}, openWeb: (String) -> Unit = openLink) {
    val vm = viewModel<UserProfileViewModel>(key = "user-$username-$viewerId", factory = viewModelFactory {
        initializer { UserProfileViewModel(repository, username, viewerId) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(vm) { vm.resume(); onDispose { vm.stop() } }
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.events.collect { showKudoToast(context, it) } }
    Box(Modifier.fillMaxSize()) {
        PublicProfilePage(state, viewerId, back, login, openLink, search, message, openWeb, statusBarStyle,
            vm::refresh, vm::retrySummary, vm::retryCard, vm::load, vm::follow, vm::showFollows, vm::closeFollows)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PublicProfilePage(state: PublicProfileState, viewerId: Int?, back: () -> Unit, login: () -> Unit,
    openLink: (String) -> Unit, search: () -> Unit, message: () -> Unit, openWeb: (String) -> Unit,
    statusBarStyle: (Boolean?) -> Unit = {}, refresh: () -> Unit = {}, retrySummary: () -> Unit = {}, retryCard: () -> Unit = {},
    load: (ProfileTab, Boolean, Boolean) -> Unit = { _, _, _ -> }, follow: () -> Unit = {},
    showFollows: (Boolean) -> Unit = {}, closeFollows: () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("public-user-page")) {
        val profile = state.profile
        val pager = rememberPagerState(pageCount = { ProfileTab.entries.size })
        val scrolls = ProfileTab.entries.map { tab -> key(profile?.member?.id, tab) { rememberLazyListState() } }
        val activeScroll by rememberUpdatedState(scrolls[pager.settledPage])
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val backdrop = rememberLayerBackdrop()
        val surface = MaterialTheme.colorScheme.surface
        var toolbarHeight by remember { mutableIntStateOf(0) }
        val minimum = if (toolbarHeight > 0) toolbarHeight.toFloat()
            else WindowInsets.statusBars.getTop(density) + with(density) { 60.dp.toPx() }
        val expandedHeight = maxWidth.coerceAtMost((maxHeight - 120.dp).coerceAtLeast(with(density) { minimum.toDp() }))
        val headerHeight = with(density) { expandedHeight.toPx() }
        var collapse by remember(profile?.member?.id) { mutableFloatStateOf(0f) }
        val maximum = (headerHeight - minimum).coerceAtLeast(0f)
        val visibleCollapse = collapse.coerceIn(0f, maximum)
        val fraction = if (maximum > 0f) visibleCollapse / maximum else 1f
        val compactIdentityAlpha = profileToolbarIdentityAlpha(fraction)
        val collapsed = fraction >= .999f
        val viewportHeight = with(density) { (headerHeight - visibleCollapse).toDp() }
        var tabsHeight by remember { mutableStateOf(40.dp) }
        val contentTop = viewportHeight + tabsHeight
        val latestMaximum by rememberUpdatedState(maximum)
        LaunchedEffect(maximum) { collapse = collapse.coerceIn(0f, maximum) }
        val nested = remember(profile?.member?.id) { object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y == 0f || (available.y > 0f && activeScroll.canScrollBackward)) return Offset.Zero
                val previous = collapse
                collapse = (collapse - available.y).coerceIn(0f, latestMaximum)
                return Offset(0f, previous - collapse)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0) return Offset.Zero
                val previous = collapse
                collapse = (collapse - available.y).coerceIn(0f, latestMaximum)
                return Offset(0f, previous - collapse)
            }
        } }
        val latestLoad by rememberUpdatedState(load)
        val activeTab = ProfileTab.entries[pager.settledPage]
        val activeFeed = state.feeds[activeTab]
        LaunchedEffect(activeTab, activeFeed?.loaded, activeFeed?.loading, activeFeed?.error) {
            if (activeTab != ProfileTab.SUMMARY && activeFeed?.loaded != true && activeFeed?.loading != true && activeFeed?.error == null)
                latestLoad(activeTab, false, false)
        }
        SideEffect { statusBarStyle(if (profile == null) null else if (compactIdentityAlpha >= .5f) surface.luminance() > .5f else false) }
        DisposableEffect(Unit) { onDispose { statusBarStyle(null) } }
        val moreVisibility = remember { MutableTransitionState(false) }
        val detailsVisibility = remember(profile?.member?.id) { MutableTransitionState(false) }
        val followsVisibility = remember { MutableTransitionState(false) }
        LaunchedEffect(state.showingFollowers) { followsVisibility.targetState = state.showingFollowers != null }
        var selectedBadge by remember { mutableStateOf<UserBadge?>(null) }
        val badgeVisibility = remember { MutableTransitionState(false) }
        val covered = listOf(moreVisibility, detailsVisibility, followsVisibility, badgeVisibility)
            .any { it.currentState || it.targetState }
        val context = LocalContext.current
        val pageUrl = profile?.member?.username?.let { "https://linux.do/u/${android.net.Uri.encode(it)}" }.orEmpty()
        if (profile == null) MaterialPagePlaceholder("个人主页", back) {
            if (state.loading) LoadingRow() else state.error?.let { ErrorState(it, refresh, login) }
        } else {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background)
                .nestedScroll(nested).testTag("public-profile-content-backdrop")) {
                Box(Modifier.fillMaxWidth().height(viewportHeight).clipToBounds().zIndex(1f).testTag("public-profile-header-viewport")
                    .scrollable(rememberScrollableState { delta ->
                        val previous = collapse
                        collapse = (collapse - delta).coerceIn(0f, maximum)
                        previous - collapse
                    }, Orientation.Vertical)) {
                    PublicProfileHeader(profile, state.summary, state.cardBackground, viewerId, state.followBusy,
                        { if (viewerId == null) login() else follow() }, { if (viewerId == null) login() else message() }, showFollows,
                        Modifier.graphicsLayer { alpha = 1f - compactIdentityAlpha }
                            .offset { IntOffset(0, -visibleCollapse.toInt()) }.wrapContentHeight(Alignment.Top, unbounded = true)
                            .height(expandedHeight), openLink,
                        collapseFraction = fraction,
                        details = { detailsVisibility.targetState = true })
                }
                HorizontalPager(pager, Modifier.fillMaxSize().testTag("profile-pager"), key = { ProfileTab.entries[it].name }) { index ->
                    val tab = ProfileTab.entries[index]
                    val scroll = scrolls[index]
                    val feed = state.feeds[tab] ?: ProfileFeedState()
                    val currentNext by rememberUpdatedState(feed.next)
                    val pull = rememberPullToRefreshState()
                    val refreshing = if (tab == ProfileTab.SUMMARY) state.summaryLoading && state.summary != null
                    else feed.loading && feed.entries.isNotEmpty()
                    val refreshPage by rememberUpdatedState {
                        if (tab == ProfileTab.SUMMARY) refresh() else latestLoad(tab, false, true)
                    }
                    LaunchedEffect(scroll, feed.next, feed.loading, feed.error, pager.settledPage) {
                        if (tab != ProfileTab.SUMMARY && index == pager.settledPage && feed.next != null && !feed.loading && feed.error == null) {
                            snapshotFlow { scroll.layoutInfo.visibleItemsInfo.any { it.key == "profile-more" } }.distinctUntilChanged().collect { visible ->
                                if (visible && currentNext != null) latestLoad(tab, true, false)
                            }
                        }
                    }
                    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshPage() }, state = pull,
                        modifier = Modifier.fillMaxSize(), indicator = {
                            PullToRefreshDefaults.IndicatorBox(state = pull, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = contentTop),
                                containerColor = MaterialTheme.colorScheme.surface) { CommunityLoadingIndicator(Modifier.size(16.dp), rotating = refreshing) }
                        }) {
                    LazyColumn(Modifier.fillMaxSize().testTag("profile-list-${tab.name}")
                            .quickScrollTarget(scroll, enabled = pager.settledPage == index && !covered,
                                expandHeader = {
                                    androidx.compose.animation.core.animate(collapse, 0f) { value, _ -> collapse = value }
                                }), state = scroll,
                            contentPadding = PaddingValues(top = contentTop + 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (tab == ProfileTab.SUMMARY) {
                            if (state.error != null) item { ErrorState(state.error, refresh, login, back) }
                            if (state.summaryLoading && state.summary == null) item { LoadingRow() }
                            if (state.summaryError != null) item { ErrorState(state.summaryError, retrySummary, login) }
                            if (state.cardError != null) item { Text("背景暂未读取 · 点击重试", Modifier.padding(horizontal = 18.dp).clickable(onClick = retryCard),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            state.summary?.let { publicSummary(it, openLink) { value -> selectedBadge = value; badgeVisibility.targetState = true } }
                            if (profile.joined.isNotBlank()) item { Text("注册于 ${profile.joined.take(10)}", Modifier.padding(18.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline) }
                            item { Text("刷新资料", Modifier.fillMaxWidth().clickable(onClick = refresh).padding(18.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } else {
                            items(feed.entries, key = ProfileEntry::key) { ProfileEntryRow(it, openLink) }
                            if (feed.loading || (!feed.loaded && feed.error == null)) item { LoadingRow() }
                            if (feed.error != null) item { ErrorState(feed.error, { load(tab, feed.entries.isNotEmpty() && feed.next != null, true) }, login) }
                            if (feed.loaded && feed.entries.isEmpty() && feed.error == null) item { EmptyState("暂无${tab.label}", "这里会显示用户公开的${tab.label}记录。") }
                            if (feed.next != null && !feed.loading && feed.error == null) item("profile-more") {
                                Text("向上滑动加载更多", Modifier.fillMaxWidth().padding(18.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            if (feed.loaded && feed.entries.isNotEmpty() && feed.next == null) item { Text("已显示全部", Modifier.padding(18.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline) }
                        }
                    }
                    }
                }
            }
            Box(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, (headerHeight - visibleCollapse).toInt()) }
                .onSizeChanged { tabsHeight = with(density) { it.height.toDp() } }
                .screenMaterial(backdrop, fraction = { compactIdentityAlpha }).testTag("public-profile-tabs-material")) {
                ProfileTabs(pager.currentPage, { index -> scope.launch { pager.animateScrollToPage(index) } })
            }
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .screenMaterial(backdrop, fraction = { compactIdentityAlpha }, expandedColor = Color.Transparent)
                .testTag("public-profile-toolbar-material")) {
                ProfileToolbar(profile.member, collapsed, viewerId, state.followBusy,
                    { if (viewerId == null) login() else follow() }, back, search, { moreVisibility.targetState = true },
                    modifier = Modifier.onSizeChanged { toolbarHeight = it.height }, collapseFraction = fraction,
                    ink = lerp(Color.White, MaterialTheme.colorScheme.onSurface, compactIdentityAlpha), backdrop = backdrop)
            }
        }
        CommunitySheet(detailsVisibility, "个人资料", { detailsVisibility.targetState = false }, showActions = false) {
            profile?.let { PublicProfileDetails(it, state.summary, openLink) }
        }
        CommunitySheet(moreVisibility, "个人主页", { moreVisibility.targetState = false }, showTitle = false, showActions = false) {
            ProfileMenuRow("copy", Icons.Rounded.ContentCopy, "复制链接") {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("个人主页", pageUrl))
                moreVisibility.targetState = false
            }
            ProfileMenuRow("share", Icons.Rounded.Share, "分享") {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pageUrl), "分享个人主页"))
                moreVisibility.targetState = false
            }
            ProfileMenuRow("web", Icons.Rounded.Language, "网页查看") { moreVisibility.targetState = false; openWeb(pageUrl) }
            ProfileMenuRow("refresh", Icons.Rounded.Refresh, "刷新") { moreVisibility.targetState = false; refresh() }
        }
        CommunitySheet(followsVisibility, if (state.showingFollowers == true) "粉丝" else "关注", closeFollows, showActions = false, scrollContent = false) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            if (state.followsLoading) item { LoadingRow() }
            state.followsError?.let { error -> item { ErrorState(error, { showFollows(state.showingFollowers == true) }, login) } }
            if (state.follows?.isEmpty() == true) item { Text("暂无公开用户", Modifier.padding(16.dp), fontSize = 14.sp) }
            items(state.follows.orEmpty(), key = Member::username) { member ->
                Row(Modifier.fillMaxWidth().clickable { closeFollows(); openLink("https://linux.do/u/${android.net.Uri.encode(member.username)}") }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(member, 40.dp)
                    Column { Text(member.displayName, fontSize = 14.sp); Text("@${member.username}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            }
        }
        CommunitySheet(badgeVisibility, selectedBadge?.name.orEmpty(), { badgeVisibility.targetState = false }, showActions = false) {
            selectedBadge?.let { badge -> RichContent(badge.longDescription.ifBlank { badge.description }, openLink, {}) }
        }
    }
}

@Composable
private fun ProfileMenuRow(key: String, icon: ImageVector, title: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = action).testTag("profile-menu-$key")
        .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, fontSize = 14.sp)
    }
}

@Composable
private fun ProfileTabs(selected: Int, select: (Int) -> Unit) {
    BalancedTabRow(ProfileTab.entries.map { it.label }, MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        Modifier.testTag("profile-tabs")) { index, slot ->
        val tab = ProfileTab.entries[index]
        key(tab) {
            val active = selected == index
            val bring = remember { BringIntoViewRequester() }
            LaunchedEffect(active) { if (active) bring.bringIntoView() }
            Box(slot.bringIntoViewRequester(bring).selectable(active, role = Role.Tab, onClick = { select(index) })
                .heightIn(min = 40.dp).padding(horizontal = 12.dp).testTag("profile-tab-${tab.name}"), contentAlignment = Alignment.Center) {
                Text(tab.label, Modifier.padding(vertical = 7.dp).drawBehind {
                    if (active) drawRoundRect(Brush.horizontalGradient(listOf(LogoYellow.copy(alpha = .22f), LogoYellow.copy(alpha = .95f), LogoYellow.copy(alpha = .35f))),
                        Offset(0f, size.height * .57f), Size(size.width, size.height * .34f), CornerRadius(3.dp.toPx()))
                }, fontSize = if (active) 16.sp else 14.sp, lineHeight = 23.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
