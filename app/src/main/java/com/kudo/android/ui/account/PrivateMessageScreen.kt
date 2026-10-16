package com.kudo.android.ui.account

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.editor.*
import kotlinx.coroutines.*

@Composable
internal fun PrivateMessageScreen(member: Member, repository: CommunityRepository, settings: AppSettings,
    back: () -> Unit, published: (PublishResult) -> Unit, recipient: String = "", existingDraftKey: String = "",
    source: PostMessageSource? = null) {
    val draftKey = existingDraftKey.takeIf { it.startsWith("${member.id}_message_") }
        ?: privateMessageDraftKey(member.id, recipient, source)
    var draft by remember(member.id, draftKey) { mutableStateOf(initialPrivateMessageDraft(settings.draft(draftKey), recipient, source)) }
    LaunchedEffect(draftKey) { if (source != null) settings.saveDraft(draftKey, draft) }
    var sending by remember { mutableStateOf(false) }
    var uploading by remember(draftKey) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var rules by remember(member.id) { mutableStateOf<PersonalMessageRules?>(null) }
    var rulesLoading by remember(member.id) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val bodyFocus = remember(draftKey) { FocusRequester() }
    val imageInsertion = remember(draftKey) { EditorImageInsertion() }
    var body by rememberSaveable(draftKey, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.raw, TextRange(draft.raw.length))) }
    var rtl by rememberSaveable(draftKey) { mutableStateOf(false) }
    var preview by remember(draftKey) { mutableStateOf(false) }
    body = body.syncMarkdown(draft.raw)
    fun change(value: Draft) {
        if (sending) return
        imageInsertion.edited(draft.raw, value.raw)
        draft = value; settings.saveDraft(draftKey, value); error = null
    }
    fun changeBody(value: TextFieldValue) {
        if (sending) return
        body = value
        if (value.text != draft.raw) change(draft.copy(raw = value.text))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !sending && !uploading) {
            uploading = true; error = null
            scope.launch {
                try {
                    val markdown = withContext(Dispatchers.IO) { uploadEditorImage(repository, context, uri) }
                    changeBody(body.syncMarkdown(imageInsertion.insert(draft.raw, markdown)))
                } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "上传失败，正文已保留" }
                finally { uploading = false }
            }
        }
    }
    LaunchedEffect(member.id) {
        rulesLoading = true
        try { rules = withContext(Dispatchers.IO) { repository.personalMessageRules() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Unable to read private message length rules" }
        finally { rulesLoading = false }
    }
    BackHandler(sending || uploading) { }
    MaterialPageScaffold(modifier = Modifier.imePadding(), header = {
        AccountPageTopBar("写私信", { if (!sending && !uploading) back() }) {
            CommunityButton(if (sending) "发送中…" else "发送", {
                val snapshot = draft
                if (sending || uploading || rulesLoading) return@CommunityButton
                val currentRules = rules ?: return@CommunityButton
                val validation = currentRules.problem(snapshot.raw)
                if (validation != null) { error = validation; return@CommunityButton }
                sending = true; error = null
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { repository.createPrivateMessage(snapshot) }
                        settings.clearDraft(draftKey); published(result)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
                    finally { sending = false }
                }
            }, enabled = !sending && !uploading && !rulesLoading && rules != null && rules?.problem(draft.raw) == null && draft.raw.isNotBlank() && draft.title.isNotBlank() && messageRecipients(draft.recipients).isNotEmpty(), compact = true)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                CommunityTextField(draft.recipients, { change(draft.copy(recipients = it)) }, Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 14.dp), placeholder = "收件人用户名，多个用逗号分隔", enabled = !sending)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CommunityTextField(draft.title, { change(draft.copy(title = it)) }, Modifier.fillMaxWidth().padding(vertical = 14.dp), placeholder = "标题", enabled = !sending)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CommunityTextField(body, ::changeBody, Modifier.fillMaxWidth().heightIn(min = 280.dp).padding(vertical = 14.dp)
                    .focusRequester(bodyFocus).testTag("editor-body"), placeholder = "正文", enabled = !sending, minLines = 8,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp,
                        textDirection = if (rtl) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr))
            }
            rules?.let { currentRules ->
                Text(replyLength(draft.raw).toString() + " / " + currentRules.maximum, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 12.sp,
                    color = if (draft.raw.length > currentRules.maximum) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            Text(if (uploading) "正在上传图片…" else "草稿已自动保存在本机", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EditorToolbar(body, ::changeBody, {
                imageInsertion.prepare(draft.raw, body.selection); picker.launch("image/*")
            }, !sending, uploading, { bodyFocus.requestFocus() }, rtl, { rtl = !rtl },
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp),
                preview = { keyboard?.hide(); preview = true })
        }
    }
    if (preview) EditorPreviewDialog(draft.raw) { preview = false }
}
