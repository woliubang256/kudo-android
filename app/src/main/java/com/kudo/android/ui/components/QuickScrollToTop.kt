package com.kudo.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kudo.android.ui.components.liquid.LiquidButton
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kudo.android.ui.topic.LocalReadingBlocked
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalQuickScrollBottomPadding = staticCompositionLocalOf { 12.dp }
private val QuickScrollButtonSize = 32.dp
private val ScrollTopIcon = ImageVector.Builder("ScrollTop", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 6f); lineTo(5f, 6f); close()
        moveTo(5f, 14f); lineTo(7f, 16f); lineTo(12f, 11f)
        lineTo(17f, 16f); lineTo(19f, 14f); lineTo(12f, 7f); close()
    }
}.build()
private val LocalQuickScrollTargets = staticCompositionLocalOf<MutableList<QuickScrollTarget>?> { null }
private val LocalQuickScrollAccessories = staticCompositionLocalOf<MutableList<QuickScrollAccessory>?> { null }

private class QuickScrollTarget {
    var bounds by mutableStateOf(Rect.Zero)
    var enabled by mutableStateOf(false)
    var scrolled by mutableStateOf(false)
    var scrollingUp by mutableStateOf(false)
    var bottomPadding by mutableStateOf(12.dp)
    var scrollToTop: suspend () -> Unit = {}
}

private class QuickScrollAccessory {
    var bounds by mutableStateOf(Rect.Zero)
    var enabled by mutableStateOf(false)
    var icon by mutableStateOf<ImageVector?>(null)
    var description by mutableStateOf("")
    var onClick by mutableStateOf<() -> Unit>({})
}

