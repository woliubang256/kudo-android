package com.kudo.android.ui.topic

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TabletMac
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import com.kudo.android.data.GlassMode
import com.kudo.android.data.Post
import com.kudo.android.ui.editor.EditorMentionOverlay
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.roundToInt

@Composable
internal fun PostDeviceIndicator(post: Post, enabled: Boolean) {
    val device = post.device ?: return
    var expanded by remember(post.id, device) { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    BackHandler(expanded) { expanded = false }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.size(48.dp).testTag("post-device-${post.id}").onGloballyPositioned {
                val bounds = it.boundsInWindow()
                anchor = IntRect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
            }) {
            Icon(if (device.type == "tablet") Icons.Rounded.TabletMac else Icons.Rounded.Smartphone,
                "查看第 ${post.number} 楼的设备", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            val density = LocalDensity.current
            val margin = with(density) { 8.dp.roundToPx() }
            val safeTop = WindowInsets.statusBars.getTop(density) + margin
            val safeBottom = WindowInsets.navigationBars.getBottom(density) + margin
            val window = LocalWindowInfo.current.containerSize
            val width = with(density) { (window.width - margin * 2).coerceAtLeast(1).toDp().coerceAtMost(320.dp) }
            val position = remember(margin, safeTop, safeBottom) { object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                    popupContentSize: IntSize): IntOffset {
                    val x = (anchorBounds.center.x - popupContentSize.width / 2)
                        .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                    val above = anchorBounds.top - popupContentSize.height - margin
                    val y = if (above >= safeTop) above else anchorBounds.bottom + margin
                    return IntOffset(x, y.coerceIn(safeTop, (windowSize.height - popupContentSize.height - safeBottom).coerceAtLeast(safeTop)))
                }
            } }
            EditorMentionOverlay(position, anchor, { expanded = false }) { backdrop ->
                val mode = LocalDisplaySettings.current.glass
                val shape = RoundedCornerShape(10.dp)
                val surface = MaterialTheme.colorScheme.surface
                val light = surface.luminance() > .5f
                val canBlur = backdrop != null && mode != GlassMode.TRANSLUCENT && Build.VERSION.SDK_INT >= 31
                val canRefract = canBlur && mode == GlassMode.LIQUID && Build.VERSION.SDK_INT >= 33
                Surface(Modifier.widthIn(max = width).testTag("post-device-tooltip-${post.id}").shadow(3.dp, shape, clip = false)
                    .then(if (backdrop != null) Modifier.drawPlainBackdrop(backdrop, shape = { shape }, effects = {
                        if (canBlur) { vibrancy(); blur(16.dp.toPx()) }
                        if (canRefract) lens(8.dp.toPx(), 16.dp.toPx())
                    }, onDrawSurface = { drawRect(surface.copy(alpha = if (canBlur) if (light) .72f else .64f else .94f)) }) else Modifier),
                    shape = shape, color = if (backdrop != null) Color.Transparent else surface) {
                    Text("${if (post.number == 1) "发布自" else "回复自"} ${device.name}",
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
