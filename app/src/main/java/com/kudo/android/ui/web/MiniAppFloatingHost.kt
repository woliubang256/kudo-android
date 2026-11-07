package com.kudo.android.ui.web

import android.os.Build
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.kudo.android.data.GlassMode
import com.kudo.android.ui.components.CommunityIconButton
import com.kudo.android.ui.components.CommunitySheet
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import kotlin.math.roundToInt

/** The sampling layer contains the page only; handles and previews are siblings. */
@Composable
internal fun MiniAppFloatingHost(owner: Int?, enabled: Boolean, ordinary: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val backdrop = rememberLayerBackdrop()
    val glass = if (ordinary) GlassMode.TRANSLUCENT else LocalDisplaySettings.current.glass
    val source = backdrop.takeUnless { ordinary }
    val entries = if (enabled) MiniAppTasks.floating.filter { it.request.key.accountId == owner } else emptyList()
    val preview = remember { MutableTransitionState(false) }
    var fraction by rememberSaveable(owner) { mutableFloatStateOf(.46f) }
    LaunchedEffect(entries.isEmpty()) { if (entries.isEmpty()) preview.targetState = false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (ordinary) Modifier else Modifier.layerBackdrop(backdrop))) { content() }
        if (entries.isNotEmpty()) {
            val density = LocalDensity.current
            val safe = WindowInsets.safeDrawing.asPaddingValues()
            val top = safe.calculateTopPadding() + 60.dp
            val range = with(density) { (maxHeight - top - safe.calculateBottomPadding() - 120.dp).coerceAtLeast(0.dp).toPx() }
            Box(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, with(density) { top.toPx() }.roundToInt() + (range * fraction).roundToInt()) }
                .padding(end = safe.calculateRightPadding(LayoutDirection.Ltr)).size(48.dp)
                .draggable(rememberDraggableState { delta -> if (range > 0) fraction = (fraction + delta / range).coerceIn(0f, 1f) }, Orientation.Vertical)
                .clickable(onClickLabel = "小程序浮窗") { preview.targetState = true }.testTag("mini-app-floating-handle"), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.width(32.dp).height(48.dp).floatingSurface(source, glass, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MoreVert, "小程序浮窗", Modifier.size(22.dp))
                }
            }
        }
        CommunitySheet(preview, "小程序浮窗", { preview.targetState = false }, backdrop = source, glass = glass,
            scrollContent = false, contentPadding = PaddingValues(bottom = 16.dp)) {
            LazyRow(Modifier.fillMaxWidth().testTag("mini-app-floating-previews"), contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(entries, key = { it.request.key.identity }) { entry ->
                    Column(Modifier.width(192.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(onClickLabel = "恢复小程序") { preview.targetState = false; MiniAppTasks.restore(context, entry) }) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(entry.info?.iconUrl, null, Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Crop)
                            Text(entry.info?.name ?: "小程序", Modifier.weight(1f).padding(start = 8.dp), maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            CommunityIconButton(Icons.Rounded.Close, "关闭浮窗", { MiniAppTasks.close(context, entry) })
                        }
                        val image = entry.preview
                        if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxWidth().height(240.dp), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                        else Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            AsyncImage(entry.info?.iconUrl, null, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Modifier.floatingSurface(backdrop: Backdrop?, glass: GlassMode, shape: RoundedCornerShape): Modifier {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    val canBlur = backdrop != null && glass != GlassMode.TRANSLUCENT && Build.VERSION.SDK_INT >= 31
    return clip(shape).then(if (backdrop == null) Modifier.background(color.copy(alpha = .94f)) else Modifier.drawBackdrop(
        backdrop, shape = { shape }, effects = {
            if (canBlur) blur(20.dp.toPx())
            if (glass == GlassMode.LIQUID && Build.VERSION.SDK_INT >= 33) lens(12.dp.toPx(), 24.dp.toPx(), depthEffect = true)
        }, onDrawSurface = { drawRect(color.copy(alpha = if (canBlur) .64f else .94f)) }))
}
