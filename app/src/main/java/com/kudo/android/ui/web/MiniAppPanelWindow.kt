package com.kudo.android.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.kudo.android.data.GlassMode
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kyant.backdrop.Backdrop

/** The menu is a separate window, so Activity PixelCopy never captures the sheet itself. */
@Composable
internal fun MiniAppPanelWindow(state: MiniAppUiState, dismissEnabled: Boolean,
    content: @Composable (Backdrop?, GlassMode) -> Unit) {
    val followsMaterial = state.panel in setOf(MiniAppPanel.MENU, MiniAppPanel.ABOUT,
        MiniAppPanel.RATING, MiniAppPanel.TIP, MiniAppPanel.REPORT)
    val glass = if (followsMaterial) LocalDisplaySettings.current.glass else GlassMode.TRANSLUCENT
    // Capture from the Activity composition, before entering the Dialog's LocalView.
    val backdrop = rememberMiniAppFloatingBackdrop(followsMaterial && state.showing, glass)
    if (!followsMaterial) { content(null, GlassMode.TRANSLUCENT); return }
    if (!state.showing) return
    Dialog(onDismissRequest = { if (dismissEnabled) state.dismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        DisposableEffect(window) {
            // CommunitySheet owns the scrim; an additional system dim would darken it twice.
            window.setDimAmount(0f)
            window.setWindowAnimations(0)
            onDispose { }
        }
        Box(Modifier.fillMaxSize().testTag("mini-app-material-panel").semantics {
            floatingMaterial = glass; floatingSample = backdrop.ready
        }) { content(backdrop.takeIf { it.ready }, glass) }
    }
}
