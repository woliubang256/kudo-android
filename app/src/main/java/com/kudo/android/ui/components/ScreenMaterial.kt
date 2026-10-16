package com.kudo.android.ui.components

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.kudo.android.data.GlassMode
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/** The source must be a sibling content layer, never an ancestor containing this surface. */
@Composable
internal fun Modifier.screenMaterial(
    backdrop: Backdrop,
    mode: GlassMode = LocalDisplaySettings.current.glass,
    fraction: () -> Float = { 1f },
    expandedColor: Color = MaterialTheme.colorScheme.surface,
): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val canBlur = mode != GlassMode.TRANSLUCENT && Build.VERSION.SDK_INT >= 31
    val canRefract = mode == GlassMode.LIQUID && Build.VERSION.SDK_INT >= 33
    return drawPlainBackdrop(backdrop, shape = { RoundedCornerShape(0.dp) }, effects = {
        val progress = fraction().coerceIn(0f, 1f)
        if (canBlur && progress > 0f) { vibrancy(); blur(4.dp.toPx() * progress) }
        if (canRefract && progress > 0f) lens(24.dp.toPx() * progress, 24.dp.toPx() * progress)
    }, onDrawSurface = {
        drawRect(lerp(expandedColor, surface.copy(alpha = if (canBlur) .2f else .94f), fraction().coerceIn(0f, 1f)))
    })
}
