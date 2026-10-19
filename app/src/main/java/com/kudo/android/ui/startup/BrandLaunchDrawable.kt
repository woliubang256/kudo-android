package com.kudo.android.ui.startup

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import kotlin.math.abs
import kotlin.math.min

internal const val BrandLaunchDurationMillis = 900L
internal const val BrandLaunchFadeStartMillis = 750L

internal class BrandLaunchDrawable : Drawable() {
    var progress = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidateSelf() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var opacity = 255
    private val white = Path().apply { addCircle(54f, 54f, 54f, Path.Direction.CW) }
    private val black = linuxPath("M71.5,260A421.5,421.5 0,0 1,824.5 260Z")
    private val yellow = linuxPath("M71.5,636L824.5,636A421.5,421.5 0,0 1,71.5 636Z")
    private val base = path("M24,0L84,0Q108,0 108,24L108,84Q108,108 84,108L24,108Q0,108 0,84L0,24Q0,0 24,0Z")
    private val letter = path("M26,27L37,27L37,48L54,27L68,27L48,52L69,81L55,81L37,57L37,81L26,81Z")
    private val dot = Path().apply { addCircle(73f, 72f, 9f, Path.Direction.CW) }
    private val band = Path(white).apply {
        op(Path().apply { addRect(0f, 260f * 108f / 896f, 108f, 636f * 108f / 896f, Path.Direction.CW) }, Path.Op.INTERSECT)
    }
    private val silhouette = ContourMorph(white, base)
    private val risingYellow = ContourMorph(yellow, base)
    private val formingLetter = ContourMorph(black, letter)
    private val gatheringWhite = ContourMorph(band, dot)
    private val easing = PathInterpolator(.4f, 0f, .2f, 1f)

    override fun draw(canvas: Canvas) {
        val elapsed = progress * BrandLaunchDurationMillis
        val scale = min(bounds.width(), bounds.height()) / 108f
        canvas.save()
        canvas.translate(bounds.exactCenterX(), bounds.exactCenterY())
        canvas.scale(scale, scale)
        canvas.translate(-54f, -54f)
        if (elapsed <= 60f) {
            color(0xFFF0F0F0.toInt()); canvas.drawPath(white, paint)
            color(0xFFFFB003.toInt()); canvas.drawPath(yellow, paint)
            color(0xFF1C1C1C.toInt()); canvas.drawPath(black, paint)
            canvas.restore()
            return
        }
        if (elapsed >= 545f) {
            color(0xFFFFB003.toInt()); canvas.drawPath(base, paint)
            color(0xFF1C1C1E.toInt()); canvas.drawPath(letter, paint)
            color(Color.WHITE); canvas.drawPath(dot, paint)
            canvas.restore()
            return
        }
        val outline = silhouette.at(amount(elapsed, 60f, 490f))
        color(0xFFF0F0F0.toInt())
        canvas.drawPath(outline, paint)
        canvas.save()
        canvas.clipPath(outline)
        color(0xFFFFB003.toInt())
        canvas.drawPath(risingYellow.at(amount(elapsed, 60f, 460f)), paint)
        color(ColorUtils.blendARGB(0xFF1C1C1C.toInt(), 0xFF1C1C1E.toInt(), amount(elapsed, 100f, 545f)))
        canvas.drawPath(formingLetter.at(amount(elapsed, 100f, 545f)), paint)
        color(ColorUtils.blendARGB(0xFFF0F0F0.toInt(), Color.WHITE, amount(elapsed, 60f, 535f)))
        canvas.drawPath(gatheringWhite.at(amount(elapsed, 60f, 535f)), paint)
        canvas.restore()
        canvas.restore()
    }

    private fun amount(elapsed: Float, start: Float, end: Float): Float =
        easing.getInterpolation(((elapsed - start) / (end - start)).coerceIn(0f, 1f))

    private fun color(value: Int) { paint.color = value; paint.alpha = opacity }
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha(): Int = opacity
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private class ContourMorph(private val source: Path, private val target: Path) {
        private val sourceCenter = center(source)
        private val targetCenter = center(target)
        private val sourcePoints = points(source, sourceCenter)
        private val targetPoints = align(sourcePoints, points(target, targetCenter))
        private val outline = Path()

        fun at(amount: Float): Path {
            if (amount <= 0f) return source
            if (amount >= 1f) return target
            val centerX = mix(sourceCenter.x, targetCenter.x, amount)
            val centerY = mix(sourceCenter.y, targetCenter.y, amount)
            outline.rewind()
            for (index in 0 until SampleCount) {
                val positionX = centerX + mix(sourcePoints[index * 2], targetPoints[index * 2], amount)
                val positionY = centerY + mix(sourcePoints[index * 2 + 1], targetPoints[index * 2 + 1], amount)
                if (index == 0) outline.moveTo(positionX, positionY) else outline.lineTo(positionX, positionY)
            }
            outline.close()
            return outline
        }
    }

    private companion object {
        const val SampleCount = 128
        fun path(data: String): Path = requireNotNull(PathParser.createPathFromPathData(data))
        fun linuxPath(data: String): Path = path(data).apply {
            transform(Matrix().apply { setScale(108f / 896f, 108f / 896f) })
        }
        fun center(path: Path): PointF {
            val bounds = RectF()
            path.computeBounds(bounds, true)
            return PointF(bounds.centerX(), bounds.centerY())
        }
        fun points(path: Path, center: PointF): FloatArray {
            val measure = PathMeasure(path, true)
            val position = FloatArray(2)
            return FloatArray(SampleCount * 2).also { samples ->
                repeat(SampleCount) { index ->
                    measure.getPosTan(measure.length * index / SampleCount, position, null)
                    samples[index * 2] = position[0] - center.x
                    samples[index * 2 + 1] = position[1] - center.y
                }
            }
        }
        fun align(source: FloatArray, destination: FloatArray): FloatArray {
            val sourceRadius = source.maxOf { abs(it) }.coerceAtLeast(1f)
            val targetRadius = destination.maxOf { abs(it) }.coerceAtLeast(1f)
            var bestCost = Float.POSITIVE_INFINITY
            var bestOffset = 0
            var bestDirection = 1
            for (direction in listOf(1, -1)) for (offset in 0 until SampleCount) {
                var cost = 0f
                repeat(SampleCount) { index ->
                    val targetIndex = Math.floorMod(offset + direction * index, SampleCount)
                    val deltaX = source[index * 2] / sourceRadius - destination[targetIndex * 2] / targetRadius
                    val deltaY = source[index * 2 + 1] / sourceRadius - destination[targetIndex * 2 + 1] / targetRadius
                    cost += deltaX * deltaX + deltaY * deltaY
                }
                if (cost < bestCost) { bestCost = cost; bestOffset = offset; bestDirection = direction }
            }
            return FloatArray(SampleCount * 2).also { aligned ->
                repeat(SampleCount) { index ->
                    val targetIndex = Math.floorMod(bestOffset + bestDirection * index, SampleCount)
                    aligned[index * 2] = destination[targetIndex * 2]
                    aligned[index * 2 + 1] = destination[targetIndex * 2 + 1]
                }
            }
        }
        fun mix(start: Float, end: Float, amount: Float): Float = start + (end - start) * amount
    }
}
