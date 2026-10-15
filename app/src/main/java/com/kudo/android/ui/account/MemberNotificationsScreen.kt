package com.kudo.android.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.LogoYellow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemberNotificationsScreen(vm: AccountViewModel, login: () -> Unit, open: (Int, Int?) -> Unit,
    website: (String) -> Unit, privateMessages: () -> Unit) {
    val pager = rememberPagerState(pageCount = { NoticeGroup.entries.size })
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().testTag("member-notifications")) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding()
            .heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("消息", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            MessageCountBadge(vm.unreadCount, Modifier.padding(start = 6.dp))
            Spacer(Modifier.weight(1f))
            CommunityIconButton(Icons.Rounded.Refresh, "刷新通知", { vm.loadNotices() }, enabled = !vm.noticesLoading)
            TextButton({ vm.markRead() }, enabled = vm.unreadCount > 0 || vm.notices.any { !it.read }) { Text("全部已读", fontSize = 12.sp) }
        }
        AccountTabs(NoticeGroup.entries.map { it.title }, pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
        HorizontalPager(pager, Modifier.weight(1f)) { index ->
            val group = NoticeGroup.entries[index]
            val scroll = rememberLazyListState()
            val notices = vm.notices.filter(group::includes)
            var scanned by remember(group) { mutableIntStateOf(0) }
            // Scan a few complete pages before offering explicit continuation, without fetching an entire account history.
            LaunchedEffect(pager.currentPage, vm.notices.size, vm.noticesLoading, vm.hasMoreNotices, vm.noticesError) {
                if (pager.currentPage == index && notices.isEmpty() && vm.hasMoreNotices &&
                    !vm.noticesLoading && vm.noticesError == null && scanned < 3) {
                    scanned++; vm.loadNotices(vm.noticesLoaded)
                }
            }
            val pull = rememberPullToRefreshState()
            val refreshing = vm.noticesLoading && vm.notices.isNotEmpty()
            LaunchedEffect(pager.settledPage, vm.notices.size, vm.noticesLoading, vm.hasMoreNotices, vm.noticesError) {
                if (pager.settledPage == index) {
                    snapshotFlow { scroll.isScrollInProgress to (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
                        .distinctUntilChanged().collect { (scrolling, last) ->
                            if (scrolling && vm.hasMoreNotices && !vm.noticesLoading && vm.noticesError == null &&
                                notices.isNotEmpty() && last >= scroll.layoutInfo.totalItemsCount - 3) vm.loadNotices(true)
                        }
                }
            }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.loadNotices() }, state = pull,
                modifier = Modifier.fillMaxSize(), indicator = {
                    PullToRefreshDefaults.IndicatorBox(state = pull, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = MaterialTheme.colorScheme.surface) { CommunityLoadingIndicator(Modifier.size(16.dp), rotating = refreshing) }
                }) {
                LazyColumn(Modifier.fillMaxSize().testTag("notice-group-${group.name}")
                    .quickScrollTarget(scroll, enabled = pager.settledPage == index), state = scroll,
                    contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                if (group == NoticeGroup.MESSAGES) item {
                    CompactAccountRow(Icons.Rounded.MailOutline, Color(0xFF6C83CA), "查看私信收件箱", privateMessages)
                }
                items(notices, key = { it.id }) { notice ->
                    Row(Modifier.fillMaxWidth().background(if (notice.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = .8f))
                        .clickable {
                            vm.markRead(notice.id)
                            when {
                                notice.topicId != null -> open(notice.topicId, notice.postNumber)
                                notice.url.isNotBlank() -> website(if (notice.url.startsWith("/")) "https://linux.do${notice.url}" else notice.url)
                                else -> {
                                    val username = java.net.URLEncoder.encode(vm.user?.username.orEmpty(), "UTF-8")
                                    website("https://linux.do/u/$username/notifications")
                                }
                            }
                        }.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box {
                            if (notice.actor != null) Avatar(notice.actor, 36.dp) else Icon(Icons.Rounded.NotificationsNone, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (notice.isSolved) Icon(Icons.Rounded.CheckBox, "话题已解决",
                                Modifier.align(Alignment.BottomEnd).size(16.dp)
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
                                    .testTag("notice-solved-${notice.id}"), tint = Color(0xFF3B82F6))
                            if (!notice.read) Box(Modifier.align(Alignment.TopEnd).size(7.dp).background(LogoYellow, RoundedCornerShape(50))
                                .testTag("notice-unread-${notice.id}"))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(notice.description, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(notice.title, fontSize = 14.sp, lineHeight = 21.sp)
                            if (notice.time.isNotBlank()) Text(relativeTime(notice.time), fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (vm.noticesLoading) item { LoadingRow() }
                vm.noticesError?.let { item { ErrorState(it, { vm.loadNotices(vm.notices.isNotEmpty()) }, login) } }
                if (!vm.noticesLoading && vm.noticesError == null && notices.isEmpty()) item {
                    EmptyState(if (vm.hasMoreNotices) "当前批次暂无${group.title}" else "暂时没有${group.title}",
                        if (vm.hasMoreNotices) "可以继续加载更早的通知。" else "新的社区回应会出现在这里。", Icons.Rounded.NotificationsNone)
                }
                if (vm.hasMoreNotices && !vm.noticesLoading && notices.isNotEmpty()) item {
                    Text("向上滑动加载更早的通知", Modifier.fillMaxWidth().padding(18.dp), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                }
            }
        }
    }
}
