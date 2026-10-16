package com.kudo.android.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun AccountRecordsScreen(destination: AccountDestination, user: Member, repository: CommunityRepository,
    settings: AppSettings, back: () -> Unit, login: () -> Unit, website: (String) -> Unit,
    topic: (Int, Int?) -> Unit, channel: (Int) -> Unit, edit: (Int?, Int?) -> Unit, copy: (String) -> Unit) {
    if (destination == AccountDestination.BADGES) {
        BadgeScreen(user, repository, back, login, website)
        return
    }
    var records by remember(user.id, destination) { mutableStateOf<List<AccountRecord>>(emptyList()) }
    var loading by remember(user.id, destination) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<AccountRecord?>(null) }
    var description by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var validity by remember { mutableIntStateOf(7) }
    val sheet = remember { MutableTransitionState(false) }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(user.id, destination, refresh) {
        loading = true; error = null
        try { records = withContext(Dispatchers.IO) { repository.accountRecords(destination, user.username) } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
        finally { loading = false }
    }
    BackHandler(busy) { }
    Box(Modifier.fillMaxSize()) {
        MaterialPageScaffold(header = {
            AccountPageTopBar(destination.title, { if (!busy) back() }) {
                if (destination == AccountDestination.INVITES) CommunityIconButton(Icons.Rounded.Add, "创建邀请链接", { selected = null; sheet.targetState = true })
                if (destination == AccountDestination.CHAT) CommunityIconButton(Icons.Rounded.AddComment, "发起聊天", { selected = null; sheet.targetState = true })
                CommunityIconButton(Icons.Rounded.Refresh, "刷新", { refresh++ }, enabled = !busy && !loading)
            }
        }) { padding ->
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().quickScrollTarget(scroll, enabled = !sheet.currentState && !sheet.targetState), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
                if (loading) item { LoadingRow() }
                error?.let { item { ErrorState(it, { refresh++ }, login) } }
                itemsIndexed(records, key = { index, record -> "${record.id}-$index" }) { _, record ->
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable {
                        when (destination) {
                            AccountDestination.CHAT -> channel(record.id)
                            AccountDestination.INVITES -> if (record.url.isNotBlank()) copy(record.url)
                            else -> { selected = record; sheet.targetState = true }
                        }
                    }.padding(horizontal = 16.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.title, fontSize = 15.sp)
                        if (record.body.isNotBlank()) Text(record.body, fontSize = 13.sp, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (record.time.isNotBlank()) Text(if (destination == AccountDestination.INVITES) "有效期至 ${record.time.take(10)}" else relativeTime(record.time), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (destination == AccountDestination.INVITES) Text("点击复制链接", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (!loading && error == null && records.isEmpty()) item {
                    EmptyState(when (destination) {
                        AccountDestination.PENDING -> "没有待审核内容"
                        AccountDestination.INVITES -> "暂无待使用的邀请链接"
                        else -> "还没有聊天频道"
                    }, "", Icons.Rounded.Inbox)
                }
            }
        }
        CommunitySheet(sheet, when (destination) { AccountDestination.INVITES -> "创建邀请链接"; AccountDestination.CHAT -> "发起聊天"; else -> "待审核内容" },
            { if (!busy) sheet.targetState = false }, dismissEnabled = !busy, showActions = false) {
            if (destination == AccountDestination.INVITES) {
                CommunityTextField(description, { description = it }, placeholder = "备注（选填）", enabled = !busy)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CommunityTextField(email, { email = it }, placeholder = "限制邮箱（选填）", enabled = !busy, singleLine = true)
                Text("有效期 · 最多使用 1 次", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AccountTabs(listOf("1 天", "7 天", "30 天", "90 天"), listOf(1, 7, 30, 90).indexOf(validity)) { if (!busy) validity = listOf(1, 7, 30, 90)[it] }
                error?.let { Text(it.message.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                CommunityButton(if (busy) "正在创建…" else "创建并复制链接", {
                    scope.launch {
                        busy = true; error = null
                        try {
                            val created = withContext(Dispatchers.IO) { repository.createAccountInvite(description, email, validity) }
                            copy(created.url); sheet.targetState = false; refresh++
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                        finally { busy = false }
                    }
                }, enabled = !busy)
            } else if (destination == AccountDestination.CHAT) {
                CommunityTextField(email, { email = it }, placeholder = "聊天对象用户名，多个用逗号分隔", enabled = !busy)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CommunityTextField(description, { description = it }, placeholder = "群聊名称（选填）", enabled = !busy)
                error?.let { Text(it.message.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                CommunityButton(if (busy) "正在创建…" else "开始聊天", {
                    scope.launch {
                        busy = true; error = null
                        try {
                            val id = withContext(Dispatchers.IO) { repository.createChat(email, description) }
                            sheet.targetState = false; refresh++; channel(id)
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                        finally { busy = false }
                    }
                }, enabled = !busy && messageRecipients(email).isNotEmpty())
            } else selected?.let { record ->
                Text(record.title, fontSize = 16.sp)
                Text(record.body, fontSize = 14.sp)
                record.topicId?.let { id -> TextButton({ sheet.targetState = false; topic(id, null) }) { Text("打开原话题") } }
                Text("撤回后将退出审核队列。重新编辑回复会直接回复话题。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it.message.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                fun withdraw(reopen: Boolean) {
                    scope.launch {
                        busy = true; error = null
                        try {
                            if (reopen) {
                                val key = "${user.id}_${record.topicId ?: "new"}_0"
                                val existing = settings.draft(key)
                                require(existing.raw.isBlank() || existing.raw == record.body) { "本机已有其他草稿，请先处理草稿后再重新编辑" }
                                settings.saveDraft(key, Draft(if (record.topicId == null) record.title else "", record.body, JSONObject(record.payload).optInt("category_id")))
                            }
                            withContext(Dispatchers.IO) { repository.withdrawPending(record.id) }
                            sheet.targetState = false; refresh++
                            if (reopen) edit(record.topicId, null)
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                        finally { busy = false }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CommunityButton(if (busy) "处理中…" else "确认撤回", { withdraw(false) }, enabled = !busy)
                    CommunityButton("撤回并编辑", { withdraw(true) }, enabled = !busy)
                }
            }
        }
    }
}
