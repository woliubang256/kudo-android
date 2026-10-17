package com.kudo.android.ui.components

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.kudo.android.data.GlassMode
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalSheetActions = staticCompositionLocalOf { false }
internal val LocalSheetBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalSheetInputBlocked = staticCompositionLocalOf { false }

/** Same-window sheet: children scroll first; downward overflow moves the panel. */
@Composable
internal fun CommunitySheet(
    visibility: MutableTransitionState<Boolean>, title: String, dismiss: () -> Unit,
    modifier: Modifier = Modifier, showTitle: Boolean = false, dismissEnabled: Boolean = true,
    scrollContent: Boolean = true, showActions: Boolean = false, maxHeightFraction: Float = 1f,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    backdrop: Backdrop? = LocalSheetBackdrop.current,
    glass: GlassMode = LocalDisplaySettings.current.glass,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) {
    require(maxHeightFraction > 0f && maxHeightFraction <= 1f)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var offset by remember { mutableFloatStateOf(0f) }
    var panelHeight by remember { mutableFloatStateOf(1f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val currentDismiss by rememberUpdatedState(dismiss)
    val canDismiss by rememberUpdatedState(dismissEnabled && visibility.targetState)
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val inputBlocked = LocalSheetInputBlocked.current
    val canBlur = backdrop != null && glass != GlassMode.TRANSLUCENT && Build.VERSION.SDK_INT >= 31
    val canRefract = backdrop != null && glass == GlassMode.LIQUID && Build.VERSION.SDK_INT >= 33
    val panelShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val minimum = with(density) { 72.dp.toPx() }
    val maximum = with(density) { 160.dp.toPx() }
    val flingSpeed = with(density) { 1000.dp.toPx() }
    fun drag(delta: Float): Float {
        if (!canDismiss) return 0f
        settling?.cancel()
        val old = offset
        offset = (offset + delta).coerceIn(0f, panelHeight)
        return offset - old
    }
    fun settle(velocity: Float) {
        settling?.cancel()
        if (canDismiss && (offset > (panelHeight * 0.22f).coerceIn(minimum, maximum) ||
                (offset > minimum / 8 && velocity > flingSpeed))) currentDismiss()
        else settling = scope.launch { Animatable(offset).animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) { offset = value } }
    }
    val nestedScroll = remember(density) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && offset > 0f && available.y < 0f) Offset(0f, drag(available.y)) else Offset.Zero
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, drag(available.y)) else Offset.Zero
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset <= 0f) return Velocity.Zero
            settle(available.y)
            return Velocity(0f, available.y)
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (offset <= 0f) return Velocity.Zero
            settle(available.y)
            return Velocity(0f, available.y)
        }
    } }
    BackHandler(visibility.targetState) { if (dismissEnabled) dismiss() }
    LaunchedEffect(visibility.currentState, visibility.targetState) {
        if (!visibility.currentState && !visibility.targetState) { settling?.cancel(); offset = 0f }
    }
    AnimatedVisibility(visibility, modifier.fillMaxSize(), enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
        LaunchedEffect(visibility.targetState) { if (visibility.targetState) focus.requestFocus() }
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val safe = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal).asPaddingValues()
            val maxPanelHeight = (maxHeight - safe.calculateTopPadding() - 12.dp).coerceAtLeast(48.dp) * maxHeightFraction
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.38f * (1f - offset / panelHeight).coerceIn(0f, 1f)))
                .clickable(interactionSource = null, indication = null, onClickLabel = "关闭弹窗") { if (dismissEnabled) dismiss() }
                .testTag("modal-scrim"))
            Column(Modifier.align(Alignment.BottomCenter).padding(start = safe.calculateLeftPadding(LayoutDirection.Ltr), end = safe.calculateRightPadding(LayoutDirection.Ltr))
                .widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxPanelHeight)
                .animateEnterExit(enter = slideInVertically(tween(240)) { it }, exit = slideOutVertically(tween(180)) { it })
                .offset { IntOffset(0, offset.roundToInt()) }.onSizeChanged { panelHeight = it.height.toFloat().coerceAtLeast(1f) }
                .clip(panelShape)
                .then(if (backdrop != null) Modifier.drawBackdrop(backdrop, shape = { panelShape }, effects = {
                    if (canBlur) blur((if (light) 18.dp else 10.dp).toPx())
                    if (canRefract) lens(20.dp.toPx(), 40.dp.toPx(), depthEffect = true)
                }, onDrawSurface = {
                    drawRect(containerColor.copy(alpha = if (canBlur) if (light) .68f else .52f else .94f))
                }) else Modifier.background(containerColor))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (backdrop != null) .16f else .08f), panelShape)
                .nestedScroll(nestedScroll).focusRequester(focus)
                .focusProperties { onExit = { if (visibility.targetState && !inputBlocked) cancelFocusChange() } }.focusGroup()
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .semantics { dialog(); paneTitle = title }.testTag("liquid-action-sheet")) {
                Box(Modifier.fillMaxWidth().height(32.dp).testTag("sheet-drag-handle")
                    .draggable(rememberDraggableState { drag(it) }, Orientation.Vertical, enabled = dismissEnabled,
                        onDragStarted = { settling?.cancel() }, onDragStopped = { settle(it) }), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f), RoundedCornerShape(3.dp)))
                }
                if (showTitle) Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 14.dp)) {
                    Text(title, Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f, fill = false)
                    .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                if (showActions) CompositionLocalProvider(LocalSheetActions provides true) {
                    Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.Transparent).navigationBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically, content = actions)
                } else Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}
