package com.kudo.android.ui.web

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import com.kudo.android.data.GlassMode
import com.kudo.android.ui.theme.LocalDisplaySettings

@Composable
internal fun MiniAppFloatingHost(owner: Int?, enabled: Boolean,
    pageKey: Any? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val glass = LocalDisplaySettings.current.glass
    val entries = if (enabled && foreground) MiniAppTasks.floating.filter { it.request.key.accountId == owner } else emptyList()
    val music = MiniAppMusic.current?.takeIf { enabled && foreground && it.key.accountId == owner && !it.hidden && it.state.canControl }
    val backdrop = rememberMiniAppFloatingBackdrop(entries.isNotEmpty() || music != null, glass)
    var panel by remember(owner) { mutableStateOf<MiniAppFloatingPanel?>(null) }
    var fraction by rememberSaveable(owner) { mutableFloatStateOf(.46f) }
    LaunchedEffect(pageKey, foreground) { panel = null }
    LaunchedEffect(entries.isEmpty()) { if (entries.isEmpty() && panel == MiniAppFloatingPanel.PREVIEWS) panel = null }
    LaunchedEffect(music) { if (music == null && panel == MiniAppFloatingPanel.MUSIC) panel = null }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        content()
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val insets = WindowInsets.safeDrawing.union(WindowInsets.ime)
        val safe = IntRect(insets.getLeft(density, direction), insets.getTop(density), insets.getRight(density, direction), insets.getBottom(density))
        val handleSize = with(density) { 48.dp.roundToPx() }
        val availableWidth = (maxWidth - with(density) { (safe.left + safe.right).toDp() } - 16.dp).coerceAtLeast(48.dp)
        val cardHeight = (maxHeight - with(density) { (safe.top + safe.bottom).toDp() } - 16.dp).coerceIn(48.dp, 168.dp)
        if (entries.isNotEmpty()) {
            val expanded = panel == MiniAppFloatingPanel.PREVIEWS
            MiniAppFloatingPopup(MiniAppFloatingSide.RIGHT, fraction, safe, handleSize, expanded, { panel = null },
                inset = if (expanded) with(density) { 8.dp.roundToPx() } else 0) {
                if (expanded) {
                    val cardWidth = availableWidth.coerceAtMost(112.dp)
                    val rowWidth = (cardWidth * entries.size + 8.dp * (entries.size - 1)).coerceAtMost(availableWidth)
                    LazyRow(Modifier.width(rowWidth).height(cardHeight).testTag("mini-app-floating-previews")
                        .semantics { paneTitle = "小程序浮窗"; isTraversalGroup = true }, reverseLayout = true,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(entries, key = { it.request.key.identity }) { entry ->
                            MiniAppFloatingPreview(entry, Modifier.width(cardWidth).fillMaxHeight(), backdrop, glass,
                                restore = { panel = null; MiniAppTasks.restore(context, entry) },
                                close = { MiniAppTasks.close(context, entry) })
                        }
                    }
                } else Box(Modifier.size(48.dp).draggable(rememberDraggableState { delta ->
                    val range = with(density) { maxHeight.toPx() } - safe.top - safe.bottom - handleSize * 3
                    if (range > 0) fraction = (fraction + delta / range).coerceIn(0f, 1f)
                }, Orientation.Vertical).clickable(interactionSource = null, indication = null, role = Role.Button,
                    onClickLabel = "展开小程序浮窗") { panel = MiniAppFloatingPanel.PREVIEWS }
                    .testTag("mini-app-floating-handle"), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.width(18.dp).height(48.dp).floatingSurface(backdrop, glass,
                        RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)).testTag("mini-app-floating-handle-surface"),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.MoreVert, "小程序浮窗", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        music?.let { MiniAppMusicFloatingControl(it, panel == MiniAppFloatingPanel.MUSIC,
            { panel = MiniAppFloatingPanel.MUSIC }, { panel = null }, backdrop, glass, safe, handleSize,
            availableWidth, with(density) { 8.dp.roundToPx() }) }
    }
}

@Composable
private fun MiniAppFloatingPreview(entry: MiniAppFloatingEntry, modifier: Modifier, backdrop: MiniAppFloatingBackdrop,
    glass: GlassMode, restore: () -> Unit, close: () -> Unit) {
    Box(modifier.floatingSurface(backdrop, glass, RoundedCornerShape(14.dp)).testTag("mini-app-preview-${entry.request.key.appId}")
        .clickable(role = Role.Button, onClickLabel = "恢复小程序", onClick = restore)
        .semantics { contentDescription = entry.info?.name ?: "小程序" }) {
        entry.preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter) }
        if (entry.preview == null) AsyncImage(entry.info?.iconUrl, null,
            Modifier.align(Alignment.Center).size(48.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        Box(Modifier.align(Alignment.TopStart).size(48.dp).clickable(interactionSource = null, indication = null,
            role = Role.Button, onClick = close), contentAlignment = Alignment.Center) {
            Box(Modifier.size(24.dp).background(Color.Black.copy(alpha = .42f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "关闭浮窗", Modifier.size(18.dp), tint = Color.White)
            }
        }
        AsyncImage(entry.info?.iconUrl, null, Modifier.align(Alignment.BottomEnd).padding(8.dp).size(24.dp)
            .clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
    }
}
