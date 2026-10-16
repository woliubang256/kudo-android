package com.kudo.android.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.LogoYellow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

internal enum class PersonalTopicsTab(val title: String) { TOPICS("话题"), REPLIES("回复") }

internal data class PersonalTopicsPage(
    val topics: List<Topic> = emptyList(), val replies: List<ProfileEntry> = emptyList(),
    val loading: Boolean = false, val loaded: Boolean = false, val error: Throwable? = null, val next: String? = null,
)

internal class PersonalTopicsViewModel(private val repository: CommunityRepository, private val username: String,
    val destination: AccountDestination) : ViewModel() {
    var mailbox by mutableStateOf("inbox"); private set
    var topicTab by mutableStateOf(PersonalTopicsTab.TOPICS); private set
    private var pages by mutableStateOf<Map<String, PersonalTopicsPage>>(emptyMap())
    val pageKey: String get() = if (destination == AccountDestination.TOPICS) topicTab.name else mailbox
    fun page(key: String) = pages[key] ?: PersonalTopicsPage()
    private val current get() = page(pageKey)
    val topics get() = current.topics
    val replies get() = current.replies
    val loading get() = current.loading
    val error get() = current.error
    val next get() = current.next
    private var request: Job? = null
    private var generation = 0
    private var historyWasShown = false
    init { load() }
    fun resumeHistory() {
        if (destination != AccountDestination.HISTORY) return
        if (historyWasShown) load()
        historyWasShown = true
    }
    private fun switchPage(select: () -> Unit) {
        request?.cancel(); generation++
        update(pageKey) { it.copy(loading = false) }
        select()
        if (!current.loaded && current.error == null) load()
    }
    fun selectMailbox(value: String) {
        if (destination != AccountDestination.MESSAGES || value == mailbox || value !in listOf("inbox", "sent", "archive")) return
        switchPage { mailbox = value }
    }
    fun selectTopicTab(value: PersonalTopicsTab) {
        if (destination != AccountDestination.TOPICS || value == topicTab) return
        switchPage { topicTab = value }
    }
    private fun update(key: String, change: (PersonalTopicsPage) -> PersonalTopicsPage) { pages = pages + (key to change(page(key))) }
    private fun itemCount() = if (destination == AccountDestination.TOPICS && topicTab == PersonalTopicsTab.REPLIES) replies.size else topics.size
    fun retry() = load(more = itemCount() > 0 && next != null)
    fun load(more: Boolean = false) {
        if (more && (loading || next == null)) return
        request?.cancel()
        val epoch = ++generation
        val key = pageKey
        val old = current
        val cursor = if (more) old.next else null
        val showingReplies = destination == AccountDestination.TOPICS && topicTab == PersonalTopicsTab.REPLIES
        val selectedMailbox = mailbox
        update(key) { it.copy(loading = true, error = null) }
        request = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (showingReplies) repository.profilePage(username, ProfileTab.REPLIES, cursor)
                    else repository.personalTopics(username, destination, cursor, selectedMailbox)
                }
                if (epoch == generation) update(key) {
                    when (result) {
                        is ProfilePage -> it.copy(replies = (if (more) old.replies + result.entries else result.entries).distinctBy(ProfileEntry::key),
                            next = result.next?.takeUnless { it == cursor }, loaded = true)
                        is TopicPage -> it.copy(topics = (if (more) old.topics + result.topics else result.topics).distinctBy { topic -> topic.id },
                            next = result.next?.takeUnless { it == cursor }, loaded = true)
                        else -> it
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (epoch == generation) update(key) { it.copy(error = e) } }
            finally { if (epoch == generation) update(key) { it.copy(loading = false) } }
        }
    }
}

@Composable
internal fun AccountPageTopBar(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    PageTopBar(title, back, actions)
}

