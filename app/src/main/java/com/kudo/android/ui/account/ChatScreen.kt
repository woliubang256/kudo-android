package com.kudo.android.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.topic.RichContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jsoup.nodes.Entities

internal class ChatViewModel(private val repository: CommunityRepository, val channelId: Int, val initialMessage: Int? = null) : ViewModel() {
    var title by mutableStateOf("聊天"); private set
    var messages by mutableStateOf<List<ChatEntry>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var morePast by mutableStateOf(false); private set
    var error by mutableStateOf<Throwable?>(null); private set
    var sending by mutableStateOf(false); private set
    private var refreshJob: Job? = null
    private var loadJob: Job? = null
    private var generation = 0
    private var active = false
    private var readJob: Job? = null
    private var lastRead = 0
    private var readTarget = 0
    init {
        viewModelScope.launch {
            try { title = withContext(Dispatchers.IO) {
                val json = repository.client.get("/chat/api/channels/$channelId")
                (json.optJSONObject("channel") ?: json).text("title").ifBlank { "聊天" }
            } } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    fun active(value: Boolean) {
        active = value
        if (!value) { readJob?.cancel(); readJob = null }
        refreshJob?.cancel(); refreshJob = null
        if (value) refreshJob = viewModelScope.launch {
            while (isActive) { if (!loading && !sending) load(); delay(20_000) }
        }
    }
    fun load(past: Boolean = false) {
        if (loading) return
        val before = if (past) messages.minOfOrNull { it.id } else null
        if (past && (before == null || !morePast)) return
        loading = true; error = null
        val epoch = ++generation
        loadJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.chatMessages(channelId, before, initialMessage.takeIf { messages.isEmpty() }) }
                if (epoch == generation) {
                    val updates = result.messages.associateBy { it.id }
                    messages = (messages.filterNot { it.id in updates } + result.messages).filterNot { it.deleted }.sortedBy { it.id }
                    if (past || before == null && messages.size <= result.messages.size) morePast = result.morePast
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (epoch == generation) error = e }
            finally { if (epoch == generation) loading = false }
        }
    }
    fun readVisible(messageId: Int, accepted: (Int) -> Unit) {
        if (!active || messageId <= lastRead) return
        readTarget = maxOf(readTarget, messageId)
        if (readJob?.isActive == true) return
        readJob = viewModelScope.launch {
            try {
                while (active && readTarget > lastRead) {
                    val target = readTarget
                    withContext(Dispatchers.IO) { repository.readChat(channelId, target) }
                    lastRead = target
                    accepted(target)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
        }
    }
    fun send(raw: String, accepted: () -> Unit) {
        if (sending || raw.isBlank()) return
        sending = true; error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.sendChat(channelId, raw) }
                accepted()
                loadJob?.cancel(); generation++; loading = false; load()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
            finally { sending = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(vm: ChatViewModel, member: Member, settings: AppSettings, back: () -> Unit,
    login: () -> Unit, website: () -> Unit, link: (String) -> Unit, user: (String) -> Unit,
    read: (Int) -> Unit = {}) {
    val draftKey = "${member.id}_chat_${vm.channelId}"
    var raw by remember(member.id, vm.channelId) { mutableStateOf(settings.draft(draftKey).raw) }
    val scroll = rememberLazyListState()
    val pull = rememberPullToRefreshState()
    val refreshing = vm.loading && vm.messages.isNotEmpty()
    LaunchedEffect(vm.messages.size, vm.loading, vm.morePast, vm.error) {
        snapshotFlow { scroll.isScrollInProgress to (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
            .distinctUntilChanged().collect { (scrolling, last) ->
                if (scrolling && vm.morePast && !vm.loading && vm.error == null && last >= scroll.layoutInfo.totalItemsCount - 3) vm.load(true)
            }
    }
    val pastViewport = rememberQuickScrollPastViewport(scroll)
    val awayFromTop = pastViewport && scroll.canScrollForward
    var located by remember(vm) { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visibleId by remember(scroll) { derivedStateOf { scroll.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Int }.maxOrNull() } }
    val latestRead by rememberUpdatedState(read)
    LifecycleResumeEffect(vm) { vm.active(true); onPauseOrDispose { vm.active(false) } }
    LaunchedEffect(vm.messages, vm.initialMessage) {
        if (!located && vm.messages.isNotEmpty()) {
            val index = vm.messages.asReversed().indexOfFirst { it.id == vm.initialMessage }
            if (index >= 0) scroll.scrollToItem(index)
            located = true
        }
    }
    LaunchedEffect(visibleId, lifecycle, located, vm.error) {
        if (lifecycle.isAtLeast(Lifecycle.State.RESUMED) && located && vm.error == null) visibleId?.let { id ->
            delay(700)
            vm.readVisible(id) { latestRead(it) }
        }
    }
    BackHandler(vm.sending) { }
    MaterialPageScaffold(modifier = Modifier.imePadding(), header = {
        AccountPageTopBar(vm.title, { if (!vm.sending) back() }) {
            CommunityIconButton(Icons.Rounded.Refresh, "刷新聊天", { vm.load() }, enabled = !vm.loading)
            CommunityIconButton(Icons.Rounded.Language, "在应用内网页查看聊天", website)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize()) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.load() }, state = pull,
                modifier = Modifier.weight(1f).fillMaxWidth(), indicator = {
                    PullToRefreshDefaults.IndicatorBox(state = pull, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
                        containerColor = MaterialTheme.colorScheme.surface) { CommunityLoadingIndicator(Modifier.size(16.dp), rotating = refreshing) }
                }) {
            LazyColumn(Modifier.fillMaxSize().quickScrollTarget(scrolled = awayFromTop) {
                scroll.animateScrollToItem((scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            }, state = scroll, reverseLayout = true,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(vm.messages.asReversed(), key = { it.id }) { message ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(message.author, 32.dp, onClick = { user(message.author.username) })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row {
                                Text(message.author.displayName, Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(relativeTime(message.time), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RichContent(message.cooked.ifBlank { Entities.escape(message.raw).replace("\n", "<br>") }, {}, link, galleryKey = "chat-${vm.channelId}-${message.id}")
                        }
                    }
                }
                vm.error?.let { item { ErrorState(it, { vm.load() }, login) } }
                if (vm.loading) item { LoadingRow() }
                if (vm.morePast && !vm.loading) item {
                    Text("向上滑动加载更早的消息", Modifier.fillMaxWidth().padding(12.dp), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                if (!vm.loading && vm.messages.isEmpty() && vm.error == null) item { EmptyState("还没有消息", "写下第一条消息。", Icons.Rounded.Forum) }
            }
            }
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CommunityTextField(raw, { raw = it; settings.saveDraft(draftKey, Draft(raw = it)) }, Modifier.weight(1f).heightIn(min = 40.dp, max = 140.dp),
                    placeholder = "发送消息…", enabled = !vm.sending, maxLines = 5)
                CommunityButton(if (vm.sending) "发送中" else "发送", { vm.send(raw) { raw = ""; settings.clearDraft(draftKey) } },
                    enabled = !vm.sending && raw.isNotBlank(), compact = true)
            }
        }
    }
}
