package com.kudo.android.ui.components

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import java.util.UUID

private const val SourceKey = "topic-transition-key"
private const val SourceEntry = "topic-transition-entry"
private const val SourceRadius = "topic-transition-radius"

internal class TopicTransitions(private val nav: NavHostController) {
    private data class Source(val topicId: Int, val entryId: String, val key: String, val radius: Float)
    private var pending: Source? = null

    fun fromCard(topicId: Int, entryId: String, key: String, radius: Dp, open: () -> Unit) {
        val entry = nav.currentBackStackEntry ?: return
        if (entry.id != entryId || entry.lifecycle.currentState != Lifecycle.State.RESUMED) return
        pending = Source(topicId, entryId, key, radius.value)
        try {
            open()
        } finally {
            pending = null
        }
    }

    fun openTopic(topicId: Int, number: Int? = null) {
        if (topicId <= 0) return
        val source = pending?.takeIf { it.topicId == topicId && it.entryId == nav.currentBackStackEntry?.id }
        pending = null
        nav.navigate("topic/$topicId?post=${number ?: 0}")
        if (source != null) nav.currentBackStackEntry?.savedStateHandle?.apply {
            set(SourceKey, source.key)
            set(SourceEntry, source.entryId)
            set(SourceRadius, source.radius)
        }
    }
}

private class TopicTransitionEnvironment(val transitions: TopicTransitions, val shared: SharedTransitionScope)
private class TopicTransitionSource(val entryId: String, val animation: AnimatedContentScope)
private val LocalTopicTransitions = staticCompositionLocalOf<TopicTransitionEnvironment?> { null }
private val LocalTopicTransitionSource = staticCompositionLocalOf<TopicTransitionSource?> { null }

@Composable
internal fun TopicTransitionHost(transitions: TopicTransitions, content: @Composable () -> Unit) {
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val environment = remember(transitions, this) { TopicTransitionEnvironment(transitions, this) }
        CompositionLocalProvider(LocalTopicTransitions provides environment) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { content() }
        }
    }
}

internal fun NavGraphBuilder.topicListComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments,
        exitTransition = {
            if (targetState.savedStateHandle.get<String>(SourceEntry) == initialState.id) ExitTransition.None else null
        },
        popEnterTransition = {
            if (initialState.savedStateHandle.get<String>(SourceEntry) == targetState.id) EnterTransition.None else null
        }) { entry ->
        val source = remember(entry.id, this) { TopicTransitionSource(entry.id, this) }
        CompositionLocalProvider(LocalTopicTransitionSource provides source) { content(entry) }
    }
}

internal fun NavGraphBuilder.topicDetailComposable(
    route: String,
    arguments: List<NamedNavArgument>,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments,
        enterTransition = {
            if (targetState.hasTopicTransition()) fadeIn(tween(360))
            else fadeIn(tween(280)) + scaleIn(tween(280, easing = FastOutSlowInEasing), initialScale = 0.97f)
        },
        popEnterTransition = {
            fadeIn(tween(220)) + scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.98f)
        },
        popExitTransition = {
            if (initialState.hasTopicTransition()) fadeOut(tween(360))
            else fadeOut(tween(220)) + scaleOut(tween(220, easing = FastOutSlowInEasing), targetScale = 0.98f)
        }) { entry ->
        TopicTransitionDetail(entry, this) { content(entry) }
    }
}

internal class TopicTransitionAnchor(val modifier: Modifier, val open: () -> Unit)

@Composable
internal fun topicTransitionAnchor(topicId: Int, radius: Dp, onClick: () -> Unit): TopicTransitionAnchor {
    val environment = LocalTopicTransitions.current
    val source = LocalTopicTransitionSource.current
    if (environment == null || source == null) return TopicTransitionAnchor(Modifier, onClick)
    val instance = rememberSaveable { UUID.randomUUID().toString() }
    val key = "${source.entryId}:$topicId:$instance"
    val corner by source.animation.transition.animateDp(
        transitionSpec = { tween(360, easing = FastOutSlowInEasing) }, label = "topic-source-corner",
    ) { if (it == EnterExitState.Visible) radius else 0.dp }
    val modifier = with(environment.shared) {
        Modifier.sharedBounds(
            rememberSharedContentState(key), source.animation,
            enter = fadeIn(tween(220)), exit = fadeOut(tween(180)),
            boundsTransform = { _, _ -> tween(360, easing = FastOutSlowInEasing) },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopStart),
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(corner)),
        )
    }
    return TopicTransitionAnchor(modifier) {
        environment.transitions.fromCard(topicId, source.entryId, key, radius, onClick)
    }
}

@Composable
internal fun TopicTransitionItem(topicId: Int, radius: Dp, onClick: () -> Unit, content: @Composable (() -> Unit) -> Unit) {
    val anchor = topicTransitionAnchor(topicId, radius, onClick)
    CompositionLocalProvider(LocalTopicTransitionSource provides null) {
        Box(Modifier.fillMaxWidth().then(anchor.modifier)) { content(anchor.open) }
    }
}

@Composable
internal fun TopicTransitionDetail(
    entry: NavBackStackEntry,
    animation: AnimatedContentScope,
    content: @Composable () -> Unit,
) {
    val environment = LocalTopicTransitions.current
    val key = entry.savedStateHandle.get<String>(SourceKey)
    val radius = (entry.savedStateHandle.get<Float>(SourceRadius) ?: 0f).dp
    val corner by animation.transition.animateDp(
        transitionSpec = { tween(360, easing = FastOutSlowInEasing) }, label = "topic-detail-corner",
    ) { if (it == EnterExitState.Visible) 0.dp else radius }
    val sharedModifier = if (environment != null && key != null) with(environment.shared) {
        Modifier.sharedBounds(
            rememberSharedContentState(key), animation,
            enter = fadeIn(tween(260, delayMillis = 60)), exit = fadeOut(tween(180)),
            boundsTransform = { _, _ -> tween(360, easing = FastOutSlowInEasing) },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopStart),
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(corner)),
            zIndexInOverlay = 1f,
        )
    } else Modifier
    CompositionLocalProvider(LocalTopicTransitionSource provides null) {
        Box(Modifier.fillMaxSize().then(sharedModifier).background(MaterialTheme.colorScheme.background)
            .testTag("topic-transition-detail")) { content() }
    }
}

private fun NavBackStackEntry.hasTopicTransition(): Boolean = savedStateHandle.get<String>(SourceKey) != null
