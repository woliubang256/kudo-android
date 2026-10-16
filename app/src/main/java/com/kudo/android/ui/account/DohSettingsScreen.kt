package com.kudo.android.ui.account

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kudo.android.data.*
import com.kudo.android.network.DohNetwork
import com.kudo.android.ui.components.*
import com.kudo.android.ui.components.liquid.LiquidModalAction
import com.kudo.android.ui.components.liquid.LiquidModal
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kudo.android.network.RequestLimits
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DohSettingsScreen(network: DohNetwork, requests: RequestSettings, back: () -> Unit) {
    val configuration by network.settings.configuration.collectAsStateWithLifecycle()
    val state by network.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val panel = remember { MutableTransitionState(false) }
    val reset = remember { MutableTransitionState(false) }
    val backdrop = rememberLayerBackdrop()
    val limits by requests.configuration.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf("actions") }
    var serverId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var bootstrap by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf<DohServer?>(null) }
    val results = remember { mutableStateMapOf<DohServer, String>() }
    val server = configuration.servers.firstOrNull { it.id == serverId }
    val showing = panel.currentState || panel.targetState || reset.currentState || reset.targetState
    fun dismiss() { panel.targetState = false }
    fun edit(value: DohServer?) {
        serverId = value?.id; name = value?.name.orEmpty(); url = value?.url.orEmpty(); bootstrap = value?.bootstrap?.joinToString(", ").orEmpty()
        error = null; mode = if (value?.builtIn == true) "view" else "edit"; panel.targetState = true
    }
    fun check(value: DohServer) {
        if (checking != null) return
        checking = value; results.remove(value); dismiss()
        scope.launch {
            try { results[value] = network.check(value) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { results[value] = "检测失败，请检查服务器或网络" }
            finally { checking = null }
        }
    }
    Box(Modifier.fillMaxSize()) {
        MaterialPageScaffold(header = { PageTopBar("网络管理", back) }, modifier = Modifier.fillMaxSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background)
            .then(if (showing) Modifier.clearAndSetSemantics {} else Modifier)
            .focusProperties { onEnter = { if (showing) cancelFocusChange() } }.focusGroup()) { padding ->
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("network-settings-list").quickScrollTarget(scroll, enabled = !showing),
                contentPadding = PaddingValues(14.dp, padding.calculateTopPadding() + 6.dp, 14.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(MaterialTheme.colorScheme.surface)
                        .padding(start = 18.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("DoH + ECH", style = MaterialTheme.typography.bodyLarge)
                            Text(state.summary, Modifier.padding(top = 4.dp).testTag("doh-status"), style = MaterialTheme.typography.bodySmall,
                                color = if (state.error != null && state.effective) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        CommunitySwitch(configuration.enabled, { network.settings.update(configuration.copy(enabled = it)); network.refresh() },
                            Modifier.testTag("doh-enabled"), enabled = !showing)
                    }
                }
                item { Text("加密解析并启用 ECH，用于原生页面与图片。网页登录与外链仍使用系统网络。",
                    Modifier.padding(horizontal = 9.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(MaterialTheme.colorScheme.surface)
                        .padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("VPN 自动切换", style = MaterialTheme.typography.bodyLarge)
                            Text("检测到 VPN 或系统代理时自动暂停 DoH，断开后恢复",
                                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        CommunitySwitch(configuration.vpnAutoSwitch, {
                            network.settings.update(configuration.copy(vpnAutoSwitch = it)); network.refresh()
                        }, Modifier.testTag("doh-vpn-auto-switch"), enabled = !showing)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionLabel("服务器") }
                        CommunityIconButton(Icons.Rounded.Add, "添加服务器", { edit(null) }, Modifier.testTag("doh-add"), enabled = !showing)
                    }
                }
                items(configuration.servers, key = { it.id }) { value ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().selectable(value.id == configuration.selected.id, enabled = !showing, role = Role.RadioButton,
                            onClick = { network.settings.update(configuration.select(value.id)); network.refresh() })
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CommunityRadio(value.id == configuration.selected.id)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(value.name + if (value.builtIn) " · 内置" else "", style = MaterialTheme.typography.bodyLarge)
                                Text(value.url, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            CommunityIconButton(Icons.Rounded.MoreHoriz, "管理 ${value.name}", {
                                serverId = value.id; mode = "actions"; panel.targetState = true
                            }, enabled = !showing)
                        }
                        if (checking == value) Row(Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CommunityLoadingIndicator(Modifier.size(16.dp))
                            Text("正在检测 DNS、ECH 与站点…", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        results[value]?.let { Text(it, Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                item { Text("自定义服务器需支持标准 DoH 和 HTTPS 记录；仅 DNS 查询成功不代表 ECH 可用。服务器可看到查询域名，登录凭据不会发送给解析服务器。",
                    Modifier.padding(horizontal = 9.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { SectionLabel("高级") }
                item { RequestLimitsCard(limits, requests::update, { reset.targetState = true }, enabled = !showing) }
                item { Text("接口与图片共用同一来源的请求额度。实时推送和文件下载独立控制；服务器返回限流时仍需等待。",
                    Modifier.padding(horizontal = 9.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        CommunitySheet(panel, when (mode) { "edit" -> if (serverId == null) "添加服务器" else "编辑服务器"; "view" -> "服务器信息"; "delete" -> "删除服务器？"; else -> "服务器管理" },
            ::dismiss, showTitle = mode != "actions", showActions = mode != "actions", actions = {
                if (mode == "view") LiquidModalAction("完成", ::dismiss, Modifier.fillMaxWidth(), primary = true)
                else {
                    LiquidModalAction("取消", ::dismiss, Modifier.weight(1f))
                    LiquidModalAction(if (mode == "delete") "删除" else "保存", {
                        if (!panel.targetState) return@LiquidModalAction
                        if (mode == "delete") {
                            server?.let { network.settings.update(configuration.removeServer(it.id)) }; network.refresh(); dismiss()
                        } else try {
                            val value = if (serverId == null) validateDohServer(name, url, bootstrap)
                                else validateDohServer(name, url, bootstrap, serverId!!)
                            network.settings.update(configuration.saveServer(value)); network.refresh(); dismiss()
                        } catch (e: IllegalArgumentException) { error = e.message }
                    }, Modifier.weight(1f), primary = true)
                }
            }) {
            when (mode) {
                "actions" -> server?.let { value ->
                    Text(value.name, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium)
                    DohAction(Icons.Rounded.NetworkCheck, "检测连接", checking == null) { check(value) }
                    DohAction(if (value.builtIn) Icons.Rounded.Info else Icons.Rounded.Edit, if (value.builtIn) "查看" else "编辑") { edit(value) }
                    if (!value.builtIn) DohAction(Icons.Rounded.DeleteOutline, "删除") { mode = "delete" }
                }
                "delete" -> Text("删除“${server?.name.orEmpty()}”？正在使用此服务器时会切回内置服务器。", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text("名称", style = MaterialTheme.typography.labelLarge)
                    CommunityTextField(name, { name = it; error = null }, Modifier.fillMaxWidth().testTag("doh-name"), "服务器名称", singleLine = true, enabled = mode != "view")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("DoH 地址", style = MaterialTheme.typography.labelLarge)
                    CommunityTextField(url, { url = it; error = null }, Modifier.fillMaxWidth().testTag("doh-url"), "https://…/dns-query", singleLine = true,
                        enabled = mode != "view", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("引导 IP · 可选", style = MaterialTheme.typography.labelLarge)
                    CommunityTextField(bootstrap, { bootstrap = it; error = null }, Modifier.fillMaxWidth().testTag("doh-bootstrap"), "多个 IPv4 / IPv6 用逗号分隔", maxLines = 3, enabled = mode != "view")
                    Text("留空时使用系统 DNS 解析服务器自身；站点地址仍通过 DoH 解析。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        LiquidModal(reset, "恢复默认请求设置？", false, backdrop, LocalDisplaySettings.current.glass, { reset.targetState = false }, actions = {
            LiquidModalAction("取消", { reset.targetState = false }, Modifier.weight(1f))
            LiquidModalAction("恢复默认", {
                if (reset.targetState) { requests.update(RequestLimits.Balanced); reset.targetState = false }
            }, Modifier.weight(1f), primary = true)
        }) { Text("恢复为均衡模式：每 3 秒内最多 6 个请求，同时进行 3 个。", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun DohAction(icon: ImageVector, text: String, enabled: Boolean = true, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = action)
        .heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .4f))
    }
}