@Composable
internal fun AccountTabs(labels: List<String>, selection: Int, modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface, select: (Int) -> Unit) {
    BalancedTabRow(labels, MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        modifier.background(containerColor)) { index, slot ->
            val label = labels[index]
            val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
            LaunchedEffect(selection == index) { if (selection == index) bring.bringIntoView() }
            Box(slot.then(Modifier.bringIntoViewRequester(bring)).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Tab) { select(index) }
                .semantics { selected = selection == index }.padding(horizontal = 12.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
                if (selection == index) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp).width(28.dp).height(7.dp)
                    .background(Brush.horizontalGradient(listOf(LogoYellow.copy(alpha = .25f), LogoYellow)), RoundedCornerShape(4.dp)))
                Text(label, fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = if (selection == index) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selection == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalTopicsScreen(vm: PersonalTopicsViewModel, categories: List<Category>, style: TopicListStyle,
    back: () -> Unit, login: () -> Unit, open: (Int, Int?) -> Unit, user: (String) -> Unit,
    composeMessage: () -> Unit, drafts: () -> Unit = {}) = key(vm) {
    if (vm.destination == AccountDestination.HISTORY) LifecycleResumeEffect(vm) {
        vm.resumeHistory()
        onPauseOrDispose {}
    }
    val keys = when (vm.destination) {
        AccountDestination.TOPICS -> PersonalTopicsTab.entries.map { it.name }
        AccountDestination.MESSAGES -> listOf("inbox", "sent", "archive")
        else -> listOf("inbox")
    }
    val pager = rememberPagerState(initialPage = keys.indexOf(vm.pageKey).coerceAtLeast(0), pageCount = { keys.size })
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager, vm) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            if (vm.destination == AccountDestination.TOPICS) vm.selectTopicTab(PersonalTopicsTab.entries[index])
            else if (vm.destination == AccountDestination.MESSAGES) vm.selectMailbox(keys[index])
        }
    }
    LaunchedEffect(vm.pageKey) {
        val index = keys.indexOf(vm.pageKey)
        if (index >= 0 && !pager.isScrollInProgress && pager.currentPage != index) pager.scrollToPage(index)
    }
    MaterialPageScaffold(header = {
        AccountPageTopBar(vm.destination.title, back) {
            if (vm.destination == AccountDestination.TOPICS) CommunityIconButton(Icons.Rounded.EditNote, "我的草稿", drafts)
            if (vm.destination == AccountDestination.MESSAGES) CommunityIconButton(Icons.Rounded.Edit, "写私信", composeMessage)
            CommunityIconButton(Icons.Rounded.Refresh, "刷新", { vm.load() })
        }
        if (vm.destination == AccountDestination.MESSAGES) {
            AccountTabs(listOf("收件箱", "已发送", "归档"), pager.currentPage, containerColor = Color.Transparent) { scope.launch { pager.animateScrollToPage(it) } }
        }
        if (vm.destination == AccountDestination.TOPICS) {
            AccountTabs(PersonalTopicsTab.entries.map(PersonalTopicsTab::title), pager.currentPage, containerColor = Color.Transparent) { scope.launch { pager.animateScrollToPage(it) } }
        }
    }) { padding ->
        HorizontalPager(pager, Modifier.fillMaxSize().testTag("personal-pager"), key = { keys[it] }, userScrollEnabled = keys.size > 1) { index ->
            val pageKey = keys[index]
            val page = vm.page(pageKey)
            val active = pager.settledPage == index && vm.pageKey == pageKey
            val scroll = rememberLazyListState()
            val showingReplies = vm.destination == AccountDestination.TOPICS && pageKey == PersonalTopicsTab.REPLIES.name
            val count = if (showingReplies) page.replies.size else page.topics.size
            val pull = rememberPullToRefreshState()
            val refreshing = page.loading && count > 0
            LaunchedEffect(active, page.next, page.loading, page.error) {
                snapshotFlow { scroll.isScrollInProgress to (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
                    .distinctUntilChanged().collect { (scrolling, last) ->
                        if (active && scrolling && page.next != null && !page.loading && page.error == null && last >= scroll.layoutInfo.totalItemsCount - 3) vm.load(true)
                    }
            }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { if (active) vm.load() }, state = pull,
                    modifier = Modifier.fillMaxSize(), indicator = {
                        PullToRefreshDefaults.IndicatorBox(state = pull, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
                        containerColor = MaterialTheme.colorScheme.surface) { CommunityLoadingIndicator(Modifier.size(16.dp), rotating = refreshing) }
                }) {
                LazyColumn(Modifier.fillMaxSize().quickScrollTarget(scroll, enabled = active).testTag("personal-list-$pageKey"), state = scroll, contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (showingReplies) {
                        items(page.replies, key = ProfileEntry::key) { entry -> ProfileEntryRow(entry) { open(entry.topicId, entry.number) } }
                    } else {
                        items(page.topics, key = { it.id }) { topic ->
                            val category = categories.firstOrNull { it.id == topic.categoryId }
                            if (style == TopicListStyle.TITLE) TopicTitleRow(topic, category, { open(topic.id, null) }, user)
                            else TopicCard(topic, category, { open(topic.id, null) }, user)
                        }
                    }
                    if (page.loading || (!page.loaded && page.error == null)) item { LoadingRow() }
                    page.error?.let { item { ErrorState(it, { if (active) vm.retry() }, login) } }
                    if (page.loaded && !page.loading && page.error == null && count == 0) item {
                        EmptyState("这里还没有内容", when {
                            showingReplies -> "你回复过的话题会出现在这里。"
                            vm.destination == AccountDestination.TOPICS -> "你创建的话题会出现在这里。"
                            vm.destination == AccountDestination.HISTORY -> "阅读过的话题会出现在这里。"
                            else -> "此信箱暂时没有私信。"
                        }, Icons.Rounded.Inbox)
                    }
                    if (page.next == null && !page.loading && page.error == null && count > 0) item {
                        Text("已显示全部", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.outline, fontSize = 11.sp)
                    }
                    item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
                }
            }
        }
    }
}
