package com.kudo.android.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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

internal class PersonalTopicsViewModel(private val repository: CommunityRepository, private val username: String,
    val destination: AccountDestination) : ViewModel() {
    var topics by mutableStateOf<List<Topic>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<Throwable?>(null); private set
    var next by mutableStateOf<String?>(null); private set
    var mailbox by mutableStateOf("inbox"); private set
    var topicTab by mutableStateOf(PersonalTopicsTab.TOPICS); private set
    private var request: Job? = null
    private var generation = 0
    private var historyWasShown = false
    init { load() }
    fun resumeHistory() {
        if (destination != AccountDestination.HISTORY) return
        if (historyWasShown) load()
        historyWasShown = true
    }
    fun selectMailbox(value: String) {
        if (value == mailbox) return
        mailbox = value; topics = emptyList(); next = null; load()
    }
    fun selectTopicTab(value: PersonalTopicsTab) {
        if (destination != AccountDestination.TOPICS || value == topicTab) return
        request?.cancel(); generation++
        topicTab = value; topics = emptyList(); replies = emptyList(); next = null; error = null
        load()
    }
    var replies by mutableStateOf<List<ProfileEntry>>(emptyList()); private set
    private fun itemCount() = if (destination == AccountDestination.TOPICS && topicTab == PersonalTopicsTab.REPLIES) replies.size else topics.size
    fun retry() = load(more = itemCount() > 0 && next != null)
    fun load(more: Boolean = false) {
        if (more && (loading || next == null)) return
        request?.cancel()
        val epoch = ++generation
        val cursor = if (more) next else null
        loading = true; error = null
        request = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    if (destination == AccountDestination.TOPICS && topicTab == PersonalTopicsTab.REPLIES)
                        repository.profilePage(username, ProfileTab.REPLIES, cursor)
                    else repository.personalTopics(username, destination, cursor, mailbox)
                }
                if (epoch == generation) {
                    when (page) {
                        is ProfilePage -> {
                            replies = (if (more) replies + page.entries else page.entries).distinctBy(ProfileEntry::key)
                            next = page.next?.takeUnless { it == cursor }
                        }
                        is TopicPage -> {
                            topics = (if (more) topics + page.topics else page.topics).distinctBy { it.id }
                            next = page.next?.takeUnless { it == cursor }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (epoch == generation) error = e }
            finally { if (epoch == generation) loading = false }
        }
    }
}

@Composable
internal fun AccountPageTopBar(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding().heightIn(min = 48.dp)
        .padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CommunityIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back)
        Text(title, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        actions()
    }
}

@Composable
internal fun AccountTabs(labels: List<String>, selection: Int, modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface, select: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().background(containerColor).horizontalScroll(rememberScrollState())
        .padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Tab) { select(index) }
                .semantics { selected = selection == index }.padding(horizontal = 12.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
                if (selection == index) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp).width(28.dp).height(7.dp)
                    .background(Brush.horizontalGradient(listOf(LogoYellow.copy(alpha = .25f), LogoYellow)), RoundedCornerShape(4.dp)))
                Text(label, fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = if (selection == index) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selection == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalTopicsScreen(vm: PersonalTopicsViewModel, categories: List<Category>, style: TopicListStyle,
    back: () -> Unit, login: () -> Unit, open: (Int, Int?) -> Unit, user: (String) -> Unit,
    composeMessage: () -> Unit, drafts: () -> Unit = {}) {
    if (vm.destination == AccountDestination.HISTORY) LifecycleResumeEffect(vm) {
        vm.resumeHistory()
        onPauseOrDispose {}
    }
    val scroll = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        AccountPageTopBar(vm.destination.title, back) {
            if (vm.destination == AccountDestination.TOPICS) CommunityIconButton(Icons.Rounded.EditNote, "我的草稿", drafts)
            if (vm.destination == AccountDestination.MESSAGES) CommunityIconButton(Icons.Rounded.Edit, "写私信", composeMessage)
            CommunityIconButton(Icons.Rounded.Refresh, "刷新", { vm.load() })
        }
        if (vm.destination == AccountDestination.MESSAGES) {
            val mailboxes = listOf("inbox", "sent", "archive")
            AccountTabs(listOf("收件箱", "已发送", "归档"), mailboxes.indexOf(vm.mailbox)) { vm.selectMailbox(mailboxes[it]) }
        }
        if (vm.destination == AccountDestination.TOPICS) {
            AccountTabs(PersonalTopicsTab.entries.map(PersonalTopicsTab::title), vm.topicTab.ordinal) { vm.selectTopicTab(PersonalTopicsTab.entries[it]) }
        }
        val showingReplies = vm.destination == AccountDestination.TOPICS && vm.topicTab == PersonalTopicsTab.REPLIES
        val count = if (showingReplies) vm.replies.size else vm.topics.size
        val pull = rememberPullToRefreshState()
        val refreshing = vm.loading && count > 0
        LaunchedEffect(vm.topicTab, vm.next, vm.loading, vm.error) {
            snapshotFlow { scroll.isScrollInProgress to (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
                .distinctUntilChanged().collect { (scrolling, last) ->
                    if (scrolling && vm.next != null && !vm.loading && vm.error == null && last >= scroll.layoutInfo.totalItemsCount - 3) vm.load(true)
                }
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.load() }, state = pull,
            modifier = Modifier.weight(1f).fillMaxWidth(), indicator = {
                PullToRefreshDefaults.IndicatorBox(state = pull, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surface) { CommunityLoadingIndicator(Modifier.size(16.dp), rotating = refreshing) }
            }) {
            LazyColumn(Modifier.fillMaxSize().quickScrollTarget(scroll), state = scroll, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                if (showingReplies) {
                    items(vm.replies, key = ProfileEntry::key) { entry -> ProfileEntryRow(entry) { open(entry.topicId, entry.number) } }
                } else {
                    items(vm.topics, key = { it.id }) { topic ->
                        val category = categories.firstOrNull { it.id == topic.categoryId }
                        if (style == TopicListStyle.TITLE) TopicTitleRow(topic, category, { open(topic.id, null) }, user)
                        else TopicCard(topic, category, { open(topic.id, null) }, user)
                    }
                }
                if (vm.loading) item { LoadingRow() }
                vm.error?.let { item { ErrorState(it, vm::retry, login) } }
                if (!vm.loading && vm.error == null && count == 0) item {
                    EmptyState("这里还没有内容", when {
                        showingReplies -> "你回复过的话题会出现在这里。"
                        vm.destination == AccountDestination.TOPICS -> "你创建的话题会出现在这里。"
                        vm.destination == AccountDestination.HISTORY -> "阅读过的话题会出现在这里。"
                        else -> "此信箱暂时没有私信。"
                    }, Icons.Rounded.Inbox)
                }
                if (vm.next == null && !vm.loading && vm.error == null && count > 0) item {
                    Text("已显示全部", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.outline, fontSize = 11.sp)
                }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        }
    }
}
