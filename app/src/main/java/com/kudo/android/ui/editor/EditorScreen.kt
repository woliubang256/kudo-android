package com.kudo.android.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.kudo.android.data.*
import com.kudo.android.ui.account.LocalWebVerification
import com.kudo.android.ui.components.*
import com.kudo.android.ui.components.liquid.LiquidModalAction
import com.kudo.android.ui.theme.LogoYellow

@Composable
fun EditorScreen(vm: EditorViewModel, categories: List<Category>, categoryError: Throwable?, reloadCategories: () -> Unit, back: () -> Unit,
    replyAuthor: Member? = null) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.upload(context, it) } }
    BackHandler(vm.sending) { /* Keep the active request attached to its editor. */ }
    EditorForm(vm.draft, categories, vm::edit, vm::publish, { picker.launch("image/*") }, back,
        reply = vm.topicId != null, categoryError = categoryError, reloadCategories = reloadCategories,
        sending = vm.sending, uploading = vm.uploading, error = vm.error,
        replyRules = vm.replyRules, rulesError = vm.rulesError, reloadRules = vm::reloadReplyRules, replyAuthor = replyAuthor,
        imageSelection = vm::prepareImageUpload, tagSearch = vm::searchTags)
}

@Composable
internal fun ReplyEditorSheet(visibility: MutableTransitionState<Boolean>, sending: Boolean, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    CommunitySheet(visibility, "写回复", { if (!sending) { keyboard?.hide(); dismiss() } }, showTitle = false,
        dismissEnabled = !sending, scrollContent = false, showActions = false, contentPadding = PaddingValues(0.dp), content = content)
}

