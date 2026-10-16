package com.kudo.android.ui.account

import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.components.liquid.LiquidModal
import com.kudo.android.ui.components.liquid.LiquidModalAction
import com.kudo.android.ui.theme.LogoYellow
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable
fun SettingsScreen(settings: DisplaySettings, update: (DisplaySettings) -> Unit, user: Member?, logout: () -> Unit, back: () -> Unit, openLink: (String) -> Unit, manageIgnored: () -> Unit = {},
    networkSummary: String = "已关闭", manageNetwork: () -> Unit = {}) {
    val context = LocalContext.current
    val verification = LocalWebVerification.current
    var notificationsEnabled by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        notificationsEnabled = context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    }
    var dialog by rememberSaveable { mutableStateOf("theme") }
    val visibility = remember { MutableTransitionState(false) }
    val backdrop = rememberLayerBackdrop()
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val showing = visibility.currentState || visibility.targetState
    fun show(target: String) { dialog = target; visibility.targetState = true }
    fun dismiss() { visibility.targetState = false }

    Box(Modifier.fillMaxSize()) {
        MaterialPageScaffold(header = { PageTopBar("设置", back) }, modifier = Modifier.fillMaxSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background)
            .then(if (showing) Modifier.clearAndSetSemantics {} else Modifier)
            .focusProperties { onEnter = { if (showing) cancelFocusChange() } }.focusGroup()) { padding ->
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().quickScrollTarget(scroll, enabled = !showing), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = padding.calculateTopPadding() + 6.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { SectionLabel("界面显示") }
                item {
                    Column(Modifier.clip(RoundedCornerShape(19.dp)).background(MaterialTheme.colorScheme.surface)) {
                        SettingRow("主题风格", settings.theme.label, !showing) { show("theme") }
                        SettingsDivider()
                        SettingRow("界面材质效果", settings.glass.label, !showing) { show("glass") }
                        SettingsDivider()
                        SettingRow("字体大小", if (settings.fontScale == 1f) "跟随系统" else "${(settings.fontScale * 100).toInt()}%", !showing) { show("font") }
                        SettingsDivider()
                        SettingRow("主题列表样式", settings.topicListStyle.label, !showing) { show("list") }
                        SettingsDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("置顶贴精简显示", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            CommunitySwitch(settings.compactPinnedTopics, { update(settings.copy(compactPinnedTopics = it)) }, enabled = !showing,
                                modifier = Modifier.testTag("compact-pinned-topics"))
                        }
                        SettingsDivider()
                        SettingRow("底栏中间按钮", settings.navigationAction.label, !showing) { show("navigation") }
                        SettingsDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("显示用户签名", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            CommunitySwitch(settings.showSignatures, { update(settings.copy(showSignatures = it)) }, enabled = !showing,
                                modifier = Modifier.testTag("show-signatures"))
                        }
                        SettingsDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("显示快速回顶按钮", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            CommunitySwitch(settings.showScrollToTop, { update(settings.copy(showScrollToTop = it)) }, enabled = !showing,
                                modifier = Modifier.testTag("show-scroll-to-top"))
                        }
                    }
                }
                item { Text(if (Build.VERSION.SDK_INT >= 33) "液态玻璃会随背景内容呈现模糊、折射和高光。可切换半透明以减少视觉效果。" else "此系统使用兼容材质显示；完整液态玻璃需要 Android 13 或更高版本。", Modifier.padding(horizontal = 9.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                if (user != null) item { SectionLabel("内容偏好") }
                if (user != null) item {
                    Column(Modifier.clip(RoundedCornerShape(19.dp)).background(MaterialTheme.colorScheme.surface)) {
                        SettingRow("已忽略用户", "${user.ignoredUsers.size} 人 · 与社区账号同步", !showing, manageIgnored)
                    }
                }
                item { SectionLabel("消息通知") }
                item {
                    Column(Modifier.clip(RoundedCornerShape(19.dp)).background(MaterialTheme.colorScheme.surface)) {
                        SettingRow("消息通知", if (notificationsEnabled) "已开启 · 运行期间接收" else "未开启 · 点击管理", !showing) {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }
                    }
                }
                item { SectionLabel("网络") }
                item {
                    Column(Modifier.clip(RoundedCornerShape(19.dp)).background(MaterialTheme.colorScheme.surface)) {
                        SettingRow("网络管理", networkSummary, !showing, manageNetwork)
                        SettingsDivider()
                        SettingRow("手动过盾", "打开网页完成验证", !showing && verification != null) {
                            verification?.invoke {}
                        }
                    }
                }
                item { SectionLabel("链接打开") }
                item {
                    Column(Modifier.clip(RoundedCornerShape(19.dp)).background(MaterialTheme.colorScheme.surface)) {
                        SettingRow("站内链接", "在系统中允许酷DO打开支持的链接", !showing) {
                            val action = if (Build.VERSION.SDK_INT >= 31) Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            context.startActivity(Intent(action, Uri.parse("package:${context.packageName}")))
                        }
                        SettingsDivider()
                        SettingRow("外部链接打开方式", settings.externalLinkMode.label, !showing) { show("external-links") }
                    }
                }
                item { Text("外部链接不包含 linux.do 及其子域名。系统打开会使用浏览器或关联应用。", Modifier.padding(horizontal = 9.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                if (user != null) item {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                        .clickable(enabled = !showing) { show("logout") }.heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        val actionSheet = dialog in listOf("theme", "glass", "font", "list", "navigation", "external-links")
        val title = when (dialog) {
            "theme" -> "主题风格"; "glass" -> "界面材质效果"; "font" -> "字体大小"
            "list" -> "主题列表样式"; "navigation" -> "底栏中间按钮"
            "external-links" -> "外部链接打开方式"
            "logout" -> "退出登录？"; else -> "酷DO"
        }
        LiquidModal(visibility, title, actionSheet, backdrop, settings.glass, ::dismiss,
            sheetBackground = MaterialTheme.colorScheme.background,
            actions = {
                if (!actionSheet && dialog == "logout") {
                    LiquidModalAction("取消", ::dismiss, Modifier.weight(1f))
                    LiquidModalAction("退出", { if (visibility.targetState) { dismiss(); logout() } }, Modifier.weight(1f), primary = true)
                } else if (!actionSheet) LiquidModalAction("知道了", ::dismiss, Modifier.fillMaxWidth(), primary = true)
            }) {
            if (actionSheet) {
                val scales = listOf(0.85f, 1f, 1.15f, 1.3f)
                val options = when (dialog) {
                    "theme" -> ThemeMode.entries.map { it.label }
                    "glass" -> GlassMode.entries.map { it.label }
                    "list" -> TopicListStyle.entries.map { it.label }
                    "navigation" -> NavigationAction.entries.map { it.label }
                    "external-links" -> ExternalLinkMode.entries.map { it.label }
                    else -> listOf("较小 · 85%", "跟随系统", "较大 · 115%", "大号 · 130%")
                }
                val selected = when (dialog) {
                    "theme" -> settings.theme.ordinal; "glass" -> settings.glass.ordinal; "list" -> settings.topicListStyle.ordinal; "navigation" -> settings.navigationAction.ordinal; "external-links" -> settings.externalLinkMode.ordinal; else -> scales.indexOf(settings.fontScale)
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)) {
                    options.forEachIndexed { index, label ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth()
                            .selectable(selected = selected == index, role = Role.RadioButton, onClick = {
                                if (!visibility.targetState) return@selectable
                                update(when (dialog) {
                                    "theme" -> settings.copy(theme = ThemeMode.entries[index])
                                    "glass" -> settings.copy(glass = GlassMode.entries[index])
                                    "list" -> settings.copy(topicListStyle = TopicListStyle.entries[index])
                                    "navigation" -> settings.copy(navigationAction = NavigationAction.entries[index])
                                    "external-links" -> settings.copy(externalLinkMode = ExternalLinkMode.entries[index])
                                    else -> settings.copy(fontScale = scales[index])
                                })
                                dismiss()
                            }).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp).testTag("modal-choice-$index"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (selected == index) LogoYellow else MaterialTheme.colorScheme.onSurface)
                            if (selected == index) Icon(Icons.Rounded.Check, null, Modifier.padding(start = 12.dp).size(22.dp), tint = LogoYellow)
                        }
                    }
                }
            } else {
                Text(when (dialog) {
                    "logout" -> "本机登录状态和编辑草稿将被清除，社区内容与收藏不会删除。"
                    else -> KudoAboutText
                }, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
internal fun SettingRow(title: String, value: String, enabled: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action).heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, Modifier.weight(1.1f).padding(start = 12.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        Icon(Icons.Rounded.ChevronRight, null, Modifier.padding(start = 5.dp).size(18.dp), MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
}

@Composable
internal fun ModalLink(label: String, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = open)
        .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
