package com.kudo.android.ui.web

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
        MiniAppCapsule(more, close)
    }
}

@Composable
internal fun MiniAppCapsule(more: () -> Unit, close: () -> Unit, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val foreground = if (dark) Color.White.copy(alpha = .9f) else Color.Black.copy(alpha = .78f)
    val surface = (if (dark) Color(0xFF2C2C2E) else Color.White).copy(alpha = .86f)
    val outline = foreground.copy(alpha = if (dark) .1f else .08f)
    Box(modifier.width(97.dp).height(48.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(50))
            .background(surface)
            .border(.5.dp, outline, RoundedCornerShape(50))
            .testTag("mini-app-capsule"), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(.5.dp).height(16.dp).background(foreground.copy(alpha = .14f)))
            Spacer(Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniAppCapsuleButton(MiniAppIcons.More, foreground, "小程序更多", more)
            Spacer(Modifier.width(1.dp))
            MiniAppCapsuleButton(MiniAppIcons.Close, foreground, "关闭网页", close)
        }
    }
}

@Composable
private fun MiniAppCapsuleButton(icon: ImageVector, tint: Color, description: String, click: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(48.dp).clickable(interactionSource = interaction, indication = null,
        role = Role.Button, onClick = click), contentAlignment = Alignment.Center) {
        Box(Modifier.width(48.dp).height(32.dp).clip(RoundedCornerShape(50))
            .indication(interaction, ripple(color = tint)), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(20.dp), tint = tint)
        }
    }
}

@Composable
internal fun MiniAppPanels(state: MiniAppUiState, location: MiniAppLocation?, refresh: () -> Unit,
    copyLink: () -> Unit, browser: () -> Unit, titleBarEnabled: Boolean = true, toggleTitleBar: () -> Unit = {},
    preferencesReady: Boolean = true, rememberClipboard: (MiniAppClipboardRequest) -> Unit = {}) {
    val context = LocalContext.current
    CommunitySheet(state.visibility, when (state.panel) {
        MiniAppPanel.MENU -> "小程序更多"
        MiniAppPanel.ABOUT -> "关于应用"
        MiniAppPanel.CLIPBOARD -> "剪贴板权限"
    }, state::dismiss, backdrop = null, glass = GlassMode.TRANSLUCENT,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        when (state.panel) {
            MiniAppPanel.MENU -> Column(Modifier.fillMaxWidth().testTag("mini-app-menu-grid")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MiniAppMenuButton(Icons.Rounded.Refresh, if (location?.id != null) "重新进入小程序" else "刷新网页", Modifier.weight(1f)) { state.dismiss(); refresh() }
                    MiniAppMenuButton(Icons.Rounded.Link, "复制链接", Modifier.weight(1f)) { state.dismiss(); copyLink() }
                    MiniAppMenuButton(Icons.Rounded.Language, "在浏览器打开", Modifier.weight(1f)) { state.dismiss(); browser() }
                    if (location?.id != null) MiniAppMenuButton(Icons.Rounded.Info, "关于", Modifier.weight(1f)) { state.open(MiniAppPanel.ABOUT) }
                    else Spacer(Modifier.weight(1f))
                }
                if (location?.id != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MiniAppMenuButton(if (titleBarEnabled) Icons.Rounded.WebAssetOff else Icons.Rounded.WebAsset,
                        if (titleBarEnabled) "隐藏标题栏" else "显示标题栏", Modifier.weight(1f), enabled = preferencesReady) { state.dismiss(); toggleTitleBar() }
                    repeat(3) { Spacer(Modifier.weight(1f)) }
                }
            }
            MiniAppPanel.ABOUT -> {
                if (state.loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CommunityLoadingIndicator() }
                state.info?.let { MiniAppAboutContent(it) }
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
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommunityButton("不允许", state::dismiss, Modifier.fillMaxWidth().heightIn(min = 48.dp), compact = true)
                    CommunityButton("仅本次允许", { request?.allow(context); state.dismiss() }, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = preferencesReady, compact = true)
                    CommunityButton("允许且不再询问", { request?.let(rememberClipboard); state.dismiss() }, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = preferencesReady && request?.appId != null, compact = true)
                }
            }
        }
    }
}

@Composable
private fun MiniAppMenuButton(icon: ImageVector, title: String, modifier: Modifier = Modifier, enabled: Boolean = true, click: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = click)
        .padding(horizontal = 2.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 36.dp), contentAlignment = Alignment.TopCenter) {
            Text(title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
