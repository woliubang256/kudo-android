package com.kudo.android.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

internal enum class MiniAppFloatingPanel { PREVIEWS, MUSIC }
internal enum class MiniAppFloatingSide { LEFT, RIGHT }

internal class MiniAppFloatingPosition(
    private val side: MiniAppFloatingSide, private val fraction: Float,
    private val safe: IntRect, private val handleSize: Int, private val inset: Int,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = anchorBounds.left + safe.left
        val right = anchorBounds.right - safe.right
        val top = anchorBounds.top + safe.top
        val bottom = anchorBounds.bottom - safe.bottom
        val reserve = minOf(handleSize, (bottom - top).coerceAtLeast(0) / 5)
        val center = top + reserve + handleSize / 2 +
            ((bottom - top - reserve - handleSize * 2).coerceAtLeast(0) * fraction.coerceIn(0f, 1f)).roundToInt()
        val x = if (side == MiniAppFloatingSide.LEFT) left + inset else right - popupContentSize.width - inset
        val y = center - popupContentSize.height / 2
        return IntOffset(x.coerceIn(left, (right-popupContentSize.width).coerceAtLeast(left)),
            y.coerceIn(top, (bottom-popupContentSize.height).coerceAtLeast(top)))
    }
}

@Composable
internal fun MiniAppFloatingPopup(side: MiniAppFloatingSide, fraction: Float, safe: IntRect, handleSize: Int,
    expanded: Boolean, dismiss: () -> Unit, inset: Int = 0, content: @Composable () -> Unit) {
    val position = remember(side, fraction, safe, handleSize, inset) { MiniAppFloatingPosition(side, fraction, safe, handleSize, inset) }
    Popup(popupPositionProvider = position, onDismissRequest = dismiss,
        properties = PopupProperties(focusable = expanded, dismissOnBackPress = expanded, dismissOnClickOutside = expanded,
            clippingEnabled = false)) {
        Box(Modifier) { content() }
    }
}
