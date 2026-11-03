package com.kudo.android.ui.web

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.kudo.android.data.GlassMode
import com.kudo.android.ui.components.*

internal enum class MiniAppPanel { MENU, ABOUT, CLIPBOARD }

internal class MiniAppUiState {
    val visibility = MutableTransitionState(false)
    var panel by mutableStateOf(MiniAppPanel.MENU)
    var info by mutableStateOf<MiniAppInfo?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf(false)
    var revision by mutableIntStateOf(0)
    var clipboard by mutableStateOf<MiniAppClipboardRequest?>(null)
    val showing get() = visibility.currentState || visibility.targetState
    fun open(panel: MiniAppPanel) { this.panel = panel; visibility.targetState = true }
    fun dismiss() { clipboard?.cancel(); clipboard = null; visibility.targetState = false }
    fun reset() { dismiss(); info = null; error = false; loading = false }
    fun request(request: MiniAppClipboardRequest) {
        if (visibility.targetState) request.cancel() else { clipboard = request; open(MiniAppPanel.CLIPBOARD) }
    }
}

@Composable
internal fun MiniAppTopBar(title: String, back: () -> Unit, more: () -> Unit, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding()
        .heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CommunityIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back)
        Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .testTag("mini-app-capsule"), verticalAlignment = Alignment.CenterVertically) {
            CommunityIconButton(Icons.Rounded.MoreHoriz, "小程序更多", more)
            Box(Modifier.width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))
            CommunityIconButton(Icons.Rounded.Close, "关闭网页", close)
        }
    }
}

@Composable
internal fun MiniAppPanels(state: MiniAppUiState, location: MiniAppLocation?, refresh: () -> Unit,
    copyLink: () -> Unit, browser: () -> Unit) {
    val context = LocalContext.current
    CommunitySheet(state.visibility, when (state.panel) {
        MiniAppPanel.MENU -> "小程序更多"
        MiniAppPanel.ABOUT -> "关于应用"
        MiniAppPanel.CLIPBOARD -> "剪贴板权限"
    }, state::dismiss, backdrop = null, glass = GlassMode.TRANSLUCENT,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        when (state.panel) {
            MiniAppPanel.MENU -> {
                MiniAppMenuRow(Icons.Rounded.Refresh, "刷新网页") { state.dismiss(); refresh() }
                MiniAppMenuRow(Icons.Rounded.Link, "复制链接") { state.dismiss(); copyLink() }
                MiniAppMenuRow(Icons.Rounded.Language, "在浏览器打开") { state.dismiss(); browser() }
                if (location?.id != null) MiniAppMenuRow(Icons.Rounded.Info, "关于") { state.open(MiniAppPanel.ABOUT) }
            }
            MiniAppPanel.ABOUT -> {
                if (state.loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CommunityLoadingIndicator() }
                state.info?.let { info ->
                    Row(Modifier.fillMaxWidth().testTag("mini-app-about"), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SubcomposeAsyncImage(info.iconUrl, null, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))
                            .semantics { contentDescription = "应用图标" },
                            loading = { Icon(Icons.Rounded.Apps, null, Modifier.padding(14.dp)) },
                            error = { Icon(Icons.Rounded.Apps, null, Modifier.padding(14.dp)) })
                        Text(info.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    }
                    if (info.description.isNotBlank()) Text(info.description, style = MaterialTheme.typography.bodyMedium)
                    if (info.version.isNotBlank()) MiniAppInfoRow("版本", info.version)
                    if (info.publisher.isNotBlank()) MiniAppInfoRow("发布人", info.publisher)
                }
                if (state.error) {
                    Text("应用信息加载失败", style = MaterialTheme.typography.bodyMedium)
                    CommunityButton("重试", { state.revision++ })
                }
                CommunityButton("关闭", state::dismiss, Modifier.fillMaxWidth())
            }
            MiniAppPanel.CLIPBOARD -> {
                val request = state.clipboard
                Text(if (request?.write == true) "允许小程序写入剪贴板？" else "允许小程序读取剪贴板文字？",
                    style = MaterialTheme.typography.titleMedium)
                request?.text?.takeIf { request.write && it.isNotBlank() }?.let {
                    Text(it.take(200), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CommunityButton("取消", state::dismiss, Modifier.weight(1f))
                    CommunityButton("仅本次允许", { request?.allow(context); state.dismiss() }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MiniAppMenuRow(icon: ImageVector, title: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = click)
        .heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MiniAppInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
