package com.kudo.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.kudo.android.data.Topic

internal class TopicCardAnchor {
    var coordinates: LayoutCoordinates? = null
    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.let {
        Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat())).takeUnless(Rect::isEmpty)
    }
}

internal data class TopicCardSnapshot(
    val key: String, val bounds: Rect, val radius: Dp, val surface: Color, val image: ImageBitmap,
    val preview: Topic? = null,
) {
    var opened = false
}

@Composable
internal fun TopicCardContainer(
    snapshot: TopicCardSnapshot?, progress: State<Float>, ready: State<Boolean>,
    closing: () -> Boolean, sourceBounds: () -> Rect?, back: () -> Unit, content: @Composable () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val statusBarTop = WindowInsets.statusBars.getTop(LocalDensity.current)
    val handoff by animateFloatAsState(if (ready.value) 1f else 0f, tween(180), label = "topic-card-handoff")
    val viewport = remember { TopicCardAnchor() }
    val path = remember { Path() }
    val interactive = snapshot == null || (progress.value >= 1f && handoff >= 1f && !closing())
    val missingSource = { snapshot != null && closing() && sourceBounds() == null }
    val liveOpacity = {
        val amount = progress.value
        if (snapshot == null || missingSource()) 1f else if (closing()) ((amount - .35f) / .65f).coerceIn(0f, 1f)
        else handoff * ((amount - .65f) / .35f).coerceIn(0f, 1f)
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { viewport.coordinates = it }
        .graphicsLayer { alpha = if (missingSource()) progress.value else 1f }.drawWithContent {
        val window = viewport.bounds()
        if (snapshot == null || window == null || missingSource() ||
            (progress.value >= 1f && ready.value && handoff >= 1f && !closing())) {
            drawContent()
        } else {
            val origin = sourceBounds() ?: snapshot.bounds
            val bounds = lerp(origin, window, progress.value).translate(-window.topLeft)
            val radius = snapshot.radius.toPx() * (1f - progress.value)
            path.rewind()
            path.addRoundRect(RoundRect(bounds, CornerRadius(radius)))
            clipPath(path) {
                drawRect(lerp(snapshot.surface, background, progress.value), bounds.topLeft, bounds.size)
                val scale = bounds.width / snapshot.image.width
                val inset = (statusBarTop - window.top).coerceAtLeast(0f) * progress.value
                drawImage(snapshot.image, dstOffset = IntOffset(bounds.left.roundToInt(), (bounds.top + inset).roundToInt()),
                    dstSize = IntSize(bounds.width.roundToInt().coerceAtLeast(1),
                        (snapshot.image.height * scale).roundToInt().coerceAtLeast(1)))
                withTransform({
                    translate(bounds.left, bounds.top)
                    scale(bounds.width / size.width, bounds.width / size.width, Offset.Zero)
                }) { this@drawWithContent.drawContent() }
            }
        }
    }.testTag("topic-transition-detail")) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = liveOpacity() }
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})
            .background(background)) { content() }
        if (!interactive) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            })
        }
        if (snapshot != null && !ready.value && progress.value >= 1f && !closing()) {
            CommunityLoadingIndicator(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
            IconButton(back, Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
            }
        }
    }
}
