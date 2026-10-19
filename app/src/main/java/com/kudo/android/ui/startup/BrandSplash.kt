package com.kudo.android.ui.startup

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.activity.ComponentActivity
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun ComponentActivity.installBrandSplash(restored: Boolean) {
    val splash = installSplashScreen()
    var playback: BrandSplashPlayback? = null
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) { playback?.finish() }
        override fun onDestroy(owner: LifecycleOwner) { playback?.finish() }
    })
    splash.setOnExitAnimationListener { provider ->
        if (restored || isFinishing || isDestroyed || !ValueAnimator.areAnimatorsEnabled() ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            provider.remove()
            return@setOnExitAnimationListener
        }
        val layer = provider.view
        val icon = provider.iconView
        if (icon.width <= 0 || icon.height <= 0) {
            provider.remove()
            return@setOnExitAnimationListener
        }
        val nextPlayback = BrandSplashPlayback(layer, icon) {
            provider.remove()
            playback = null
        }
        playback = nextPlayback
        nextPlayback.start()
    }
}

internal class BrandSplashPlayback(
    private val layer: View, private val icon: View, private val completed: () -> Unit,
) {
    private val drawable = BrandLaunchDrawable().apply { setBounds(visibleIconBounds(icon, layer)) }
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = BrandLaunchDurationMillis
        interpolator = LinearInterpolator()
    }
    private var finished = false
    val isRunning: Boolean get() = animator.isRunning

    fun start() {
        if (finished || animator.isStarted) return
        icon.visibility = View.INVISIBLE
        layer.overlay.add(drawable)
        animator.addUpdateListener { animation ->
            drawable.progress = animation.animatedValue as Float
            val elapsed = drawable.progress * BrandLaunchDurationMillis
            layer.alpha = 1f - ((elapsed - BrandLaunchFadeStartMillis) /
                (BrandLaunchDurationMillis - BrandLaunchFadeStartMillis)).coerceIn(0f, 1f)
        }
        animator.doOnEnd { finish() }
        animator.start()
    }

    fun finish() {
        if (finished) return
        finished = true
        animator.removeAllUpdateListeners()
        animator.removeAllListeners()
        animator.cancel()
        layer.overlay.remove(drawable)
        completed()
    }
}

private fun visibleIconBounds(icon: View, layer: View): Rect {
    val iconPosition = IntArray(2)
    val layerPosition = IntArray(2)
    icon.getLocationOnScreen(iconPosition)
    layer.getLocationOnScreen(layerPosition)
    val left = iconPosition[0] - layerPosition[0]
    val top = iconPosition[1] - layerPosition[1]
    val fallback = Rect(left, top, left + icon.width, top + icon.height)
    if (icon.width <= 0 || icon.height <= 0) return fallback
    val scale = min(1f, 512f / max(icon.width, icon.height))
    val bitmap = Bitmap.createBitmap(ceil(icon.width * scale).toInt(), ceil(icon.height * scale).toInt(), Bitmap.Config.ARGB_8888)
    try {
        icon.draw(Canvas(bitmap).apply { scale(scale, scale) })
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val visible = Rect(bitmap.width, bitmap.height, 0, 0)
        pixels.forEachIndexed { index, color ->
            if (color ushr 24 > 16) {
                val column = index % bitmap.width
                val row = index / bitmap.width
                visible.left = min(visible.left, column)
                visible.top = min(visible.top, row)
                visible.right = max(visible.right, column + 1)
                visible.bottom = max(visible.bottom, row + 1)
            }
        }
        return if (visible.isEmpty) fallback else Rect(
            left + (visible.left / scale).roundToInt(), top + (visible.top / scale).roundToInt(),
            left + (visible.right / scale).roundToInt(), top + (visible.bottom / scale).roundToInt(),
        )
    } finally {
        bitmap.recycle()
    }
}
