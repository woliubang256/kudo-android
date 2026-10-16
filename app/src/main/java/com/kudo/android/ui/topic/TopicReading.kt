package com.kudo.android.ui.topic

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kudo.android.data.TopicReadingBatch
import com.kudo.android.data.TopicReadingProgress
import com.kudo.android.data.Post
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalReadingBlocked = staticCompositionLocalOf { false }
private data class Exposure(val number: Int, val bounds: Rect, val eligible: Boolean)
private class ReadingRegistry { val entries = mutableStateMapOf<Any, Exposure>() }
private val LocalReadingRegistry = staticCompositionLocalOf<ReadingRegistry?> { null }
private val LocalReadingViewport = staticCompositionLocalOf { Rect.Zero }

@Composable
internal fun TopicReadingEnvironment(topicId: Int, viewerId: Int?, enabled: Boolean,
    submit: (TopicReadingBatch) -> Unit, onRead: (Set<Int>) -> Unit = {}, content: @Composable () -> Unit) {
    val progress = remember(topicId, viewerId) { TopicReadingProgress(topicId) }
    val registry = remember(progress) { ReadingRegistry() }
    // Keep the outgoing session's callback bound to its original account during disposal.
    val submitState = remember(progress) { mutableStateOf(submit) }
    SideEffect { submitState.value = submit }
    val currentSubmit by submitState
    val readState = remember(progress) { mutableStateOf(onRead) }
    SideEffect { readState.value = onRead }
    fun notifyRead() { progress.takeReadNumbers().takeIf { it.isNotEmpty() }?.let(readState.value) }
    val currentEnabled by rememberUpdatedState(enabled && viewerId != null && !LocalReadingBlocked.current)
    val window = LocalWindowInfo.current
    var resumed by remember(progress) { mutableStateOf(false) }
    fun stop() {
        progress.visible(emptySet(), SystemClock.elapsedRealtime())
        notifyRead()
        progress.drain()?.let(currentSubmit)
    }
    LifecycleResumeEffect(progress) {
        resumed = true
        onPauseOrDispose { resumed = false; stop() }
    }
    DisposableEffect(progress) { onDispose { stop() } }
    LaunchedEffect(progress, registry, window) {
        launch {
            snapshotFlow { (currentEnabled && resumed && window.isWindowFocused) to registry.entries.toMap() }
                .collect { (active, entries) ->
                    progress.visible(if (active) entries.values.filter { it.eligible }.map { it.number }.toSet() else emptySet(), SystemClock.elapsedRealtime())
                    notifyRead()
                    if (!active) progress.drain()?.let(currentSubmit)
                }
        }
        var ticks = 0
        while (true) {
            delay(1000)
            progress.tick(SystemClock.elapsedRealtime())
            notifyRead()
            if (++ticks % 5 == 0) progress.drain()?.let(currentSubmit)
        }
    }
    CompositionLocalProvider(LocalReadingRegistry provides registry, content = content)
}

@Composable
internal fun PostUnreadIndicator(post: Post, state: TopicState) {
    AnimatedVisibility(state.viewerId != null && post.read == false && post.number !in state.readNumbers,
        enter = EnterTransition.None, exit = fadeOut(tween(1800))) {
        Box(Modifier.padding(horizontal = 6.dp).size(6.dp).background(Color(0xFF3D8BFF), CircleShape)
            .testTag("unread-post-${post.number}").semantics { contentDescription = "未读" })
    }
}

@Composable
internal fun PostReadingViewport(bottomInset: Dp = 0.dp, topInset: Dp = 0.dp, content: @Composable (Modifier) -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val inset = with(LocalDensity.current) { bottomInset.toPx() }
    val top = with(LocalDensity.current) { topInset.toPx() }
    CompositionLocalProvider(LocalReadingViewport provides bounds) {
        content(Modifier.onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) {
            val rect = it.boundsInWindow
            val area = Rect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
            val visibleTop = (area.top + top).coerceAtMost(area.bottom)
            bounds = Rect(area.left, visibleTop, area.right, (area.bottom - inset).coerceAtLeast(visibleTop))
        })
    }
}

@Composable
internal fun PostReadingContent(number: Int, enabled: Boolean, content: @Composable () -> Unit) {
    val registry = LocalReadingRegistry.current
    val viewport = LocalReadingViewport.current
    val key = remember { Any() }
    var body by remember { mutableStateOf(Rect.Zero) }
    DisposableEffect(registry, key) { onDispose { registry?.entries?.remove(key) } }
    // Read geometry during composition so scrolling invalidates this exposure calculation.
    val visible = body.intersect(viewport)
    val eligible = enabled && body.height > 0 && viewport.height > 0 &&
        body.bottom <= viewport.bottom + 1 &&
        visible.height >= minOf(body.height, viewport.height) * .5f
    val exposure = Exposure(number, body, eligible)
    SideEffect { registry?.entries?.set(key, exposure) }
    Box(Modifier.onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) {
        val rect = it.boundsInWindow
        body = Rect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
    }) { content() }
}