@Composable
internal fun QuickScrollToTopHost(content: @Composable () -> Unit) {
    val targets = remember { mutableStateListOf<QuickScrollTarget>() }
    val accessories = remember { mutableStateListOf<QuickScrollAccessory>() }
    val backdrop = rememberLayerBackdrop()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val settings = LocalDisplaySettings.current
    val ime = WindowInsets.ime.getBottom(density)
    val navigation = WindowInsets.navigationBars.getBottom(density)
    val target = targets.filter { it.enabled && it.bounds.width > 0 && it.bounds.height > 0 }
        .maxByOrNull { it.bounds.width * it.bounds.height }
    val accessory = accessories.filter { it.enabled && it.bounds.width > 0 && it.bounds.height > 0 && it.icon != null }
        .maxByOrNull { it.bounds.width * it.bounds.height }
    Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInWindow() }) {
        CompositionLocalProvider(LocalQuickScrollTargets provides targets, LocalQuickScrollAccessories provides accessories) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { content() }
        }
        if (target != null) key(target) {
            val scope = rememberCoroutineScope()
            var scrolling by remember { mutableStateOf(false) }
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 1.08f else 1f)
            val size = with(density) { 48.dp.toPx() }
            val touchInset = with(density) { ((48.dp - QuickScrollButtonSize) / 2).toPx() }
            val bottom = minOf(target.bounds.bottom - with(density) { target.bottomPadding.toPx() },
                bounds.bottom - navigation - with(density) { 12.dp.toPx() })
            val x = target.bounds.center.x - bounds.left - size / 2
            val y = bottom - bounds.top - size + touchInset
            val showTop = settings.showScrollToTop && target.scrolled && target.scrollingUp && !scrolling && ime == 0 && y >= 0
            val showAccessory = accessory != null && ime == 0 && y >= 0
            if (showTop || showAccessory) {
                val topX = target.bounds.center.x - bounds.left - size / 2
                val accessoryX = target.bounds.left - bounds.left + with(density) { 16.dp.toPx() }
                Box(Modifier.fillMaxSize()) {
                    AnimatedVisibility(showAccessory, enter = fadeIn(), exit = fadeOut(),
                        modifier = Modifier.offset { IntOffset(accessoryX.roundToInt(), y.roundToInt()) }) {
                        Box(Modifier.size(48.dp).testTag("quick-scroll-accessory").clickable(
                            enabled = !scrolling, interactionSource = null, indication = null,
                            onClick = { accessory?.onClick?.invoke() }), contentAlignment = Alignment.Center) {
                            LiquidButton(onClick = null, isInteractive = false,
                                backdrop = backdrop, mode = settings.glass, enabled = !scrolling,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .3f),
                                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(QuickScrollButtonSize)
                                    .testTag("quick-scroll-accessory-surface")) {
                                Icon(accessory?.icon ?: ScrollTopIcon, accessory?.description, Modifier.size(18.dp))
                            }
                        }
                    }
                    AnimatedVisibility(showTop, enter = fadeIn(), exit = fadeOut(),
                        modifier = Modifier.offset { IntOffset(topX.roundToInt(), y.roundToInt()) }) {
                        Box(Modifier.size(48.dp).testTag("quick-scroll-to-top").clickable(
                            enabled = !scrolling, interactionSource = null, indication = null, onClick = {
                            if (!scrolling) scope.launch {
                                scrolling = true
                                try { target.scrollToTop() } finally { scrolling = false }
                            }
                        }), contentAlignment = Alignment.Center) {
                            LiquidButton(onClick = null, isInteractive = false,
                                backdrop = backdrop, mode = settings.glass, enabled = !scrolling,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .3f),
                                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(QuickScrollButtonSize)
                                    .graphicsLayer { scaleX = scale; scaleY = scale }.testTag("quick-scroll-to-top-surface")) {
                                Icon(ScrollTopIcon, "杩斿洖椤堕儴", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Modifier.quickScrollTarget(scrolled: Boolean, scrollingUp: Boolean, enabled: Boolean = true,
    bottomPadding: Dp = LocalQuickScrollBottomPadding.current, scrollToTop: suspend () -> Unit): Modifier {
    val targets = LocalQuickScrollTargets.current ?: return this
    val target = remember(targets) { QuickScrollTarget() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val blocked = LocalReadingBlocked.current
    SideEffect {
        target.enabled = enabled && !blocked && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
        target.scrolled = scrolled
        target.scrollingUp = scrollingUp
        target.bottomPadding = bottomPadding
        target.scrollToTop = scrollToTop
    }
    DisposableEffect(targets, target) {
        targets += target
        onDispose { targets -= target }
    }
    return onGloballyPositioned { target.bounds = it.boundsInWindow() }
}

@Composable
internal fun Modifier.quickScrollAccessory(enabled: Boolean, icon: ImageVector, description: String, onClick: () -> Unit): Modifier {
    val accessories = LocalQuickScrollAccessories.current ?: return this
    val accessory = remember(accessories) { QuickScrollAccessory() }
    SideEffect {
        accessory.enabled = enabled
        accessory.icon = icon
        accessory.description = description
        accessory.onClick = onClick
    }
    DisposableEffect(accessories, accessory) {
        accessories += accessory
        onDispose { accessories -= accessory }
    }
    return onGloballyPositioned { accessory.bounds = it.boundsInWindow() }
}

private data class ScrollTopRow(val index: Int, val key: Any, val size: Int)
private data class ScrollTopMetrics(val width: Int, val height: Int, val index: Int, val offset: Int,
    val spacing: Int, val backwards: Boolean, val rows: List<ScrollTopRow>)

/** Retain measured prefix heights so a long first post is not estimated from later short replies. */
private class ScrollTopDistance {
    private var width = 0
    private val measured = mutableMapOf<Int, ScrollTopRow>()

    fun exceedsViewport(metrics: ScrollTopMetrics): Boolean = with(metrics) {
        if (width != this@ScrollTopDistance.width) {
            measured.clear()
            this@ScrollTopDistance.width = width
        }
        measured.keys.removeAll { it >= height }
        rows.filter { it.index in 0 until height && it.size > 0 }.forEach { row ->
            if (measured[row.index]?.key?.let { it != row.key } == true) {
                measured.keys.removeAll { it >= row.index }
            }
            measured[row.index] = row
        }
        if (!backwards || height <= 0 || rows.isEmpty()) return false
        if (index >= height) return true
        val prefix = measured.values.filter { it.index < index }
        val average = rows.map { it.size }.average()
        offset.toDouble() + index.toDouble() * spacing + prefix.sumOf { it.size.toDouble() } +
            (index - prefix.size) * average >= height
    }
}

@Composable
private fun rememberScrollThreshold(state: Any, metrics: () -> ScrollTopMetrics): Boolean {
    var scrolled by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        val distance = ScrollTopDistance()
        snapshotFlow(metrics).collect { scrolled = distance.exceedsViewport(it) }
    }
    return scrolled
}

@Composable
internal fun rememberQuickScrollPastViewport(state: LazyListState): Boolean = rememberScrollThreshold(state) {
    val info = state.layoutInfo
    ScrollTopMetrics(info.viewportSize.width, info.viewportSize.height, state.firstVisibleItemIndex,
        state.firstVisibleItemScrollOffset, info.mainAxisItemSpacing, state.canScrollBackward,
        info.visibleItemsInfo.map { ScrollTopRow(it.index, it.key, it.size) })
}

@Composable
private fun rememberQuickScrollPastViewport(state: LazyGridState): Boolean = rememberScrollThreshold(state) {
    val info = state.layoutInfo
    val items = info.visibleItemsInfo.filter { it.row >= 0 }
    val firstRow = items.firstOrNull { it.index == state.firstVisibleItemIndex }?.row
        ?: items.minOfOrNull { it.row } ?: 0
    ScrollTopMetrics(info.viewportSize.width, info.viewportSize.height, firstRow,
        state.firstVisibleItemScrollOffset, info.mainAxisItemSpacing, state.canScrollBackward,
        items.groupBy { it.row }.map { (row, cells) ->
            ScrollTopRow(row, cells.minBy { it.index }.key, cells.maxOf { it.size.height })
        })
}

@Composable
internal fun Modifier.quickScrollTarget(state: LazyListState, enabled: Boolean = true,
    bottomPadding: Dp = LocalQuickScrollBottomPadding.current, expandHeader: suspend () -> Unit = {}): Modifier {
    val scrolled = rememberQuickScrollPastViewport(state)
    return quickScrollTarget(scrolled, state.lastScrolledBackward, enabled, bottomPadding) {
        coroutineScope { launch { state.animateScrollToItem(0) }; launch { expandHeader() } }
    }
}

@Composable
internal fun Modifier.quickScrollTarget(state: LazyGridState, enabled: Boolean = true): Modifier {
    val scrolled = rememberQuickScrollPastViewport(state)
    return quickScrollTarget(scrolled, state.lastScrolledBackward, enabled) { state.animateScrollToItem(0) }
}

@Composable
internal fun Modifier.quickScrollTarget(state: ScrollState, enabled: Boolean = true): Modifier {
    val scrolled by remember(state) { derivedStateOf { state.viewportSize > 0 && state.value >= state.viewportSize } }
    return quickScrollTarget(scrolled, state.lastScrolledBackward, enabled) { state.animateScrollTo(0) }
}
