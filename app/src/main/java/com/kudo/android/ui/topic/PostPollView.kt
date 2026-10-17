package com.kudo.android.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.components.CommunityLoadingIndicator
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LogoYellow
import kotlin.math.roundToInt

@Composable
internal fun PostPollView(poll: PostPoll, saved: List<String>, owner: Boolean, locked: Boolean, busy: Boolean,
    loggedIn: Boolean, vote: (List<String>) -> Unit, login: () -> Unit, web: () -> Unit) {
    var selected by rememberSaveable(poll.name, saved) { mutableStateOf<List<String>>(saved.toList()) }
    val closed = poll.closed() || locked
    val resultsAllowed = poll.resultsVisible(saved.isNotEmpty(), owner)
    var results by rememberSaveable(poll.name, saved) { mutableStateOf(saved.isNotEmpty() || closed) }
    val showResults = resultsAllowed && (results || closed)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).padding(12.dp)
        .testTag("poll-${poll.name}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (poll.title.isNotBlank()) Text(plainText(poll.title), style = MaterialTheme.typography.titleSmall)
        poll.options.forEach { option ->
            val chosen = option.id in if (showResults) saved else selected
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = !closed && !busy && !showResults && poll.supported, role = Role.Button) {
                    if (!loggedIn) login()
                    else if (poll.type == "multiple") selected = if (chosen) selected - option.id else selected + option.id
                    else vote(listOf(option.id))
                }.semantics { this.selected = chosen }.testTag("poll-option-${option.id}")) {
                val fraction = if (poll.voters > 0) ((option.votes ?: 0).toFloat() / poll.voters).coerceIn(0f, 1f) else 0f
                if (showResults) Box(Modifier.matchParentSize()) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(LogoYellow.copy(alpha = 0.22f)))
                }
                Row(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (chosen) Icons.Rounded.CheckCircle else if (poll.type == "multiple") Icons.Rounded.CheckBoxOutlineBlank else Icons.Rounded.RadioButtonUnchecked,
                        if (chosen) "已选择" else null, Modifier.size(18.dp), if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    Text(plainText(option.html), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (showResults) Text("${(fraction * 100).roundToInt()}% · ${option.votes}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text("${poll.voters} 投票人" + if (poll.public) " · 公开投票" else "", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (closed) Text("投票已结束", style = MaterialTheme.typography.bodySmall)
        if (poll.type == "multiple" && !closed && !showResults) Text("请选择 ${poll.min} 至 ${poll.max} 项", style = MaterialTheme.typography.bodySmall)
        if (!poll.supported || poll.results == "staff_only") PollTextAction("在网页查看投票", !busy, web)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CommunityLoadingIndicator(Modifier.size(18.dp))
            if (resultsAllowed && !closed) PollTextAction(if (showResults) "返回投票" else "查看结果", !busy) { results = !results }
            if (poll.type == "multiple" && !closed && !showResults && poll.supported) {
                val valid = poll.validSelection(selected) && selected.toSet() != saved.toSet()
                Text(if (saved.isEmpty()) "投票" else "更新投票", Modifier.clip(RoundedCornerShape(50))
                    .background(if (valid) LogoYellow else MaterialTheme.colorScheme.surface)
                    .clickable(enabled = !busy && (valid || !loggedIn), role = Role.Button) { if (!loggedIn) login() else vote(selected.toList()) }
                    .padding(horizontal = 16.dp, vertical = 8.dp).testTag("poll-submit"),
                    color = if (valid) Ink else MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelLarge)
            }
            if (saved.isNotEmpty() && !closed && !showResults && poll.supported) PollTextAction("撤回投票", !busy) { vote(emptyList()) }
        }
        if (!resultsAllowed && poll.results == "on_close") Text("投票结束后显示结果", style = MaterialTheme.typography.bodySmall)
        if (!resultsAllowed && poll.results == "on_vote") Text("参与投票后显示结果", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PollTextAction(label: String, enabled: Boolean, click: () -> Unit) {
    Text(label, Modifier.clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = click).padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
}