@Composable
internal fun EditorForm(draft: Draft, categories: List<Category>, edit: (Draft) -> Unit, publish: () -> Unit,
    pickImage: () -> Unit, back: () -> Unit, reply: Boolean = false, categoryError: Throwable? = null,
    reloadCategories: () -> Unit = {}, sending: Boolean = false, uploading: Boolean = false, error: String? = null,
    replyRules: ReplyRules? = null, rulesError: Throwable? = null, reloadRules: () -> Unit = {}, replyAuthor: Member? = null,
    editing: Boolean = false, editingReply: Boolean = false, canSave: Boolean = false,
    editReason: String = "", reasonChange: (String) -> Unit = {}, imageSelection: (TextRange) -> Unit = {},
    tagSearch: suspend (String, Int, List<TopicTag>) -> List<TopicTag> = { _, _, _ -> emptyList() },
    extra: @Composable ColumnScope.() -> Unit = {}) {
    val categorySheet = remember { MutableTransitionState(false) }
    val tagSheet = remember { MutableTransitionState(false) }
    val modalShowing = categorySheet.currentState || categorySheet.targetState || tagSheet.currentState || tagSheet.targetState
    val bodyFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val verification = LocalWebVerification.current
    val rulesNeedVerification = (rulesError as? ApiFailure)?.kind == FailureKind.VERIFICATION
    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.raw, TextRange(draft.raw.length))) }
    var rtl by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    // Acknowledged text keeps IME composition and selection; external changes are rebased.
    body = body.syncMarkdown(draft.raw)
    fun changeBody(value: TextFieldValue) { body = value; if (value.text != draft.raw) edit(draft.copy(raw = value.text)) }
    val enabled = !sending && !modalShowing
    val canPublish = if (editing) enabled && !uploading && canSave else enabled && !uploading && draft.raw.isNotBlank() &&
        (if (reply) replyRules != null && replyRules.problem(draft.raw) == null else draft.title.isNotBlank() && draft.categoryId > 0)
    LaunchedEffect(reply) {
        if (reply) { withFrameNanos { }; bodyFocus.requestFocus(); keyboard?.show() }
    }
    val publishText = if (editing) { if (sending) "保存中…" else "保存" } else if (sending) "发布中…" else "发布"
    Box(Modifier.then(if (reply) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).background(MaterialTheme.colorScheme.surface)) {
        val header: @Composable () -> Unit = {
            if (!reply) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopBarIconButton(Icons.Rounded.Close, "关闭编辑器", back, enabled = enabled)
                    Text(if (editing) { if (editingReply) "编辑回复" else "编辑话题" } else "发布话题", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    CommunityButton(publishText, publish, enabled = canPublish, compact = true)
                }
                if (!editing) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("editor-destinations"), verticalAlignment = Alignment.CenterVertically) {
                    EditorDestination("类别", categories.firstOrNull { it.id == draft.categoryId }?.name ?: "请选择", Icons.Rounded.AddBox,
                        enabled, Modifier.weight(1f).testTag("editor-category")) {
                        focus.clearFocus(force = true); keyboard?.hide(); categorySheet.targetState = true
                    }
                    VerticalDivider(Modifier.height(24.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    EditorDestination("标签", when (draft.tags.size) {
                        0 -> if (draft.categoryId > 0) "多选" else "先选类别"
                        1 -> draft.tags.first()
                        else -> "已选 ${draft.tags.size} 个"
                    }, Icons.Rounded.Tag, enabled && draft.categoryId > 0, Modifier.weight(1f).testTag("editor-tags")) {
                        focus.clearFocus(force = true); keyboard?.hide(); tagSheet.targetState = true
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        val form: @Composable (PaddingValues) -> Unit = { padding ->
            Column(Modifier.then(if (reply) Modifier.fillMaxWidth().heightIn(max = 300.dp) else Modifier.fillMaxSize())
                .then(if (modalShowing) Modifier.clearAndSetSemantics {} else Modifier)
                .focusProperties { onEnter = { if (modalShowing) cancelFocusChange() } }.focusGroup().testTag("editor-form")) {
                Column(Modifier.weight(1f, fill = !reply).then(if (!reply) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(top = padding.calculateTopPadding()).padding(horizontal = 20.dp)) {
                    if (!reply && (!editing || !editingReply)) {
                        CommunityTextField(draft.title, { edit(draft.copy(title = it)) }, Modifier.fillMaxWidth().padding(vertical = 20.dp).testTag("editor-title"),
                            placeholder = "标题", enabled = enabled, maxLines = 3,
                            textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    CommunityTextField(body, ::changeBody, Modifier.fillMaxWidth()
                        .then(if (reply) Modifier.heightIn(min = 80.dp, max = 180.dp) else Modifier.heightIn(min = 280.dp))
                        .padding(vertical = 14.dp).focusRequester(bodyFocus).testTag("editor-body"),
                        placeholder = if (reply) replyAuthor?.displayName?.takeIf(String::isNotBlank)?.let { "回复 $it" } ?: "回复" else "正文",
                        enabled = enabled, minLines = if (reply) 3 else 8,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, textDirection = if (rtl) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr))
                    if (editing) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        CommunityTextField(editReason, reasonChange, Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("edit-reason"),
                            placeholder = "编辑说明（选填）", enabled = enabled, maxLines = 3)
                    }
                }
                error?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                extra()
                if (reply && rulesError != null) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(rulesError.message ?: "读取回帖字数规则失败", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    CommunityButton(if (rulesNeedVerification) "完成网页验证" else "重试", {
                        if (rulesNeedVerification) { keyboard?.hide(); verification?.invoke(reloadRules) }
                        else reloadRules()
                    }, enabled = enabled && (!rulesNeedVerification || verification != null), compact = true)
                }
                Text(if (uploading) "正在上传图片…" else if (sending) { if (editing) "正在保存…" else "正在发布…" } else if (reply) {
                    if (replyRules == null) when {
                        rulesNeedVerification -> "等待网页验证"
                        rulesError != null -> "回帖字数规则读取失败"
                        else -> "正在读取回帖字数规则…"
                    }
                    else replyLength(draft.raw).let { length ->
                        if (length < replyRules.minimum) "$length / ${replyRules.minimum} 字" else "$length 字"
                    }
                } else "草稿已自动保存 · ${draft.raw.length} 字",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp).then(if (reply) Modifier.testTag("reply-length") else Modifier), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().then(if (reply) Modifier else Modifier.navigationBarsPadding()).padding(start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    EditorToolbar(body, ::changeBody, { imageSelection(body.selection); pickImage() }, enabled, uploading,
                        { bodyFocus.requestFocus() }, rtl, { rtl = !rtl }, Modifier.weight(1f),
                        preview = { keyboard?.hide(); preview = true })
                    if (reply) CommunityButton(publishText, publish, enabled = canPublish, compact = true)
                }
            }
        }
        if (reply) form(PaddingValues())
        else MaterialPageScaffold(header = header, modifier = Modifier.imePadding()
            .then(if (modalShowing) Modifier.clearAndSetSemantics {} else Modifier)
            .focusProperties { onEnter = { if (modalShowing) cancelFocusChange() } }.focusGroup(), content = form)
        if (!reply && !editing) CommunitySheet(categorySheet, "类别", { categorySheet.targetState = false },
            containerColor = MaterialTheme.colorScheme.background) {
            if (categories.isEmpty()) {
                Text(categoryError?.message ?: "分类暂未加载", style = MaterialTheme.typography.bodyMedium)
                CommunityButton("重新加载", reloadCategories)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)) {
                categories.forEachIndexed { index, category ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().selectable(draft.categoryId == category.id, role = Role.RadioButton, onClick = {
                        if (categorySheet.targetState && !sending) {
                            edit(draft.copy(categoryId = category.id, tags = if (draft.categoryId == category.id) draft.tags else emptyList()))
                            categorySheet.targetState = false
                        }
                    }).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(category.name, Modifier.weight(1f), color = if (draft.categoryId == category.id) LogoYellow else MaterialTheme.colorScheme.onSurface)
                        if (draft.categoryId == category.id) Icon(Icons.Rounded.Check, null, Modifier.size(22.dp), tint = LogoYellow)
                    }
                }
            }
        }
        if (!reply && !editing) EditorTags(tagSheet, draft.categoryId, draft.tags, tagSearch, sending) {
            edit(draft.copy(tags = it))
        }
    }
    if (preview) EditorPreviewDialog(draft.raw) { preview = false }
}

@Composable
private fun EditorDestination(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean, modifier: Modifier, click: () -> Unit) {
    Row(modifier.clickable(enabled = enabled, role = Role.Button, onClick = click).heightIn(min = 64.dp)
        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = LogoYellow.copy(alpha = if (enabled) 1f else .4f))
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text(value, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
    }
}
