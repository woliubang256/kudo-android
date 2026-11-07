package com.kudo.android.ui.topic

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kudo.android.ui.components.CommunityLoadingIndicator
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

@Composable
internal fun ImageScreen(gallery: ImageGalleryState, session: GallerySession, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    // Pager may measure between an album update and the next recomposition. All of
    // its deferred callbacks must use the same album as the captured transforms.
    val images = session.images
    val pager = key(images) { rememberPagerState(initialPage = session.index) { images.size } }
    val transforms = session.transforms
    val transform = transforms[pager.currentPage]
    val painters = remember(images) { mutableStateMapOf<Int, Painter>() }
    val currentTransform by rememberUpdatedState(transform)
    val context = LocalContext.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var entering by remember { mutableStateOf(!session.restored) }
    var flight by remember { mutableStateOf<GalleryFlight?>(null) }
    val progress = remember { Animatable(0f) }
    val window = rememberGalleryWindow()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var actionTarget by remember { mutableStateOf<ImageActionTarget?>(null) }
    var exporting by remember { mutableStateOf(false) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            if (session.images === images) session.select(page)
        }
    }
    LaunchedEffect(Unit) { focusManager.clearFocus(force = true); keyboard?.hide(); focus.requestFocus() }
    LaunchedEffect(session) {
        if (session.restored) return@LaunchedEffect
        try {
            snapshotFlow { currentTransform.viewport }.first { !it.isEmpty() }
            val source = gallery.openingSnapshot
            val painter = source?.painter
            val intrinsic = painter?.intrinsicSize
            if (intrinsic != null && intrinsic.width.isFinite() && intrinsic.height.isFinite() && intrinsic.width > 0 && intrinsic.height > 0) {
                currentTransform.decodedRatio(intrinsic.width / intrinsic.height)
            }
            val target = currentTransform.imageFrame()
            flight = GalleryFlight(source?.imageFrame(origin, currentTransform.ratio) ?: target, target,
                painter, 0f, 1f, closing = false)
            gallery.hiddenAnchor = gallery.openingAnchor
            if (galleryMotionEnabled(context)) progress.animateTo(1f, tween(280, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
            else progress.snapTo(1f)
        } finally {
            entering = false
            flight = null
            gallery.hiddenAnchor = null
        }
    }
    fun close() {
        val leaving = transform.imageFrame()
        val scrim = transform.scrimAlpha
        val pagePainter = painters[pager.currentPage]
        if (!session.closing) scope.launch {
            gallery.dismiss(if (session.images === images) pager.currentPage else session.index, prepare = {
                snapshotFlow { entering }.first { !it }
                pager.stopScroll()
                window?.restoreAndAwaitLayout()
            }) { anchor ->
                val destination = anchor?.snapshot()?.imageFrame(origin, transform.ratio)
                progress.snapTo(0f)
                flight = GalleryFlight(leaving, destination ?: leaving, pagePainter?.takeIf { destination != null }, scrim, 0f, closing = true)
                gallery.hiddenAnchor = anchor
                if (galleryMotionEnabled(context)) progress.animateTo(1f, tween(240, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
                else progress.snapTo(1f)
            }
        }
    }
    BackHandler { close() }
    val moving = flight
    val scrim = moving?.let { lerp(it.fromScrim, it.toScrim, progress.value) } ?: if (entering) 0f else transform.scrimAlpha
    val pageAlpha = if (moving?.painter != null || (entering && moving == null)) 0f
        else if (moving != null) (if (moving.closing) 1f - progress.value else progress.value) else 1f
    val interactive = !entering && !session.closing && moving == null && actionTarget == null
    val backgroundTap by rememberUpdatedState { if (interactive) close() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }
        .background(Color.Black.copy(alpha = scrim)).testTag("image-viewer")
        .pointerInput(Unit) { detectTapGestures(onTap = { backgroundTap() }) }
        .semantics { paneTitle = "图片查看器" }
        .then(if (actionTarget != null) Modifier.clearAndSetSemantics {} else Modifier)
        .focusRequester(focus).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyUp) false else when (event.key) {
                Key.Escape -> { close(); true }
                Key.DirectionLeft -> { if (interactive) scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }; true }
                Key.DirectionRight -> { if (interactive) scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(images.lastIndex)) }; true }
                else -> false
            }
        }.focusable()) {
        HorizontalPager(pager, Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha }.testTag("image-pager"),
            userScrollEnabled = interactive && !transform.zoomed && !transform.dragging,
            key = { "${it}:${images[it].originalUrl}" }) { page ->
            GalleryImagePage(images[page], page, transforms[page], interactive,
                active = page == pager.currentPage, close = ::close, information = {
                    if (!exporting) actionTarget = ImageActionTarget(images[pager.currentPage], images.toList(), session.sourceAt(pager.currentPage))
                },
                placeholder = gallery.openingSnapshot?.painter?.takeIf { sameGalleryImage(images[page], session.openingImage) },
                onPainter = { painters[page] = it }, onPainterDisposed = { painters.remove(page) })
        }
        if (moving != null) GalleryTransitionLayer(moving, progress.value)
        Column(Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = if (interactive) transform.scrimAlpha else 0f }
            .navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (session.loading) CommunityLoadingIndicator(Modifier.size(18.dp))
            if (session.error) TextButton(onClick = session::retryAlbum) { Text("其他图片加载失败，点击重试", color = Color.White) }
            if (images.size > 1) Text("${pager.currentPage + 1} / ${images.size}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
            if (exporting) Text("正在处理图片…", color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
    ImageActions(actionTarget, { actionTarget = null }, { exporting = it })
}

private tailrec fun Context.galleryActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.galleryActivity()
    else -> null
}

private class GalleryWindow(val view: View, val controller: WindowInsetsControllerCompat) {
    private val insets = ViewCompat.getRootWindowInsets(view)
    private val statusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) != false
    private val navigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) != false
    private val behavior = controller.systemBarsBehavior
    fun hide() {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    fun restore() {
        controller.systemBarsBehavior = behavior
        if (statusVisible) controller.show(WindowInsetsCompat.Type.statusBars())
        if (navigationVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
    }
    suspend fun restoreAndAwaitLayout() {
        restore()
        withTimeoutOrNull(400) {
            do {
                withFrameNanos { }
                val current = ViewCompat.getRootWindowInsets(view)
                val ready = (!statusVisible || current?.isVisible(WindowInsetsCompat.Type.statusBars()) == true) &&
                    (!navigationVisible || current?.isVisible(WindowInsetsCompat.Type.navigationBars()) == true)
            } while (!ready)
            withFrameNanos { }
        }
    }
}

@Composable
private fun rememberGalleryWindow(): GalleryWindow? {
    val activity = LocalContext.current.galleryActivity()
    val view = LocalView.current
    val window = remember(activity, view) { activity?.let { GalleryWindow(view, WindowCompat.getInsetsController(it.window, view)) } }
    DisposableEffect(window) { window?.hide(); onDispose { window?.restore() } }
    return window
}
