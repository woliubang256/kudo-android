package com.kudo.android.ui.web

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kudo.android.data.GlassMode
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal val FloatingMaterialKey = SemanticsPropertyKey<GlassMode>("FloatingMaterial")
internal var SemanticsPropertyReceiver.floatingMaterial by FloatingMaterialKey
internal val FloatingSampleKey = SemanticsPropertyKey<Boolean>("FloatingBackgroundSample")
internal var SemanticsPropertyReceiver.floatingSample by FloatingSampleKey

private data class WindowFrame(val image: ImageBitmap, val origin: Offset, val size: IntSize)

/** PixelCopy reads only the Activity window. Floating UI lives in separate Popup windows. */
internal class MiniAppFloatingBackdrop : Backdrop {
    private var frame by mutableStateOf<WindowFrame?>(null)
    val ready get() = frame != null
    override val isCoordinatesDependent = true
    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        val frame = frame ?: return
        if (coordinates?.isAttached != true) return
        val position = frame.origin - coordinates.positionOnScreen()
        drawImage(frame.image, dstOffset = IntOffset(position.x.roundToInt(), position.y.roundToInt()), dstSize = frame.size)
    }
    fun clear() { frame = null }
    fun update(bitmap: Bitmap, origin: Offset, size: IntSize) { frame = WindowFrame(bitmap.asImageBitmap(), origin, size) }
}

@Composable
internal fun rememberMiniAppFloatingBackdrop(active: Boolean, glass: GlassMode): MiniAppFloatingBackdrop {
    val view = LocalView.current
    val activity = view.context.activity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val source = remember(activity) { MiniAppFloatingBackdrop() }
    LaunchedEffect(active, glass, activity, lifecycle) {
        if (!active || activity == null || glass == GlassMode.TRANSLUCENT || Build.VERSION.SDK_INT < 31) {
            source.clear(); return@LaunchedEffect
        }
        try {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val decor = activity.window.decorView
                var dirty = true
                val listener = ViewTreeObserver.OnDrawListener { dirty = true }
                decor.viewTreeObserver.addOnDrawListener(listener)
                var idle = 0
                try {
                    while (isActive) {
                        if ((dirty || ++idle >= 5) && decor.width > 0 && decor.height > 0 && decor.isAttachedToWindow) {
                            dirty = false; idle = 0
                            val size = IntSize(decor.width, decor.height)
                            val width = minOf(540, size.width)
                            val bitmap = Bitmap.createBitmap(width, (size.height.toFloat() * width / size.width).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            val location = IntArray(2).also(decor::getLocationOnScreen)
                            val success = suspendCancellableCoroutine { continuation ->
                                runCatching { PixelCopy.request(activity.window, bitmap, { result ->
                                    if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
                                }, Handler(Looper.getMainLooper())) }.onFailure { if (continuation.isActive) continuation.resume(false) }
                            }
                            if (success) source.update(bitmap, Offset(location[0].toFloat(), location[1].toFloat()), size)
                        }
                        delay(120)
                    }
                } finally {
                    if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener)
                    source.clear()
                }
            }
        } finally { source.clear() }
    }
    return source
}

@Composable
internal fun Modifier.floatingSurface(backdrop: Backdrop?, glass: GlassMode, shape: RoundedCornerShape): Modifier {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    val ready = (backdrop as? MiniAppFloatingBackdrop)?.ready ?: (backdrop != null)
    val canBlur = ready && glass != GlassMode.TRANSLUCENT && Build.VERSION.SDK_INT >= 31
    val light = color.luminance() > .5f
    return semantics { floatingMaterial = glass; floatingSample = ready && canBlur }.clip(shape)
        .then(if (!canBlur || backdrop == null) Modifier.background(color.copy(alpha = .92f)) else Modifier.drawBackdrop(
            backdrop, shape = { shape }, effects = {
                blur(18.dp.toPx())
                if (glass == GlassMode.LIQUID && Build.VERSION.SDK_INT >= 33) lens(10.dp.toPx(), 18.dp.toPx(), depthEffect = true)
            }, onDrawSurface = { drawRect(color.copy(alpha = if (light) .58f else .74f)) }))
}
