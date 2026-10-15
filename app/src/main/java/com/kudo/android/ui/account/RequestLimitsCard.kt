package com.kudo.android.ui.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.kudo.android.network.RequestLimits
import com.kudo.android.ui.components.CommunityIconButton
import com.kudo.android.ui.components.liquid.LiquidSlider
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LogoYellow
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

private val presets = listOf(
    Triple("慢速", "放缓请求节奏，适合弱网环境", RequestLimits.Gentle),
    Triple("均衡", "日常推荐", RequestLimits.Balanced),
    Triple("极速", "网络稳定时加载更快", RequestLimits.Turbo),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RequestLimitsCard(value: RequestLimits, update: (RequestLimits) -> Unit, reset: () -> Unit,
    enabled: Boolean = true) {
    val sliderBackdrop = rememberLayerBackdrop()
    val matched = presets.indexOfFirst { it.third == value }
    var customRequested by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(matched < 0) }
    LaunchedEffect(value) { customRequested = false; if (matched < 0) expanded = true }
    val selected = if (customRequested || matched < 0) 3 else matched
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp))) {
        // Sliders must sample a sibling surface, never an ancestor recording their own lenses.
        Box(Modifier.matchParentSize().layerBackdrop(sliderBackdrop).background(MaterialTheme.colorScheme.surface))
        Column {
            Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("并发与限流", style = MaterialTheme.typography.titleMedium)
                    Text("控制同时发出的请求数，减少触发服务器限流", Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CommunityIconButton(Icons.Rounded.Restore, "恢复默认请求设置", reset, enabled = enabled && value != RequestLimits.Balanced)
            }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (presets.map { it.first } + "自定义").forEachIndexed { index, label ->
                        val checked = selected == index
                        Row(Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (checked) LogoYellow else MaterialTheme.colorScheme.onSurface.copy(alpha = .055f))
                            .selectable(checked, enabled = enabled, role = Role.RadioButton) {
                                if (index == 3) { customRequested = true; expanded = true }
                                else { customRequested = false; update(presets[index].third) }
                            }.testTag("request-preset-$index").padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (checked) Icon(Icons.Rounded.Check, null, Modifier.padding(end = 6.dp).size(17.dp), tint = Ink)
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (checked) Ink else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Text(if (selected == 3) "手动调节下方参数" else presets[selected].second, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("每 ${value.windowSeconds} 秒内最多 ${value.perWindow} 个请求，同时进行 ${value.concurrent} 个",
                    Modifier.testTag("request-summary"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { expanded = !expanded }
                .testTag("request-advanced").padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("高级参数", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "收起高级参数" else "展开高级参数")
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp)) {
                    RequestLimitSlider(Icons.Rounded.SyncAlt, "同时请求数", "同一来源同时进行的请求上限", value.concurrent, 1..10,
                        "request-concurrent", sliderBackdrop, enabled = enabled) { update(value.copy(concurrent = it)) }
                    RequestLimitSlider(Icons.Rounded.Speed, "限流阈值", "每个时间窗口内最多发出的请求数", value.perWindow, 2..30,
                        "request-per-window", sliderBackdrop, enabled = enabled) { update(value.copy(perWindow = it)) }
                    RequestLimitSlider(Icons.Rounded.Timer, "时间窗口", "限流统计的时间长度", value.windowSeconds, 1..10,
                        "request-window", sliderBackdrop, suffix = "秒", enabled = enabled) { update(value.copy(windowSeconds = it)) }
                }
            }
        }
    }
}

@Composable
private fun RequestLimitSlider(icon: ImageVector, title: String, description: String, value: Int, range: IntRange,
    tag: String, backdrop: Backdrop, suffix: String = "", enabled: Boolean, update: (Int) -> Unit) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 3.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("$value$suffix", color = LogoYellow, style = MaterialTheme.typography.labelLarge)
            }
            Text(description, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val valueRange = range.first.toFloat()..range.last.toFloat()
            LiquidSlider({ value.toFloat() }, { val next = it.roundToInt().coerceIn(range); if (next != value) update(next) }, valueRange,
                backdrop, Modifier.fillMaxWidth().testTag(tag).semantics {
                    contentDescription = title
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), valueRange, range.last - range.first - 1)
                    if (!enabled) disabled()
                    setProgress { if (enabled && it.isFinite()) { update(it.roundToInt().coerceIn(range)); true } else false }
                }, enabled)
        }
    }
}
