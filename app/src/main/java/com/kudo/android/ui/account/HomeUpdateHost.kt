package com.kudo.android.ui.account

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kudo.android.BuildConfig
import com.kudo.android.data.GitHubRelease
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Activity-scoped: navigation and configuration changes must not restart automatic checks. */
internal class HomeUpdateViewModel(
    private val check: suspend () -> GitHubRelease,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
) : ViewModel() {
    private var checked = false
    private val pending = MutableStateFlow<GitHubRelease?>(null)
    val release = pending.asStateFlow()

    fun checkOnce() {
        if (checked) return
        checked = true
        viewModelScope.launch {
            try {
                val value = check()
                if (value.isNewerThan(currentVersion)) pending.value = value
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Automatic checks never surface network or version errors. */ }
        }
    }

    fun dismiss() { pending.value = null }
}

@Composable
internal fun HomeUpdateHost(model: HomeUpdateViewModel, active: Boolean, external: (String) -> Unit,
    content: @Composable () -> Unit) {
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(model, active) {
        resumed = true
        if (active) model.checkOnce()
        onPauseOrDispose { resumed = false }
    }
    val release by model.release.collectAsStateWithLifecycle()
    var displayed by remember { mutableStateOf<GitHubRelease?>(null) }
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(release, active, resumed) {
        if (active && resumed && release != null) {
            displayed = release
            visibility.targetState = true
        } else visibility.targetState = false
    }
    LaunchedEffect(visibility.isIdle, visibility.currentState) {
        if (visibility.isIdle && !visibility.currentState) displayed = null
    }
    val showing = visibility.currentState || visibility.targetState
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)
            .then(if (showing) Modifier.clearAndSetSemantics {} else Modifier)
            .focusProperties { onEnter = { if (showing) cancelFocusChange() } }.focusGroup()) { content() }
        displayed?.let { value ->
            UpdateSheet(visibility, value, backdrop, external) {
                visibility.targetState = false
                model.dismiss()
            }
        }
    }
}
