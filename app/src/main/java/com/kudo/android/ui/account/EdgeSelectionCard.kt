package com.kudo.android.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kudo.android.data.DohConfiguration
import com.kudo.android.network.DohNetworkState
import com.kudo.android.ui.components.CommunityButton
import com.kudo.android.ui.components.CommunitySwitch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun EdgeSelectionCard(configuration: DohConfiguration, state: DohNetworkState,
    update: (DohConfiguration) -> Unit, reselect: () -> Unit, selecting: Boolean,
    feedback: String?, enabled: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("本地优选", style = MaterialTheme.typography.bodyLarge)
                Text("自动寻找可用节点，失败时使用所选 DoH", Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CommunitySwitch(configuration.localSelection, { update(configuration.copy(localSelection = it)) },
                Modifier.testTag("edge-enabled"), enabled = enabled)
        }
        if (configuration.localSelection) {
            Text(if (state.effective) state.edge.summary else state.summary, Modifier.testTag("edge-status"),
                style = MaterialTheme.typography.bodyMedium)
            state.edge.ip?.let { ip ->
                Text("$ip · ${state.edge.latencyMs ?: 0} ms", Modifier.testTag("edge-node"), style = MaterialTheme.typography.bodySmall)
            }
            state.edge.checkedAt?.let { seconds ->
                Text("上次验证：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(seconds * 1000))}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CommunityButton(if (selecting || state.edge.busy) "正在优选…" else "重新优选", reselect,
                Modifier.fillMaxWidth().testTag("edge-reselect"), enabled = enabled && state.effective && !selecting && !state.edge.busy)
            if (feedback != null && feedback != state.edge.summary) Text(feedback, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("补充社区节点", style = MaterialTheme.typography.bodyMedium)
                    Text("从 atlas 获取公开候选表", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CommunitySwitch(configuration.atlas, { update(configuration.copy(atlas = it)) },
                    Modifier.testTag("edge-atlas"), enabled = enabled)
            }
            Text("通过公共加密 DNS 查询其他站点并检测候选。服务方可见来源 IP；不发送账号凭据，不上传测速结果。网页登录仍使用系统网络。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
