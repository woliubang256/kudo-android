package com.kudo.android.ui.account

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kudo.android.R
import com.kudo.android.BuildConfig
import com.kudo.android.data.GitHubUpdates
import com.kudo.android.data.GitHubRelease
import com.kudo.android.data.KudoSourceUrl
import com.kudo.android.ui.components.PageTopBar
import com.kudo.android.ui.components.MaterialPageScaffold
import com.kudo.android.ui.components.liquid.LiquidModal
import com.kudo.android.ui.components.quickScrollTarget
import com.kudo.android.ui.components.liquid.LiquidModalAction
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

internal const val KudoAboutText = "酷DO是 LINUX DO 的第三方安卓原生客户端"

@Composable
fun AboutScreen(openLink: (String) -> Unit = {}, openExternal: (String) -> Unit = {},
    checkUpdate: (suspend () -> GitHubRelease)? = null, back: () -> Unit) {
    val updates = remember { GitHubUpdates() }
    val settings = LocalDisplaySettings.current
    val backdrop = rememberLayerBackdrop()
    val scroll = rememberLazyListState()
    val visibility = remember { MutableTransitionState(false) }
    var dialog by rememberSaveable { mutableStateOf("licenses") }
    val showing = visibility.currentState || visibility.targetState
    fun show(value: String) { dialog = value; visibility.targetState = true }
    fun dismiss() { visibility.targetState = false }
    Box(Modifier.fillMaxSize()) {
        MaterialPageScaffold(header = { PageTopBar("关于酷DO", back) }, modifier = Modifier.fillMaxSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background)
            .then(if (showing) Modifier.clearAndSetSemantics {} else Modifier)
            .focusProperties { onEnter = { if (showing) cancelFocusChange() } }.focusGroup()) { padding ->
            LazyColumn(Modifier.fillMaxSize().testTag("about-content").quickScrollTarget(scroll, enabled = !showing), state = scroll, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.Top) {
                        Brand(R.drawable.ic_kudo, "酷DO", Modifier.weight(1f))
                        Icon(Icons.Rounded.Favorite, "热爱", Modifier.padding(top = 18.dp).size(28.dp), tint = Color(0xFFFF5B66))
                        Brand(R.drawable.ic_linux_do, "LINUX DO", Modifier.weight(1f))
                    }
                }
                item {
                    Text(KudoAboutText, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface).padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)) {
                        SettingRow("检查更新", BuildConfig.VERSION_NAME, !showing) { show("update") }
                        SettingRow("查看源码", "GitHub", !showing) { openExternal(KudoSourceUrl) }
                        SettingRow("开源致谢", "", !showing) { show("licenses") }
                        SettingRow("社区指南", "", !showing) { openLink("https://linux.do/guidelines") }
                        SettingRow("隐私说明", "", !showing) { show("privacy") }
                    }
                }
                item { Text("真诚 · 友善 · 团结 · 专业", Modifier.testTag("about-slogan"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
            }
        }
        if (dialog == "update") UpdateSheet(visibility, checkUpdate ?: updates::latest, backdrop, openExternal, ::dismiss)
        else LiquidModal(visibility, if (dialog == "privacy") "隐私说明" else "开源致谢",
            false, backdrop, settings.glass, ::dismiss, sheetBackground = MaterialTheme.colorScheme.background,
            maxDialogHeight = if (dialog == "licenses") 560.dp else Dp.Infinity,
            maxDialogHeightFraction = if (dialog == "licenses") .7f else 1f,
            actions = { LiquidModalAction("知道了", ::dismiss, Modifier.fillMaxWidth(), primary = true) }) {
            if (dialog == "privacy") {
                Text("酷DO 直接连接 Linux.do。登录在官网页面完成；应用不收集密码，不接入统计或广告服务。Cookie 与草稿保存在应用私有目录，禁用系统备份，退出登录后清除。帖子中的外部图片会向其来源站点发起请求。DoH 默认关闭；开启后所选解析服务器可看到查询域名，应用不会向其发送社区登录凭据。",
                    style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.8f))
            } else {
                ModalLink("Backdrop 项目与许可") { openLink("https://github.com/Kyant0/AndroidLiquidGlass") }
                ModalLink("Font Awesome 图标与许可") { openLink("https://fontawesome.com/license/free") }
                ModalLink("APNG4Android 动图支持与许可") { openLink("https://github.com/penfeizhou/APNG4Android") }
                ModalLink("AndroidSVG 图片支持与许可") { openLink("https://github.com/BigBadaboom/androidsvg/blob/Release_1.4/LICENSE") }
                ModalLink("CommonMark Markdown 预览与许可") { openLink("https://github.com/commonmark/commonmark-java/blob/commonmark-parent-0.24.0/LICENSE.txt") }
                ModalLink("libavif 解码器与许可") { openLink("https://github.com/AOMediaCodec/libavif/blob/v1.1.1/LICENSE") }
                ModalLink("FluxDO 接口参考") { openLink("https://github.com/Lingyan000/fluxdo") }
                ModalLink("Rust DoH / ECH 组件与完整许可") { openLink("https://github.com/woliubang256/kudo-android/blob/main/app/src/main/assets/licenses/native-doh-NOTICES.txt") }
            }
        }
    }
}

@Composable
private fun Brand(logo: Int, name: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(logo), "$name Logo", Modifier.size(64.dp), tint = Color.Unspecified)
        Text(name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}
