package com.kudo.android.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kudo.android.BuildConfig
import com.kudo.android.R
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LogoYellow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

@Composable
fun RootHeading(title: String, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(start = 20.dp, end = 12.dp).heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        action()
    }
}

@Composable
fun CategoriesScreen(categories: List<Category>, loading: Boolean, error: Throwable?, refresh: () -> Unit, login: () -> Unit,
    inHome: Boolean = false, scroll: LazyListState = rememberLazyListState(), footer: (@Composable () -> Unit)? = null,
    contentTopPadding: Dp = 0.dp, active: Boolean = true,
    select: (Category) -> Unit) {
    Column(Modifier.fillMaxSize().then(if (inHome) Modifier else Modifier.statusBarsPadding())) {
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().quickScrollTarget(scroll, enabled = active).testTag("category-directory-list"), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = contentTopPadding + 14.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading && categories.isEmpty()) item { LoadingRow() }
            if (error != null) item { ErrorState(error, refresh, login) }
            if (!loading && error == null && categories.isEmpty()) item { Text("暂无类别", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(categories, key = { it.id }) { category ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).clickable { select(category) }.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val color = runCatching { Color(android.graphics.Color.parseColor("#${category.color}")) }.getOrDefault(LogoYellow)
                    Box(Modifier.size(45.dp).background(color.copy(alpha = 0.14f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { Icon(CategoryIcons.get(category.icon), null, Modifier.size(24.dp), tint = color) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(category.name, style = MaterialTheme.typography.titleMedium)
                        Text(category.description.ifBlank { "${compactNumber(category.count)} 个话题" }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), MaterialTheme.colorScheme.outline)
                }
            }
            if (footer != null) item("directory-footer") { footer() }
        }
    }
}

@Composable
fun ProfileScreen(user: Member?, login: () -> Unit, settings: () -> Unit, bookmarks: () -> Unit, profile: () -> Unit,
    repository: CommunityRepository? = null, about: () -> Unit = settings,
    destination: (AccountDestination) -> Unit = { if (it == AccountDestination.BOOKMARKS) bookmarks() },
    downloads: () -> Unit = {},
    statusBarStyle: (Boolean?) -> Unit = {}) {
    if (user == null) {
        GuestProfileScreen(login, settings, about, downloads)
        return
    }
    key(user.id, user.username) { MemberProfileScreen(user, repository, settings, about, profile, destination, downloads, statusBarStyle) }
}

@Composable
private fun GuestProfileScreen(login: () -> Unit, settings: () -> Unit, about: () -> Unit, downloads: () -> Unit) {
    val scroll = rememberLazyListState()
    Column(Modifier.fillMaxSize().statusBarsPadding().semantics { paneTitle = "我的" }.testTag("guest-profile")) {
        LazyColumn(state = scroll, modifier = Modifier.quickScrollTarget(scroll), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { GuestLoginPanel("登录后参与社区讨论", login) }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)) {
                    GuestMenuRow(Icons.Rounded.Palette, "设置", "主题、字号与液态玻璃", settings)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    GuestMenuRow(Icons.Rounded.Download, "下载管理", "查看文件下载进度和历史", downloads)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    GuestMenuRow(Icons.Rounded.Info, "关于酷DO", "一个用心的 Linux.do 原生客户端", about, value = BuildConfig.VERSION_NAME)
                }
            }
            item {
                Text("真诚 · 友善 · 团结 · 专业", Modifier.fillMaxWidth().padding(18.dp),
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GuestLoginPanel(explanation: String, login: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surface).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.widthIn(max = 208.dp).fillMaxWidth().clip(RoundedCornerShape(50))
            .background(LogoYellow).clickable(role = Role.Button, onClick = login)
            .heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text("点击登录", color = Ink, fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
        }
        Text(explanation, color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GuestMenuRow(icon: ImageVector, title: String, subtitle: String, click: () -> Unit, value: String? = null) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = click)
        .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (value != null) Text(value, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun MenuRow(icon: ImageVector, title: String, subtitle: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(19.dp))
    }
}

@Composable
fun NotificationsScreen(vm: AccountViewModel, login: () -> Unit, open: (Int, Int?) -> Unit,
    website: (String) -> Unit = {}, privateMessages: () -> Unit = {}) {
    if (vm.user == null) {
        GuestNotificationsScreen(login)
        return
    }
    key(vm.user?.id) { MemberNotificationsScreen(vm, login, open, website, privateMessages) }
}

@Composable
private fun GuestNotificationsScreen(login: () -> Unit) {
    val scroll = rememberLazyListState()
    Column(Modifier.fillMaxSize().statusBarsPadding().semantics { paneTitle = "消息" }.testTag("guest-notifications")) {
        LazyColumn(state = scroll, modifier = Modifier.quickScrollTarget(scroll), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 112.dp)) {
            item { GuestLoginPanel("登录后查看回复、提及和点赞通知", login) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(vm: AccountViewModel, back: () -> Unit, login: () -> Unit, open: (Int, Int?) -> Unit) {
    val scroll = rememberLazyListState()
    val pull = rememberPullToRefreshState()
    val refreshing = vm.savedLoading && vm.saved.isNotEmpty()
    LaunchedEffect(vm.saved.size, vm.savedLoading, vm.hasMoreSaved, vm.savedError) {
        snapshotFlow { scroll.isScrollInProgress to (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
            .distinctUntilChanged().collect { (scrolling, last) ->
                if (scrolling && vm.hasMoreSaved && !vm.savedLoading && vm.savedError == null &&
                    vm.saved.isNotEmpty() && last >= scroll.layoutInfo.totalItemsCount - 3) vm.loadSaved(true)
            }
    }
    MaterialPageScaffold(header = {
        AccountPageTopBar("我的收藏", back) { CommunityIconButton(Icons.Rounded.Refresh, "刷新收藏", { vm.loadSaved() }) }
    }) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.loadSaved() }, state = pull,
            modifier = Modifier.fillMaxSize(), indicator = {
                PullToRefreshDefaults.IndicatorBox(state = pull, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
                    containerColor = MaterialTheme.colorScheme.surface) { CommunityLoadingIndicator(Modifier.size(16.dp), rotating = refreshing) }
            }) {
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().quickScrollTarget(scroll), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                if (vm.user == null) item { EmptyState("把好内容留在这里", "登录后查看你的社区收藏。", Icons.Rounded.BookmarkBorder, "登录", login) }
                if (vm.savedLoading) item { LoadingRow() }
                items(vm.saved, key = { it.id }) { topic ->
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable { open(topic.topicId, topic.postNumber) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(topic.title, style = MaterialTheme.typography.titleMedium)
                        if (topic.excerpt.isNotBlank()) Text(topic.excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                vm.savedError?.let { item { ErrorState(it, { vm.loadSaved() }, login) } }
                if (vm.user != null && !vm.savedLoading && vm.saved.isEmpty() && vm.savedError == null) item { EmptyState("还没有收藏", "在帖子下方点星标，保存想再看的内容。", Icons.Rounded.BookmarkBorder) }
                if (!vm.savedLoading && vm.savedError == null && vm.saved.isNotEmpty() && !vm.hasMoreSaved) item {
                    Text("已显示全部", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.outline, fontSize = 11.sp)
                }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        }
    }
}
