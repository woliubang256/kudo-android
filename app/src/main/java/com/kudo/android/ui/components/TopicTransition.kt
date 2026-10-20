package com.kudo.android.ui.components

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kudo.android.data.Topic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

private const val SourceKey = "topic-transition-key"
private const val SourceEntry = "topic-transition-entry"
private const val OpeningDuration = 300
private const val ContainerDuration = 480

internal class TopicTransitions(private val nav: NavHostController) {
    private data class Source(val topicId: Int, val entryId: String, val snapshot: TopicCardSnapshot)
    private var pending: Source? = null
    private var capturing = false
    private val snapshots = mutableStateMapOf<String, TopicCardSnapshot>()
    private val observers = mutableMapOf<NavBackStackEntry, LifecycleEventObserver>()
    internal val anchors = mutableMapOf<String, TopicCardAnchor>()

    internal suspend fun fromCard(
        topicId: Int, entryId: String, capture: suspend () -> TopicCardSnapshot?, open: () -> Unit,
    ) {
        val entry = nav.currentBackStackEntry ?: return
        if (capturing || entry.id != entryId || entry.lifecycle.currentState != Lifecycle.State.RESUMED) return
        capturing = true
        try {
            val snapshot = try { capture() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) { null }
            if (nav.currentBackStackEntry != entry || entry.lifecycle.currentState != Lifecycle.State.RESUMED) return
            pending = snapshot?.let { Source(topicId, entryId, it) }
            open()
        } finally {
            pending = null
            capturing = false
        }
    }

    fun openTopic(topicId: Int, number: Int? = null) {
        if (topicId <= 0) return
        val source = pending?.takeIf { it.topicId == topicId && it.entryId == nav.currentBackStackEntry?.id }
        pending = null
        nav.navigate("topic/$topicId?post=${number ?: 0}")
        if (source != null) nav.currentBackStackEntry?.let { entry ->
            entry.savedStateHandle[SourceKey] = source.snapshot.key
            entry.savedStateHandle[SourceEntry] = source.entryId
            snapshots[entry.id] = source.snapshot
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    snapshots.remove(entry.id)
                    observers.remove(entry)?.let(entry.lifecycle::removeObserver)
                }
            }
            observers[entry] = observer
            entry.lifecycle.addObserver(observer)
        }
    }

    internal fun snapshot(entry: NavBackStackEntry): TopicCardSnapshot? = snapshots[entry.id]
    internal fun hasSource(entryId: String): Boolean = snapshots.values.any { it.key.startsWith("$entryId:") }
    @Composable
    internal fun returningToSource(entry: NavBackStackEntry): Boolean {
        val current by nav.currentBackStackEntryAsState()
        return current?.id == entry.savedStateHandle.get<String>(SourceEntry)
    }
    internal fun back() { nav.popBackStack() }
    internal fun clear() {
        observers.forEach { (entry, observer) -> entry.lifecycle.removeObserver(observer) }
        observers.clear()
        snapshots.clear()
        anchors.clear()
    }
}

private class TopicTransitionSource(val entryId: String)
private val LocalTopicTransitions = staticCompositionLocalOf<TopicTransitions?> { null }
private val LocalTopicTransitionSource = staticCompositionLocalOf<TopicTransitionSource?> { null }
private val LocalTopicContentReady = staticCompositionLocalOf<MutableState<Boolean>?> { null }
private val LocalTopicEntered = staticCompositionLocalOf<State<Boolean>?> { null }
private val LocalTopicPreview = staticCompositionLocalOf<Topic?> { null }

@Composable
internal fun TopicTransitionHost(transitions: TopicTransitions, avatars: AvatarTransitions? = null, content: @Composable () -> Unit) {
    DisposableEffect(transitions) { onDispose { transitions.clear() } }
    CompositionLocalProvider(LocalTopicTransitions provides transitions) {
        AvatarTransitionHost(avatars) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { content() }
        }
    }
}

@Composable
internal fun TopicTransitionContentReady(ready: Boolean) {
    val state = LocalTopicContentReady.current
    SideEffect { state?.value = ready }
}

@Composable
internal fun TopicTransitionBody(ready: Boolean, content: @Composable () -> Unit) {
    val entered = LocalTopicEntered.current?.value != false
    val preview = LocalTopicPreview.current
    val transitions = LocalTopicTransitions.current
    TopicTransitionContentReady(preview != null || (entered && ready))
    if (preview != null) {
        Crossfade(entered && ready, animationSpec = tween(180), label = "topic-preview-handoff") { loaded ->
            if (loaded) content() else TopicOpeningPreview(preview) { transitions?.back() }
        }
    } else if (entered) {
        content()
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
        val source = remember(entry.id) { TopicTransitionSource(entry.id) }
        val blurEnabled = LocalTopicTransitions.current?.hasSource(entry.id) == true
        val blur by transition.animateDp(
            transitionSpec = {
                tween(if (blurEnabled && targetState == EnterExitState.PostExit) OpeningDuration else ContainerDuration,
                    easing = FastOutSlowInEasing)
            }, label = "topic-feed-blur",
        ) { if (blurEnabled && it != EnterExitState.Visible) 6.dp else 0.dp }
        CompositionLocalProvider(LocalTopicTransitionSource provides source) {
            AvatarTransitionScene(entry, this) {
            Box(Modifier.fillMaxSize().blur(blur)) { content(entry) }
            }
        }
    }
}

