package com.kudo.android.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.LogoYellow
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun MiniAppActionContent(panel: MiniAppPanel, state: MiniAppActionsController, login: () -> Unit) {
    if (state.loading || state.busy) Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        CommunityLoadingIndicator()
    }
    if (!state.signedIn) {
        Text("请先登录后再操作", style = MaterialTheme.typography.bodyMedium)
        CommunityButton("登录", login, Modifier.fillMaxWidth())
        return
    }
    when (panel) {
        MiniAppPanel.RATING -> state.context?.takeIf { it.canRate }?.let { context ->
            val rating = context.rating
            if (rating.count == 0L) Text("暂无评分", style = MaterialTheme.typography.bodyMedium)
            else Text("${rating.average.toString().trimEnd('0').trimEnd('.')} / 5 · ${rating.count} 人评分", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                (1..5).forEach { score ->
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                        .selectable(rating.mine == score, enabled = state.canRate, role = Role.RadioButton, onClick = { state.rate(score) })
                        .semantics { contentDescription = "评为 $score 分，满分 5 分" }, contentAlignment = Alignment.Center) {
                        Icon(if (score <= (rating.mine ?: 0)) Icons.Rounded.Star else Icons.Rounded.StarBorder, null,
                            Modifier.size(30.dp), tint = if (score <= (rating.mine ?: 0)) LogoYellow else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        MiniAppPanel.TIP -> state.tips?.let { status ->
            var now by remember(status) { mutableStateOf(Instant.now()) }
            LaunchedEffect(status) {
                while (status.coolingDown()) {
                    delay((java.time.Duration.between(Instant.now(), status.nextTipAt).toMillis() + 50).coerceIn(50, 3_600_000))
                    now = Instant.now()
                }
            }
            Text("余额：${status.balance} 积分", style = MaterialTheme.typography.bodyMedium)
            status.amounts.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { amount ->
                        val enabled = !state.busy && !state.loading && status.allows(amount, now) && (!state.tipUncertain || state.amount == amount)
                        Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                            .semantics { contentDescription = "$amount 积分" }
                            .background(if (state.amount == amount) LogoYellow.copy(alpha = .22f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .selectable(state.amount == amount, enabled = enabled, role = Role.RadioButton, onClick = { state.chooseAmount(amount) }),
                            contentAlignment = Alignment.Center) {
                            Text("$amount", color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .35f),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (status.coolingDown(now)) Text("可再次打赏：${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(status.nextTipAt)}",
                style = MaterialTheme.typography.bodyMedium)
            if (state.tipUncertain) Text("上次结果尚未确认，重试会核对同一笔打赏。", style = MaterialTheme.typography.bodyMedium)
            CommunityButton(if (state.reconcilingTip) "核对上次打赏" else state.amount?.let { "确认打赏 $it 积分" } ?: "确认打赏",
                state::tip, Modifier.fillMaxWidth(), enabled = state.canTip)
        }
        MiniAppPanel.REPORT -> {
            state.flags?.let { flags ->
                if (flags.reasons.isEmpty()) Text("当前没有可用的举报原因", style = MaterialTheme.typography.bodyMedium)
                flags.reasons.forEach { reason ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(state.reason == reason, enabled = !state.busy && !state.reportLocked && state.notice == null,
                            role = Role.RadioButton, onClick = { state.chooseReason(reason) }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CommunityRadio(state.reason == reason, enabled = !state.busy && !state.reportLocked)
                        Text(reason.name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                state.reason?.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.reason?.takeIf { it.requireMessage }?.let { reason ->
                    CommunityTextField(state.message, state::message, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp).testTag("mini-app-report-message"),
                        placeholder = "举报说明", enabled = !state.busy && !state.reportLocked, minLines = 3)
                    Text("${state.message.trim().length}/${flags.maximum}，至少 ${flags.minimum} 字", style = MaterialTheme.typography.bodySmall)
                    if (reason.illegal) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CommunityCheckbox(state.confirmed, state::confirm, enabled = !state.busy && !state.reportLocked)
                        Text("我确认上述说明准确、完整。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                CommunityButton("提交举报", state::report, Modifier.fillMaxWidth(), enabled = state.canReport)
            }
        }
        else -> Unit
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    if (state.reportLocked) Text("上次举报结果尚未确认，请到网站核对后再操作。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (state.error != null && !state.loading && !state.busy && !state.reportLocked && (!state.tipUncertain || state.tips == null))
        CommunityButton("重试", state::retry, Modifier.fillMaxWidth(), compact = true)
}
