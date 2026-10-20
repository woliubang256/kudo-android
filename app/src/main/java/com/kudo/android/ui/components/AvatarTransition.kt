package com.kudo.android.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NamedNavArgument
import androidx.navigation.compose.composable
import com.kudo.android.data.Member
import java.util.UUID

internal class AvatarTransitions(private val nav: NavHostController) {
    internal data class Seed(val key: String, val sourceEntry: String, val member: Member)
    private var pending: Seed? = null
    private val seeds = mutableStateMapOf<String, Seed>()
    private val observers = mutableMapOf<NavBackStackEntry, LifecycleEventObserver>()

    internal fun fromAvatar(key: String, entry: NavBackStackEntry, member: Member, open: () -> Unit) {
        if (nav.currentBackStackEntry != entry || entry.lifecycle.currentState != Lifecycle.State.RESUMED) return
        pending = Seed(key, entry.id, member)
        try { open() } finally { pending = null }
    }

    fun openUser(username: String) {
        if (username.isBlank()) return
        val source = pending?.takeIf {
            it.member.username.equals(username, ignoreCase = true) && it.sourceEntry == nav.currentBackStackEntry?.id
        }
        nav.navigate("user/${Uri.encode(username)}")
        if (source != null) nav.currentBackStackEntry?.let { entry ->
            seeds[entry.id] = source
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    seeds.remove(entry.id)
                    observers.remove(entry)?.let(entry.lifecycle::removeObserver)
                }
            }
            observers[entry] = observer
            entry.lifecycle.addObserver(observer)
        }
    }

    internal fun seed(entry: NavBackStackEntry): Seed? = seeds[entry.id]

    internal fun clear() {
        observers.forEach { (entry, observer) -> entry.lifecycle.removeObserver(observer) }
        observers.clear()
        seeds.clear()
    }
}

private data class AvatarScene(val entry: NavBackStackEntry, val animation: AnimatedVisibilityScope)
private val LocalAvatarTransitions = staticCompositionLocalOf<AvatarTransitions?> { null }
private val LocalAvatarSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
private val LocalAvatarScene = staticCompositionLocalOf<AvatarScene?> { null }
private class ProfileAvatarSource(val key: String, var member: Member)
private class ProfileAvatarLink(val source: ProfileAvatarSource, val open: (() -> Unit)?)
private val LocalProfileAvatarLink = staticCompositionLocalOf<ProfileAvatarLink?> { null }

@Composable
internal fun AvatarProfileLink(member: Member, enabled: Boolean = true, onClick: () -> Unit, content: @Composable (() -> Unit) -> Unit) {
    val scene = LocalAvatarScene.current
    val transitions = LocalAvatarTransitions.current
    val instance = rememberSaveable { UUID.randomUUID().toString() }
    val source = remember(member, scene?.entry?.id, instance) { ProfileAvatarSource("${scene?.entry?.id}:${member.username}:$instance", member) }
    val open = {
        if (enabled && member.username.isNotBlank()) {
            if (scene != null && transitions != null) transitions.fromAvatar(source.key, scene.entry, source.member, onClick)
            else onClick()
        }
    }
    CompositionLocalProvider(LocalProfileAvatarLink provides ProfileAvatarLink(source, open.takeIf { enabled && member.username.isNotBlank() })) {
        content(open)
    }
}

internal fun NavGraphBuilder.avatarComposable(route: String, arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit) {
    composable(route, arguments) { entry -> AvatarTransitionScene(entry, this) { content(entry) } }
}

@Composable
internal fun AvatarTransitionHost(transitions: AvatarTransitions?, content: @Composable () -> Unit) {
    if (transitions == null) { content(); return }
    DisposableEffect(transitions) { onDispose { transitions.clear() } }
    SharedTransitionLayout {
        CompositionLocalProvider(LocalAvatarTransitions provides transitions, LocalAvatarSharedScope provides this) { content() }
    }
}

@Composable
internal fun AvatarTransitionScene(entry: NavBackStackEntry, animation: AnimatedVisibilityScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAvatarScene provides remember(entry, animation) { AvatarScene(entry, animation) }, content = content)
}

internal class AvatarTransitionAnchor(val modifier: Modifier, val open: (() -> Unit)?)

@Composable
internal fun avatarTransitionAnchor(member: Member?, destination: Boolean, onClick: (() -> Unit)?): AvatarTransitionAnchor {
    val link = LocalProfileAvatarLink.current?.takeIf { !destination && onClick == null && it.source.member.username.equals(member?.username, ignoreCase = true) }
    SideEffect { if (link != null && member != null) link.source.member = member }
    val transitions = LocalAvatarTransitions.current
    val shared = LocalAvatarSharedScope.current
    val scene = LocalAvatarScene.current
    if (transitions == null || shared == null || scene == null || member == null || member.username.isBlank()) {
        return AvatarTransitionAnchor(Modifier, onClick ?: link?.open)
    }
    val instance = rememberSaveable { UUID.randomUUID().toString() }
    val key = "${scene.entry.id}:${member.username}:$instance"
    val sharedKey = if (destination) transitions.seed(scene.entry)?.takeIf {
        it.member.username.equals(member.username, ignoreCase = true)
    }?.key else link?.source?.key ?: key.takeIf { onClick != null }
    val modifier = if (sharedKey == null) Modifier else with(shared) {
        Modifier.sharedElement(rememberSharedContentState(sharedKey), scene.animation,
            boundsTransform = { _, _ -> tween(360, easing = FastOutSlowInEasing) })
    }
    return AvatarTransitionAnchor(modifier, link?.open ?: onClick?.let { open ->
        { transitions.fromAvatar(key, scene.entry, member, open) }
    })
}