internal fun NavGraphBuilder.topicDetailComposable(
    route: String,
    arguments: List<NamedNavArgument>,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments,
        enterTransition = {
            if (targetState.hasTopicTransition()) EnterTransition.None
            else fadeIn(tween(280)) + scaleIn(tween(280, easing = FastOutSlowInEasing), initialScale = 0.97f)
        },
        popEnterTransition = {
            fadeIn(tween(220)) + scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.98f)
        },
        popExitTransition = {
            if (initialState.hasTopicTransition()) ExitTransition.None
            else fadeOut(tween(220)) + scaleOut(tween(220, easing = FastOutSlowInEasing), targetScale = 0.98f)
        }) { entry ->
        AvatarTransitionScene(entry, this) { TopicTransitionDetail(entry, this) { content(entry) } }
    }
}

internal class TopicTransitionAnchor(val modifier: Modifier, val open: () -> Unit)

@Composable
internal fun topicTransitionAnchor(topicId: Int, radius: Dp, preview: Topic? = null, onClick: () -> Unit): TopicTransitionAnchor {
    val transitions = LocalTopicTransitions.current
    val source = LocalTopicTransitionSource.current
    if (transitions == null || source == null) return TopicTransitionAnchor(Modifier, onClick)
    val instance = rememberSaveable { UUID.randomUUID().toString() }
    val key = "${source.entryId}:$topicId:$instance"
    val anchor = remember(key) { TopicCardAnchor() }
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val surface = MaterialTheme.colorScheme.surface
    DisposableEffect(transitions, key) {
        transitions.anchors[key] = anchor
        onDispose { if (transitions.anchors[key] === anchor) transitions.anchors.remove(key) }
    }
    val modifier = Modifier.onGloballyPositioned { anchor.coordinates = it }.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
    return TopicTransitionAnchor(modifier) {
        scope.launch {
            transitions.fromCard(topicId, source.entryId, capture = {
                val bounds = anchor.bounds()
                if (bounds == null || layer.size.width.toLong() * layer.size.height !in 1..4_000_000) null
                else TopicCardSnapshot(key, bounds, radius, surface, layer.toImageBitmap(), preview?.takeIf { it.id == topicId })
            }, open = onClick)
        }
    }
}

@Composable
internal fun TopicTransitionItem(topicId: Int, radius: Dp, onClick: () -> Unit, preview: Topic? = null,
    content: @Composable (() -> Unit) -> Unit) {
    val anchor = topicTransitionAnchor(topicId, radius, preview, onClick)
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
    val transitions = LocalTopicTransitions.current
    val snapshot = transitions?.snapshot(entry)
    var firstVisit by remember(entry.id, snapshot) { mutableStateOf(snapshot?.opened == false) }
    val returning = transitions?.returningToSource(entry) == true
    val leaving = animation.transition.targetState == EnterExitState.PostExit
    val activeSnapshot = snapshot?.takeIf { (firstVisit && !leaving) || returning }
    if (leaving && !returning && firstVisit) SideEffect { firstVisit = false }
    val ready = remember(entry.id) { mutableStateOf(true) }
    val entered = remember(entry.id, snapshot) { mutableStateOf(snapshot?.opened != false) }
    if (snapshot == null) SideEffect {
        entry.savedStateHandle.remove<String>(SourceKey)
        entry.savedStateHandle.remove<String>(SourceEntry)
    }
    val progress = if (activeSnapshot != null) animation.transition.animateFloat(
        transitionSpec = {
            tween(if (targetState == EnterExitState.Visible) OpeningDuration else ContainerDuration,
                easing = FastOutSlowInEasing)
        }, label = "topic-card-progress",
    ) { if (it == EnterExitState.Visible) 1f else 0f } else rememberUpdatedState(1f)
    if (snapshot != null && !leaving && !entered.value && progress.value >= 1f) SideEffect {
        snapshot.opened = true
        entered.value = true
    }
    CompositionLocalProvider(LocalTopicTransitionSource provides null, LocalTopicContentReady provides ready,
        LocalTopicEntered provides entered, LocalTopicPreview provides snapshot?.preview) {
        TopicCardContainer(activeSnapshot, progress, ready,
            closing = { animation.transition.targetState == EnterExitState.PostExit },
            sourceBounds = { snapshot?.let { transitions.anchors[it.key]?.bounds() } },
            back = { transitions?.back() }, content = content)
    }
}

private fun NavBackStackEntry.hasTopicTransition(): Boolean = savedStateHandle.get<String>(SourceKey) != null
