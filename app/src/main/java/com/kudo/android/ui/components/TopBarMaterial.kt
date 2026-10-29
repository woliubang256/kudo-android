package com.kudo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import kotlin.math.roundToInt

internal val LocalToolbarBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalToolbarActions = staticCompositionLocalOf { false }

@Composable
internal fun WebPageScaffold(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    CompositionLocalProvider(LocalToolbarBackdrop provides null, LocalToolbarActions provides false) {
        Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().testTag("material-page-header")) { header() }
            Box(Modifier.weight(1f).fillMaxWidth()) { content(PaddingValues()) }
        }
    }
}

/** Content alone is sampled; the header must never be inside its own source. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MaterialPageScaffold(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) = MaterialPageLayout(header, modifier, null, null, content)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CollapsingPageScaffold(
    header: @Composable () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
    pinnedHeader: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) = MaterialPageLayout(header, modifier, scrollBehavior, pinnedHeader, content)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MaterialPageLayout(
    header: @Composable () -> Unit,
    modifier: Modifier,
    scrollBehavior: TopAppBarScrollBehavior?,
    pinnedHeader: (@Composable () -> Unit)?,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    var headerHeight by rememberSaveable { mutableIntStateOf(0) }
    val top = with(LocalDensity.current) { headerHeight.toDp() }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).testTag("material-page-content")) {
            content(PaddingValues(top = top))
        }
        CompositionLocalProvider(LocalToolbarBackdrop provides backdrop, LocalToolbarActions provides true) {
            Column(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }
                .screenMaterial(backdrop)
                .testTag("material-page-header")
                .then(if (scrollBehavior != null) Modifier.statusBarsPadding() else Modifier)) {
                Column(Modifier.fillMaxWidth().then(if (scrollBehavior != null) Modifier.clipToBounds().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    scrollBehavior.state.heightOffsetLimit = -placeable.height.toFloat()
                    val offset = scrollBehavior.state.heightOffset.roundToInt()
                    layout(placeable.width, (placeable.height + offset).coerceAtLeast(0)) { placeable.placeRelative(0, offset) }
                } else Modifier).testTag("material-page-title")) { header() }
                pinnedHeader?.invoke()
            }
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
        Box(modifier.size(touchSize).clickable(interactionSource = null, indication = null,
            enabled = enabled, role = Role.Button, onClick = action),
            contentAlignment = Alignment.Center) {
            LiquidButton(action, backdrop, LocalDisplaySettings.current.glass, Modifier.size(visualSize),
                enabled = enabled, contentColor = tint, contentPadding = PaddingValues(0.dp),
                surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .3f), minimumHeight = visualSize) {
                Icon(icon, description, Modifier.size(iconSize))
            }
        }
    } else {
        Box(modifier.size(touchSize).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = action), contentAlignment = Alignment.Center) {
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
        Box(modifier.height(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = action),
            contentAlignment = Alignment.Center) {
            Box(Modifier.height(40.dp).clip(CircleShape)
                .background(LogoYellow.copy(alpha = if (enabled) 1f else .18f))
                .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(text, color = Ink.copy(alpha = if (enabled) 1f else .38f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}
