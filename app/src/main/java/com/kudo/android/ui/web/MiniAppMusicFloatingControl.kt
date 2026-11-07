package com.kudo.android.ui.web

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.data.GlassMode
import com.kudo.android.ui.components.CommunityIconButton
import com.kudo.android.ui.components.CommunitySheet
import com.kyant.backdrop.Backdrop

@Composable
internal fun BoxScope.MiniAppMusicFloatingControl(owner: Int?, enabled: Boolean, backdrop: Backdrop?, glass: GlassMode) {
    val session = MiniAppMusic.current?.takeIf { enabled && it.key.accountId == owner && !it.hidden && it.state.canControl }
    val panel = remember { MutableTransitionState(false) }
    LaunchedEffect(session) { if (session == null) panel.targetState = false }
    if (session != null) {
        Box(Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .size(48.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "音乐控制") { panel.targetState = true }.testTag("mini-app-music-handle"), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.width(36.dp).height(48.dp).floatingSurface(backdrop, glass,
                RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, "音乐控制", Modifier.size(22.dp))
            }
        }
    }
    CommunitySheet(panel, "音乐控制", { panel.targetState = false }, backdrop = backdrop, glass = glass) {
        session?.let { controller ->
            val state = controller.state
            Row(Modifier.fillMaxWidth().testTag("mini-app-music-control"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.title.ifBlank { "小程序音乐" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.artist.isNotBlank()) Text(state.artist, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CommunityIconButton(Icons.Rounded.Close, "暂停并关闭音乐浮窗", { controller.hideAndPause(); panel.targetState = false }, enabled = !controller.busy)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (state.canSkip) CommunityIconButton(Icons.Rounded.SkipPrevious, "上一首", { controller.command("previous") }, enabled = !controller.busy)
                CommunityIconButton(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (state.playing) "暂停" else "继续播放", { controller.command(if (state.playing) "pause" else "play") }, enabled = !controller.busy)
                if (state.canSkip) CommunityIconButton(Icons.Rounded.SkipNext, "下一首", { controller.command("next") }, enabled = !controller.busy)
            }
            controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
