package com.kudo.android.ui.editor

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import com.kudo.android.data.GlassMode
import com.kudo.android.data.Member
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.*
import kotlin.math.roundToInt

@Composable
internal fun EditorMentionField(value: TextFieldValue, change: (TextFieldValue) -> Unit,
    search: suspend (String) -> List<Member>, modifier: Modifier = Modifier, placeholder: String = "",
    enabled: Boolean = true, suggestionsEnabled: Boolean = true, minLines: Int = 1,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge) {
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember(value) { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val query = remember(value, focused, enabled, suggestionsEnabled, dismissed) {
        value.takeIf { focused && enabled && suggestionsEnabled && !dismissed }?.mentionQuery()
    }
    var results by remember(query) { mutableStateOf(emptyList<Member>()) }
    var loading by remember(query) { mutableStateOf(true) }
    var error by remember(query) { mutableStateOf<String?>(null) }
    var attempt by remember(query) { mutableIntStateOf(0) }
    val currentSearch by rememberUpdatedState(search)
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(change)
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(query, attempt) {
        if (query == null) return@LaunchedEffect
        loading = true; error = null
        try {
            delay(300)
            val found = currentSearch(query.term)
            ensureActive()
            results = found
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            if (isActive) error = failure.message ?: "用户加载失败"
        } finally { if (isActive) loading = false }
    }
    BackHandler(query != null) { dismissed = true }
    Box {
        CommunityTextField(value, change, modifier.onFocusChanged { focused = it.isFocused }
            .onGloballyPositioned { origin = it.positionInWindow(); fieldSize = it.size }, placeholder, enabled,
            minLines = minLines, textStyle = textStyle, onTextLayout = { layout = it })
        if (query != null) {
            val density = LocalDensity.current
            val view = LocalView.current
            val ime = WindowInsets.ime.getBottom(density)
            val systemTop = WindowInsets.statusBars.getTop(density)
            val window = LocalWindowInfo.current.containerSize
            val frame = android.graphics.Rect().also { view.getWindowVisibleDisplayFrame(it) }
            val screen = IntArray(2).also(view::getLocationOnScreen)
            val inWindow = IntArray(2).also(view::getLocationInWindow)
            val bottom = minOf(window.height, frame.bottom - (screen[1] - inWindow[1]))
            val margin = with(density) { 12.dp.roundToPx() }
            val maxHeight = with(density) { ((bottom - systemTop - margin * 2).coerceAtLeast(1) * .6f).toDp().coerceAtMost(288.dp) }
            val width = with(density) { (window.width - margin * 2).coerceAtLeast(1).toDp().coerceAtMost(360.dp) }
            val cursor = layout?.takeIf { it.layoutInput.text.text == value.text }?.getCursorRect(value.selection.start)
            val top = origin.y + (cursor?.top ?: 0f).coerceIn(0f, fieldSize.height.toFloat())
            val below = origin.y + (cursor?.bottom ?: fieldSize.height.toFloat()).coerceIn(0f, fieldSize.height.toFloat())
            val position = remember(origin, top, below, bottom, systemTop, margin, ime) {
                object : PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                        popupContentSize: IntSize): IntOffset {
                        val x = origin.x.roundToInt().coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                        val lower = below.roundToInt() + margin / 2
                        val y = if (lower + popupContentSize.height <= bottom - margin) lower else anchorBounds.top - popupContentSize.height - margin / 2
                        return IntOffset(x, y.coerceIn(systemTop + margin, (bottom - popupContentSize.height - margin).coerceAtLeast(systemTop + margin)))
                    }
                }
            }
            key(query) { EditorMentionOverlay(position, IntRect(IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), fieldSize),
                dismiss = { dismissed = true }) { backdrop ->
                val mode = LocalDisplaySettings.current.glass
                val surface = MaterialTheme.colorScheme.surface
                val light = surface.luminance() > .5f
                val canBlur = mode != GlassMode.TRANSLUCENT && Build.VERSION.SDK_INT >= 31
                val canRefract = mode == GlassMode.LIQUID && Build.VERSION.SDK_INT >= 33
                val shape = RoundedCornerShape(12.dp)
                Surface(Modifier.width(width).heightIn(max = maxHeight).testTag("mention-suggestions")
                    .then(if (backdrop != null) Modifier.drawBackdrop(backdrop, shape = { shape }, effects = {
                        if (canBlur) {
                            colorControls(brightness = if (light) .2f else 0f, saturation = 1.5f)
                            blur((if (light) 16.dp else 8.dp).toPx())
                        }
                        if (canRefract) lens(24.dp.toPx(), 48.dp.toPx(), depthEffect = true)
                    }, highlight = { Highlight.Plain }, onDrawSurface = {
                        drawRect(surface.copy(alpha = if (canBlur) if (light) .6f else .4f else .96f))
                    }) else Modifier),
                    shape = shape, color = if (backdrop != null) Color.Transparent else surface, shadowElevation = 6.dp) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        if (loading) item {
                            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) { CommunityLoadingIndicator(Modifier.size(20.dp)) }
                        }
                        else if (error != null) item {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                Text("重试", Modifier.clickable(role = Role.Button) { attempt++ }.heightIn(min = 48.dp).wrapContentHeight(),
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        else if (results.isEmpty()) item {
                            Text("没有匹配用户", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else items(results, key = { it.username }) { user ->
                            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                val updated = currentValue.completeMention(query, user.username)
                                if (updated != currentValue) { currentChange(updated); keyboard?.show() }
                            }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp).testTag("mention-user-${user.username}"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Avatar(user, 28.dp, presentation = AvatarPresentation.PLAIN)
                                Text(user.username, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (user.name.isNotBlank() && !user.name.equals(user.username, true)) Text(user.name,
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } }
        }
    }
}
