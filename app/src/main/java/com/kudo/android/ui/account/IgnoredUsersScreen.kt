package com.kudo.android.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import kotlinx.coroutines.*

@Composable
fun IgnoredUsersScreen(viewer: Member?, repository: CommunityRepository, back: () -> Unit, login: () -> Unit, restored: (Int, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    var busy by remember(viewer?.id) { mutableStateOf<String?>(null) }
    var error by remember(viewer?.id) { mutableStateOf<Throwable?>(null) }
    var uncertain by remember(viewer?.id) { mutableStateOf(setOf<String>()) }
    MaterialPageScaffold(header = { PageTopBar("已忽略用户", back) }) { padding ->
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().quickScrollTarget(scroll), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("与 Linux.do 账号同步。取消忽略后，恢复此用户的内容和正常通知。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (viewer == null) item { EmptyState("登录后管理忽略用户", "使用与社区相同的账号设置。", actionLabel = "登录", action = login) }
            else {
                if (viewer.ignoredUsers.isEmpty()) item { EmptyState("没有忽略的用户", "在主题列表的操作菜单中选择屏蔽。") }
                items(viewer.ignoredUsers.sorted(), key = { it }) { username ->
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("@$username", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = {
                            if (busy != null) return@TextButton
                            busy = username; error = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { repository.ignoreUser(viewer, username, null) }
                                    restored(viewer.id, username)
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) {
                                    error = e
                                    if ((e as? ApiFailure)?.kind == FailureKind.NETWORK || e.message.orEmpty().contains("未确认")) uncertain = uncertain + username
                                } finally { busy = null }
                            }
                        }, enabled = busy == null && username !in uncertain) { Text(if (busy == username) "恢复中…" else "取消忽略") }
                    }
                }
            }
            error?.let { item { Text(it.message ?: "恢复失败，请稍后再试", color = MaterialTheme.colorScheme.error) } }
        }
    }
}
