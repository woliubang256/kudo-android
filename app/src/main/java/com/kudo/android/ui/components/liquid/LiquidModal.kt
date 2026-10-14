/*
 * Dialog surface adapted from Kyant0/AndroidLiquidGlass catalog (Apache License 2.0).
 * Source revision: 896a94a3ade1cc1a940b92365f942a34971fecda (Android 1.0.6).
 * Source: catalog/src/main/java/com/kyant/backdrop/catalog/destinations/DialogContent.kt
 * Changes: in-window modal behavior, action sheet, theme/material fallback,
 * safe areas, scrolling, accessibility isolation, and brand colors.
 * Full license: app/src/main/assets/licenses/backdrop-demo-LICENSE.txt
 */
package com.kudo.android.ui.components.liquid

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kudo.android.data.GlassMode
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LogoYellow
import com.kudo.android.ui.components.CommunitySheet
import com.kudo.android.ui.components.LocalSheetActions
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle

/** Renders above the source layer in the same window; the scrim never enters the backdrop. */
@Composable
internal fun LiquidModal(
    visibility: MutableTransitionState<Boolean>,
    title: String,
    actionSheet: Boolean,
    backdrop: Backdrop,
    mode: GlassMode,
    dismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    showTitle: Boolean = !actionSheet,
    showActions: Boolean = !actionSheet,
    scrollContent: Boolean = true,
    sheetBackground: Color = MaterialTheme.colorScheme.surface,
    dismissEnabled: Boolean = true,
    maxDialogHeight: Dp = Dp.Infinity,
    maxDialogHeightFraction: Float = 1f,
    transformOrigin: TransformOrigin = TransformOrigin.Center,
    initialScale: Float = 0.94f,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (actionSheet) {
        CommunitySheet(visibility, title, dismiss, showTitle = showTitle, showActions = showActions,
            scrollContent = scrollContent, dismissEnabled = dismissEnabled, containerColor = sheetBackground,
            actions = actions, content = content)
        return
    }
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val foreground = MaterialTheme.colorScheme.onSurface
    val container = MaterialTheme.colorScheme.surface.copy(alpha = if (light) 0.6f else 0.4f)
    val canBlur = mode != GlassMode.TRANSLUCENT && Build.VERSION.SDK_INT >= 31
    val canRefract = mode == GlassMode.LIQUID && Build.VERSION.SDK_INT >= 33
    val focus = remember { FocusRequester() }
    BackHandler(visibility.targetState, dismiss)

    AnimatedVisibility(visibility, Modifier.fillMaxSize(), enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
        LaunchedEffect(visibility.targetState) { if (visibility.targetState) focus.requestFocus() }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
            val availableHeight = (maxHeight - safeInsets.calculateTopPadding() - safeInsets.calculateBottomPadding() - 32.dp).coerceAtLeast(48.dp)
            val maxPanelHeight = minOf(availableHeight * maxDialogHeightFraction, maxDialogHeight)
            Box(Modifier.fillMaxSize()
                .background(if (light) Color(0xFF29293A).copy(alpha = 0.23f) else Color(0xFF121212).copy(alpha = 0.56f))
                .clickable(interactionSource = null, indication = null, onClickLabel = "关闭弹窗", onClick = dismiss)
                .testTag("modal-scrim"))
            Column(
                Modifier.align(Alignment.Center)
                    .padding(safeInsets).padding(horizontal = 16.dp, vertical = 16.dp)
                    .widthIn(max = 440.dp).fillMaxWidth()
                    .animateEnterExit(
                        enter = scaleIn(tween(180), initialScale = initialScale, transformOrigin = transformOrigin),
                        exit = scaleOut(tween(140), targetScale = 0.96f))
                    .focusRequester(focus)
                    .focusProperties { onExit = { if (visibility.targetState) cancelFocusChange() } }
                    .focusGroup()
                    .drawBackdrop(backdrop, shape = { RoundedRectangle(36.dp) },
                        effects = {
                            if (canBlur) {
                                colorControls(brightness = if (light) 0.2f else 0f, saturation = 1.5f)
                                blur((if (light) 16.dp else 8.dp).toPx())
                            }
                            if (canRefract) lens(24.dp.toPx(), 48.dp.toPx(), depthEffect = true)
                        }, highlight = { Highlight.Plain },
                        onDrawSurface = { drawRect(if (canBlur) container else container.copy(alpha = 0.96f)) })
                    .heightIn(max = maxPanelHeight)
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .semantics { dialog(); paneTitle = title }
                    .testTag("liquid-dialog")
                    .padding(20.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides foreground) {
                    Column(Modifier.weight(1f, fill = false)
                        .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (showTitle) Text(title, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        content()
                    }
                    if (showActions) Row(Modifier.fillMaxWidth().padding(top = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
        }
    }
}

@Composable
internal fun LiquidModalAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    if (LocalSheetActions.current) {
        Box(modifier.background(if (primary) LogoYellow else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (primary) Ink else LocalContentColor.current)
        }
        return
    }
    Box(modifier.clip(RoundedCornerShape(50))
        .background(if (primary) LogoYellow else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
        .clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (primary) Ink else LocalContentColor.current)
    }
}
