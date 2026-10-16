package com.kudo.android.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
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
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DraftsScreen(user: Member, repository: CommunityRepository, settings: AppSettings,
    back: () -> Unit, login: () -> Unit, edit: (SavedDraft) -> Unit) {
    var local by remember(user.id) { mutableStateOf(settings.drafts(user.id)) }
    var cloud by remember(user.id) { mutableStateOf<List<SavedDraft>>(emptyList()) }
    var hasMore by remember(user.id) { mutableStateOf(false) }
    var offset by remember(user.id) { mutableIntStateOf(0) }
    var loading by remember(user.id) { mutableStateOf(false) }
    var error by remember(user.id) { mutableStateOf<Throwable?>(null) }
    var refresh by remember(user.id) { mutableIntStateOf(0) }
    var deleting by remember(user.id) { mutableStateOf<SavedDraft?>(null) }
    val sheet = remember { MutableTransitionState(false) }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    suspend fun load(more: Boolean) {
        loading = true; error = null
        local = settings.drafts(user.id)
        try {
            val page = withContext(Dispatchers.IO) { repository.cloudDrafts(if (more) offset else 0) }
            cloud = (if (more) cloud + page.first else page.first).distinctBy { it.key }
            offset = (if (more) offset else 0) + page.first.size; hasMore = page.second
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
        finally { loading = false }
    }
    LaunchedEffect(user.id, refresh) { load(false) }
    val pull = rememberPullToRefreshState()
    val refreshing = loading && (cloud.isNotEmpty() || local.isNotEmpty())
    LaunchedEffect(cloud.size, loading, hasMore, error) {
        snapshotFlow { scroll.isScrollInProgress to (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
            .distinctUntilChanged().collect { (scrolling, last) ->
                if (scrolling && hasMore && !loading && error == null && cloud.isNotEmpty() &&
                    last >= scroll.layoutInfo.totalItemsCount - 3) scope.launch { load(true) }
            }
    }
    Box(Modifier.fillMaxSize()) {
        MaterialPageScaffold(header = {
            AccountPageTopBar("我的草稿", back) { CommunityIconButton(Icons.Rounded.Refresh, "刷新草稿", { refresh++ }, enabled = !loading) }
        }) { padding ->
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { load(false) } }, state = pull,
                modifier = Modifier.fillMaxSize(), indicator = {
                    PullToRefreshDefaults.IndicatorBox(state = pull, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
                        containerColor = MaterialTheme.colorScheme.surface) { CommunityLoadingIndicator(Modifier.size(16.dp), rotating = refreshing) }
                }) {
                LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().quickScrollTarget(scroll, enabled = !sheet.currentState && !sheet.targetState), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
                    if (local.isNotEmpty()) item { Text("本机草稿", Modifier.padding(16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(local, key = { "local-${it.key}" }) { saved -> DraftRow(saved, { edit(saved) }, { deleting = saved; sheet.targetState = true }) }
                    item { Text("云端草稿", Modifier.padding(16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(cloud, key = { "cloud-${it.key}" }) { saved -> DraftRow(saved, { edit(saved) }, { deleting = saved; sheet.targetState = true }) }
                    if (loading) item { LoadingRow() }
                    error?.let { item { ErrorState(it, { refresh++ }, login) } }
                    if (!loading && error == null && cloud.isEmpty() && local.isEmpty()) item { EmptyState("还没有草稿", "未发布的内容会保存在这里。", Icons.Rounded.EditNote) }
                    if (hasMore && !loading && cloud.isNotEmpty()) item {
                        Text("向上滑动加载更多草稿", Modifier.fillMaxWidth().padding(18.dp), fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        CommunitySheet(sheet, "删除草稿", { if (!loading) sheet.targetState = false }, dismissEnabled = !loading, showActions = false) {
            Text("删除后无法恢复。", fontSize = 14.sp)
            error?.let { Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            CommunityButton(if (loading) "正在删除…" else "删除草稿", {
                val saved = deleting ?: return@CommunityButton
                scope.launch {
                    loading = true; error = null
                    try {
                        if (saved.backupKey != null) settings.clearDraft(saved.backupKey)
                        else if (saved.remote) withContext(Dispatchers.IO) { repository.deleteCloudDraft(saved) } else settings.clearDraft(saved.key)
                        sheet.targetState = false; deleting = null; load(false)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                    finally { loading = false }
                }
            }, enabled = !loading)
        }
    }
}

@Composable private fun DraftRow(saved: SavedDraft, edit: () -> Unit, delete: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = edit).padding(start = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(saved.title, fontSize = 15.sp)
            Text(saved.draft.raw, fontSize = 12.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (saved.time.isNotBlank()) Text(relativeTime(saved.time), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CommunityIconButton(Icons.Rounded.DeleteOutline, "删除${saved.title}", delete)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun CloudDraftScreen(key: String, user: Member, repository: CommunityRepository, settings: AppSettings,
    categories: List<Category>, back: () -> Unit, login: () -> Unit, published: (PublishResult) -> Unit) {
    val backupKey = "${user.id}_cloud_$key"
    var saved by remember(user.id, key) { mutableStateOf<SavedDraft?>(null) }
    var draft by remember(user.id, key) { mutableStateOf(settings.draft(backupKey)) }
    var loading by remember(user.id, key) { mutableStateOf(true) }
    var error by remember(user.id, key) { mutableStateOf<Throwable?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val categorySheet = remember { MutableTransitionState(false) }
    val scroll = rememberLazyListState()
    LaunchedEffect(user.id, key, refresh) {
        loading = true; error = null
        try {
            val result = withContext(Dispatchers.IO) { repository.cloudDraft(key) }
            saved = result
            if (draft.raw.isBlank() && draft.title.isBlank()) draft = result.draft
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
        finally { loading = false }
    }
    BackHandler(loading) { }
    fun change(value: Draft) { draft = value; settings.saveDraft(backupKey, value); message = "本机副本已保存" }
    Box(Modifier.fillMaxSize()) {
        MaterialPageScaffold(modifier = Modifier.imePadding(), header = {
            AccountPageTopBar("编辑云端草稿", { if (!loading) back() }) {
                CommunityButton("保存", {
                    val snapshot = saved ?: return@CommunityButton
                    scope.launch {
                        loading = true; error = null
                        try {
                            saved = withContext(Dispatchers.IO) { repository.saveCloudDraft(snapshot, draft) }
                            settings.clearDraft(backupKey); message = "已保存到云端"
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                        finally { loading = false }
                    }
                }, enabled = !loading && saved != null, compact = true)
            }
        }) { padding ->
            Column(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).quickScrollTarget(scroll, enabled = !categorySheet.currentState && !categorySheet.targetState), state = scroll, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (loading) item { LoadingRow() }
                    if (saved?.topicId == null) item {
                        CommunityTextField(draft.title, { change(draft.copy(title = it)) }, placeholder = "标题", enabled = !loading)
                    }
                    if (saved?.topicId == null && saved?.privateMessage == false) item {
                        Text("分类：${categories.firstOrNull { it.id == draft.categoryId }?.name ?: "选择分类"}",
                            Modifier.fillMaxWidth().clickable(enabled = !loading) { categorySheet.targetState = true }, fontSize = 14.sp)
                    }
                    item { CommunityTextField(draft.raw, { change(draft.copy(raw = it)) }, Modifier.fillMaxWidth().heightIn(min = 220.dp), placeholder = "正文", enabled = !loading, minLines = 8) }
                    error?.let { item { ErrorState(it, { refresh++ }, login) } }
                    if (message.isNotBlank()) item { Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    CommunityButton("发布", {
                        val snapshot = saved ?: return@CommunityButton
                        scope.launch {
                            loading = true; error = null
                            try {
                                val result = withContext(Dispatchers.IO) { repository.publishCloudDraft(snapshot, draft) }
                                settings.clearDraft(backupKey); published(result)
                            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                            finally { loading = false }
                        }
                    }, enabled = !loading && saved != null && draft.raw.isNotBlank() &&
                        (saved?.topicId != null || draft.title.isNotBlank() && (saved?.privateMessage == true || draft.categoryId > 0)))
                }
            }
        }
        CommunitySheet(categorySheet, "选择分类", { categorySheet.targetState = false }, showActions = false) {
            categories.forEach { category -> Text(category.name, Modifier.fillMaxWidth().clickable { change(draft.copy(categoryId = category.id)); categorySheet.targetState = false }.padding(vertical = 10.dp), fontSize = 14.sp) }
        }
    }
}
