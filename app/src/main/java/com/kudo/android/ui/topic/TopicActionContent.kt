package com.kudo.android.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.BackHandler
import com.kudo.android.data.*
import com.kudo.android.ui.components.LoadingRow
import com.kudo.android.ui.components.LocalTopicPresentation
import com.kudo.android.ui.components.*

@Composable
internal fun TopicHistoryContent(state: TopicActionState, load: (Int?) -> Unit) {
    if (state.loading) LoadingRow()
    state.revision?.let { revision ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton({ load(revision.previous) }, enabled = !state.loading && revision.previous != null) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "上一处修订") }
            Text("修订 ${revision.current}", style = MaterialTheme.typography.titleMedium)
            IconButton({ load(revision.next) }, enabled = !state.loading && revision.next != null) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "下一处修订") }
        }
        Text("${revision.author} · ${revision.createdAt.replace('T', ' ').removeSuffix("Z")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (revision.reason.isNotBlank()) Text("编辑原因：${revision.reason}", style = MaterialTheme.typography.bodyMedium)
        Text("下划线为新增，删除线为移除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (revision.hidden) Text("部分修订已被隐藏，差异以网站允许查看的内容为准。", style = MaterialTheme.typography.bodySmall)
        if (revision.diffError) Text("此次修改较复杂，网站暂时无法生成差异。", color = MaterialTheme.colorScheme.error)
        val categories = LocalTopicPresentation.current.categories
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                revision.changes.forEach { change ->
                    Text(change.label, style = MaterialTheme.typography.titleSmall)
                    Text(buildAnnotatedString {
                        change.parts.forEach { part ->
                            val style = when (part.kind) {
                                RevisionKind.ADDED -> SpanStyle(color = Color(0xFF267344), background = Color(0xFFB5E8C7).copy(alpha = 0.5f), textDecoration = TextDecoration.Underline)
                                RevisionKind.REMOVED -> SpanStyle(color = MaterialTheme.colorScheme.error, background = MaterialTheme.colorScheme.error.copy(alpha = 0.1f), textDecoration = TextDecoration.LineThrough)
                                RevisionKind.NORMAL -> SpanStyle(color = MaterialTheme.colorScheme.onSurface)
                            }
                            val text = if (change.label == "分类") categories.firstOrNull { it.id == part.text.toIntOrNull() }?.name ?: part.text else part.text
                            withStyle(style) { append(text) }
                        }
                    }, style = MaterialTheme.typography.bodyMedium)
                }
                if (revision.changes.isEmpty() && !revision.diffError) Text("此次修订没有可显示的文本差异。", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun TopicReportContent(state: TopicActionState, choose: (FlagReason) -> Unit, message: (String) -> Unit, confirm: (Boolean) -> Unit, active: Boolean = true) {
    if (state.loading) LoadingRow()
    val config = state.flags ?: return
    val author = state.post?.author?.username.orEmpty()
    val enabled = active && !state.busy
    val contact = config.reasons.filter { it.contactAuthor }
    val flags = config.reasons.filterNot { it.contactAuthor }
    if (contact.isNotEmpty()) ReportSection("联系作者")
    contact.forEach { FlagChoice(it, author, state.selectedFlag == it, enabled, choose) }
    if (flags.isNotEmpty()) ReportSection("通知管理人员")
    flags.forEach { FlagChoice(it, author, state.selectedFlag == it, enabled, choose) }
    if (config.reasons.isEmpty()) Text("当前账号暂时不能举报此帖子，或已提交过举报。", style = MaterialTheme.typography.bodyMedium)
    state.selectedFlag?.takeIf { it.requireMessage }?.let { reason ->
        ReportSection(if (reason.contactAuthor) "发给作者的消息" else "举报说明")
        CommunityTextField(state.message, message, Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.background).padding(14.dp).testTag("report-message"), enabled = enabled,
            placeholder = "请补充具体说明…", minLines = 3)
        Text("${state.message.trim().length}/${config.maximum}，至少 ${config.minimum} 字", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = if (state.message.trim().length > config.maximum) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (reason.illegal) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CommunityCheckbox(state.confirmed, confirm, enabled = enabled)
            Text("我真诚地认为这些内容违法，且上述说明准确、完整。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FlagChoice(reason: FlagReason, username: String, selected: Boolean, enabled: Boolean, choose: (FlagReason) -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { choose(reason) })
        .heightIn(min = 68.dp).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CommunityRadio(selected, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(reason.name.replace("%{username}", username), style = MaterialTheme.typography.bodyLarge)
            if (reason.description.isNotBlank()) Text(reason.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(Modifier.padding(start = 60.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun TopicIgnoreContent(state: TopicActionState, viewer: Member?, duration: (IgnoreDuration) -> Unit) {
    Text("忽略 @${state.post?.author?.username.orEmpty()}", style = MaterialTheme.typography.titleMedium)
    Text("你将不会收到与此用户相关的任何通知，其所有内容也将被隐藏。这项设置与 Linux.do 账号同步。", style = MaterialTheme.typography.bodyMedium)
    if (viewer?.canIgnoreUsers != true || state.post?.author?.id == viewer.id) {
        Text("当前账号无法忽略此用户。", color = MaterialTheme.colorScheme.error)
        return
    }
    Text("忽略至", style = MaterialTheme.typography.titleSmall)
    IgnoreDuration.entries.forEach { option ->
        Row(Modifier.fillMaxWidth().selectable(state.duration == option, enabled = !state.busy, role = Role.RadioButton, onClick = { duration(option) })
            .heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            CommunityRadio(state.duration == option, enabled = !state.busy)
            Text(option.label, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReportSection(title: String) {
    Text(title, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun TopicReportPage(state: TopicActionState, dismiss: () -> Unit, choose: (FlagReason) -> Unit,
    message: (String) -> Unit, confirm: (Boolean) -> Unit, submit: () -> Unit, retry: () -> Unit, active: Boolean = true) {
    BackHandler { dismiss() }
    MaterialPageScaffold(modifier = Modifier.imePadding().testTag("topic-report-page"), header = { PageTopBar("举报", dismiss) }) { padding ->
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = padding.calculateTopPadding())) {
                TopicReportContent(state, choose, message, confirm, active)
                state.error?.let {
                    Text(it.message ?: "操作失败，请稍后重试", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                    if (state.flags == null && !state.busy) CommunityButton("重试", retry, Modifier.padding(horizontal = 20.dp))
                }
                if (state.writeLocked) Text("上次提交结果尚未确认，请在网站核对后再操作。", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                CommunityButton(if (state.busy) "提交中…" else if (state.selectedFlag?.contactAuthor == true) "发送消息" else "提交举报", submit,
                    Modifier.fillMaxWidth(), enabled = active && !state.loading && !state.busy && !state.writeLocked && state.flags?.valid(state.selectedFlag, state.message, state.confirmed) == true)
            }
        }
    }
}
