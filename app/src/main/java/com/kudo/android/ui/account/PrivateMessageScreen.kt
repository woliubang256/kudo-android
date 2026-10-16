package com.kudo.android.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import kotlinx.coroutines.*

@Composable
internal fun PrivateMessageScreen(member: Member, repository: CommunityRepository, settings: AppSettings,
    back: () -> Unit, published: (PublishResult) -> Unit, recipient: String = "", existingDraftKey: String = "") {
    val draftKey = existingDraftKey.takeIf { it.startsWith("${member.id}_message_") }
        ?: "${member.id}_message_${recipient.lowercase(java.util.Locale.ROOT).ifBlank { "0" }}"
    var draft by remember(member.id, draftKey) { mutableStateOf(settings.draft(draftKey).let {
        if (it.recipients.isBlank() && recipient.isNotBlank()) it.copy(recipients = recipient) else it
    }) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(sending) { }
    fun change(value: Draft) { draft = value; settings.saveDraft(draftKey, value); error = null }
    MaterialPageScaffold(modifier = Modifier.imePadding(), header = {
        AccountPageTopBar("写私信", { if (!sending) back() }) {
            CommunityButton(if (sending) "发送中…" else "发送", {
                val snapshot = draft
                sending = true; error = null
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { repository.createPrivateMessage(snapshot) }
                        settings.clearDraft(draftKey); published(result)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
                    finally { sending = false }
                }
            }, enabled = !sending && draft.raw.isNotBlank() && draft.title.isNotBlank() && messageRecipients(draft.recipients).isNotEmpty(), compact = true)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CommunityTextField(draft.recipients, { change(draft.copy(recipients = it)) }, Modifier.fillMaxWidth().padding(top = 20.dp), placeholder = "收件人用户名，多个用逗号分隔", enabled = !sending)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CommunityTextField(draft.title, { change(draft.copy(title = it)) }, Modifier.fillMaxWidth(), placeholder = "标题", enabled = !sending)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CommunityTextField(draft.raw, { change(draft.copy(raw = it)) }, Modifier.fillMaxWidth().weight(1f), placeholder = "正文", enabled = !sending, minLines = 6)
            error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            Text("草稿已自动保存在本机", Modifier.navigationBarsPadding().padding(bottom = 16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
