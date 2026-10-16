package com.kudo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kudo.android.ui.components.liquid.LiquidButton
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kudo.android.ui.theme.LogoYellow
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

internal val LocalToolbarBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalToolbarActions = staticCompositionLocalOf { false }

/** Content alone is sampled; the header must never be inside its own source. */
@Composable
internal fun MaterialPageScaffold(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    var headerHeight by remember { mutableIntStateOf(0) }
    val top = with(LocalDensity.current) { headerHeight.toDp() }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).testTag("material-page-content")) {
            content(PaddingValues(top = top))
        }
        CompositionLocalProvider(LocalToolbarBackdrop provides backdrop, LocalToolbarActions provides true) {
            Column(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }
                .screenMaterial(backdrop).testTag("material-page-header")) { header() }
        }
    }
}

@Composable
internal fun MaterialPagePlaceholder(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    MaterialPageScaffold(header = { PageTopBar(title, back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), content = content)
    }
}

@Composable
internal fun TopBarIconButton(
    icon: ImageVector, description: String, action: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    backdrop: Backdrop? = LocalToolbarBackdrop.current,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    touchSize: Dp = 48.dp,
    visualSize: Dp = 40.dp,
    iconSize: Dp = 23.dp,
) {
    if (backdrop != null) {
        Box(modifier.size(touchSize).clickable(enabled = enabled, role = Role.Button, onClick = action),
            contentAlignment = Alignment.Center) {
            LiquidButton(action, backdrop, LocalDisplaySettings.current.glass, Modifier.size(visualSize),
                enabled = enabled, contentColor = tint, contentPadding = PaddingValues(0.dp),
                surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .3f), minimumHeight = visualSize) {
                Icon(icon, description, Modifier.size(iconSize))
            }
        }
    } else {
        Box(modifier.size(touchSize).clickable(enabled = enabled, role = Role.Button, onClick = action), contentAlignment = Alignment.Center) {
            Box(Modifier.size(visualSize).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .85f)), contentAlignment = Alignment.Center) {
                Icon(icon, description, Modifier.size(iconSize), tint = tint.copy(alpha = if (enabled) 1f else .38f))
            }
        }
    }
}

@Composable
internal fun TopBarTextButton(text: String, action: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val backdrop = LocalToolbarBackdrop.current
    if (backdrop != null) {
        Box(modifier.height(48.dp), contentAlignment = Alignment.Center) {
            LiquidButton(action, backdrop, LocalDisplaySettings.current.glass, Modifier.height(40.dp),
                enabled = enabled, minimumHeight = 40.dp, tint = LogoYellow, contentColor = Ink) {
                Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    } else {
        Box(modifier.height(48.dp).clickable(enabled = enabled, role = Role.Button, onClick = action),
            contentAlignment = Alignment.Center) {
            Box(Modifier.height(40.dp).clip(CircleShape)
                .background(LogoYellow.copy(alpha = if (enabled) 1f else .18f))
                .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(text, color = Ink.copy(alpha = if (enabled) 1f else .38f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}
