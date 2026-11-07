package com.kudo.android.ui.web

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.kudo.android.data.GlassMode
import com.kudo.android.ui.components.CommunityIconButton

@Composable
internal fun MiniAppMusicFloatingControl(controller: MiniAppMusicController, expanded: Boolean,
    expand: () -> Unit, dismiss: () -> Unit, backdrop: MiniAppFloatingBackdrop, glass: GlassMode,
    safe: IntRect, handleSize: Int, availableWidth: Dp, inset: Int) {
    MiniAppFloatingPopup(MiniAppFloatingSide.LEFT, .46f, safe, handleSize, expanded, dismiss,
        inset = if (expanded) inset else 0) {
        if (expanded) {
            val state = controller.state
            Column(Modifier.width(availableWidth.coerceAtMost(328.dp))
                .floatingSurface(backdrop, glass, RoundedCornerShape(20.dp)).testTag("mini-app-music-control")
                .semantics { paneTitle = "音乐控制"; isTraversalGroup = true }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MusicNote, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Text(state.title.ifBlank { "小程序音乐" }, Modifier.weight(1f).padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CommunityIconButton(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playing) "暂停" else "继续播放", { controller.command(if (state.playing) "pause" else "play") },
                        enabled = !controller.busy)
                    CommunityIconButton(Icons.Rounded.Close, "暂停并关闭音乐浮窗", { controller.hideAndPause() }, enabled = !controller.busy)
                }
                controller.error?.let { Text(it, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        } else Box(Modifier.size(48.dp).clickable(interactionSource = null, indication = null,
            role = Role.Button, onClickLabel = "音乐控制", onClick = expand).testTag("mini-app-music-handle"),
            contentAlignment = Alignment.CenterStart) {
            Box(Modifier.width(28.dp).height(48.dp).floatingSurface(backdrop, glass,
                RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)).testTag("mini-app-music-handle-surface"),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, "音乐控制", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
