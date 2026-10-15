package com.kudo.android.ui.topic

import android.graphics.drawable.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kudo.android.ui.components.CommunityLoadingIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay

@Composable
internal fun GalleryImagePage(
    image: ArticleBlock.Image, page: Int, transform: GalleryTransformState,
    enabled: Boolean, active: Boolean, close: () -> Unit, information: () -> Unit,
    placeholder: Painter? = null, onPainter: (Painter) -> Unit = {}, onPainterDisposed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var animation by remember(image.originalUrl) { mutableStateOf<Animatable?>(null) }
    var failed by remember(image.originalUrl) { mutableStateOf(false) }
    var loaded by remember(image.originalUrl) { mutableStateOf(false) }
    var loadingProgress by remember(image.originalUrl) { mutableFloatStateOf(0f) }
    var retry by remember(image.originalUrl) { mutableIntStateOf(0) }
    var settling by remember { mutableStateOf<Job?>(null) }
    var flinging by remember { mutableStateOf<Job?>(null) }
    var touching by remember { mutableStateOf(false) }
    var flingActive by remember { mutableStateOf(false) }
    val displayedProgress by animateFloatAsState(loadingProgress, label = "gallery image loading progress")
    LaunchedEffect(image.originalUrl, retry, loaded, failed) {
        if (!loaded && !failed) {
            loadingProgress = 0.08f
            while (loadingProgress < 0.92f) {
                delay(180)
                loadingProgress = (loadingProgress + 0.035f).coerceAtMost(0.92f)
            }
        }
    }
    val decay = rememberSplineBasedDecay<Float>()
    DisposableEffect(transform, enabled, active) { onDispose { flinging?.cancel(); settling?.cancel(); touching = false } }
    DisposableEffect(image.originalUrl) { onDispose { animation?.stop(); onPainterDisposed() } }
    val currentClose by rememberUpdatedState(close)
    val currentInformation by rememberUpdatedState(information)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentActive by rememberUpdatedState(active)
    val flingVelocity = with(LocalDensity.current) { 1_000.dp.toPx() }
    val viewport = transform.viewport
    val request = remember(image.originalUrl, viewport, retry) {
        ImageRequest.Builder(context).data(image.originalUrl).apply {
            if (!viewport.isEmpty()) {
                val sampling = min(2f, min(4096f / maxOf(viewport.width, viewport.height),
                    sqrt(8_000_000f / (viewport.width * viewport.height))))
                size((viewport.width * sampling).roundToInt().coerceAtLeast(1), (viewport.height * sampling).roundToInt().coerceAtLeast(1))
            }
        }.build()
    }
    LifecycleResumeEffect(animation, active) {
        val drawable = animation
        if (active) { if (drawable?.isRunning == false) drawable.start() } else drawable?.stop()
        onPauseOrDispose { drawable?.stop() }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { transform.resize(Size(it.width.toFloat(), it.height.toFloat())) }
        .testTag("gallery-page-$page")
        .pointerInput(transform, enabled) {
            if (enabled) detectTapGestures(onTap = { currentClose() }, onLongPress = { currentInformation() })
        }
        .galleryGestures(transform, enabled, flingVelocity,
            start = { touching = true; flinging?.cancel(); settling?.cancel(); transform.dismissOffset = androidx.compose.ui.geometry.Offset.Zero },
            finish = { dismiss ->
                if (currentEnabled) {
                    if (dismiss) currentClose()
                    else if (galleryMotionEnabled(context)) settling = scope.launch { transform.restoreDismiss() }
                    else transform.dismissOffset = androidx.compose.ui.geometry.Offset.Zero
                }
            }, panFinished = { velocity ->
                if (currentEnabled && currentActive && galleryMotionEnabled(context)) {
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        try { transform.fling(velocity, decay) }
                        finally { if (flinging === currentCoroutineContext()[Job]) flingActive = false }
                    }
                    flinging = job
                    flingActive = true
                    job.start()
                }
            }, gestureFinished = { touching = false })
        .semantics {
            contentDescription = "第 ${page + 1} 张图片"
            stateDescription = transform.zoomDescription
            onClick("关闭图片") { if (enabled) currentClose(); enabled }
            onLongClick("图片操作") { if (enabled) currentInformation(); enabled }
            customActions = listOf(
                CustomAccessibilityAction("放大图片") { if (enabled) transform.accessibleZoom(); enabled },
                CustomAccessibilityAction("还原图片") { if (enabled) transform.resetZoom(); enabled },
            )
        }) {
        key(retry) { AsyncImage(request, null, Modifier.fillMaxSize()
            .then(if (touching || flingActive) Modifier.preferredFrameRate(FrameRateCategory.High) else Modifier).graphicsLayer {
            scaleX = transform.scale * transform.dismissScale
            scaleY = scaleX
            translationX = transform.offset.x + transform.dismissOffset.x
            translationY = transform.offset.y + transform.dismissOffset.y
        }.testTag("gallery-photo-$page"), contentScale = ContentScale.Fit, placeholder = placeholder,
            onLoading = { failed = false; loaded = false; loadingProgress = 0.08f },
            onError = { failed = true; loaded = false; loadingProgress = 0f },
            onSuccess = { result ->
                loaded = true
                failed = false
                loadingProgress = 1f
                val decoded = result.result.image
                if (decoded.width > 0 && decoded.height > 0) transform.decodedRatio(decoded.width.toFloat() / decoded.height)
                onPainter(result.painter)
                val drawable = decoded.asDrawable(resources) as? Animatable
                animation = drawable
                if (!active || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) drawable?.stop()
            })
        }
        if (!loaded && !failed) {
            Box(Modifier.align(Alignment.Center).size(72.dp).testTag("gallery-loading-progress"), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { displayedProgress }, modifier = Modifier.fillMaxSize(), color = Color.White, trackColor = Color.White.copy(alpha = 0.24f))
                Text("${(displayedProgress * 100).roundToInt()}%", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (failed) Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("图片加载失败", color = Color.White)
            TextButton(onClick = { retry++ }, enabled = enabled) { Text("重试", color = Color.White) }
        }
    }
}
