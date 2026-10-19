package com.kudo.android

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.ui.startup.BrandLaunchDrawable
import com.kudo.android.ui.startup.BrandLaunchDurationMillis
import com.kudo.android.ui.startup.BrandLaunchFadeStartMillis
import com.kudo.android.ui.startup.BrandSplashPlayback
import com.kudo.android.ui.startup.brandLaunchLayerAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BrandSplashTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun preservesTheOriginalMorphThenFadesForTheLast150ms() {
        assertEquals(900L, BrandLaunchDurationMillis)
        assertEquals(750L, BrandLaunchFadeStartMillis)
        assertEquals(150L, BrandLaunchDurationMillis - BrandLaunchFadeStartMillis)
    }

    @Test fun splashLayerRemainsOpaqueUntil750msThenFadesLinearly() {
        for (elapsed in listOf(0f, 60f, 545f, 665f, 749f, 750f)) {
            assertEquals("The whole splash layer must stay opaque at ${elapsed}ms",
                1f, brandLaunchLayerAlpha(elapsed), .0001f)
        }
        assertEquals(.75f, brandLaunchLayerAlpha(787.5f), .0001f)
        assertEquals(.5f, brandLaunchLayerAlpha(825f), .0001f)
        assertEquals(.25f, brandLaunchLayerAlpha(862.5f), .0001f)
        assertEquals(0f, brandLaunchLayerAlpha(900f), .0001f)
        assertEquals(0f, brandLaunchLayerAlpha(1000f), .0001f)
    }

    @Test fun normalizedSystemIconDoesNotShrinkAtTheHandoff() {
        instrumentation.runOnMainSync {
            val nativeFrame = Bitmap.createBitmap(Size, Size, Bitmap.Config.ARGB_8888)
            requireNotNull(context.getDrawable(R.drawable.ic_linux_do)).apply {
                setBounds(54, 54, 378, 378)
            }.draw(Canvas(nativeFrame))
            val layer = FrameLayout(context).apply { layout(0, 0, Size, Size) }
            val icon = ImageView(context).apply {
                setImageDrawable(BitmapDrawable(context.resources, nativeFrame))
                layout(0, 0, Size, Size)
            }
            layer.addView(icon)
            val playback = BrandSplashPlayback(layer, icon) {}
            try {
                playback.start()
                val firstCustomFrame = Bitmap.createBitmap(Size, Size, Bitmap.Config.ARGB_8888)
                layer.draw(Canvas(firstCustomFrame))
                assertSimilar(nativeFrame, firstCustomFrame, "The normalized native logo must not shrink when the overlay takes over")
            } finally {
                playback.finish()
            }
        }
    }

    @Test fun initialFrameIsTheExistingLinuxDoLogo() {
        val expected = render(requireNotNull(context.getDrawable(R.drawable.ic_launch_linux_do)))
        assertSimilar(expected, render(BrandLaunchDrawable()), "The first frame must be LinuxDO, not the launcher icon")
        assertSimilar(expected, render(BrandLaunchDrawable().apply { progress = 59f / BrandLaunchDurationMillis }),
            "The complete source logo must remain for the opening beat")
    }

    @Test fun finalFrameIsTheExistingKudoLogo() {
        val expected = Bitmap.createBitmap(Size, Size, Bitmap.Config.ARGB_8888)
        requireNotNull(context.getDrawable(R.drawable.ic_kudo)).apply { setBounds(108, 108, 324, 324) }
            .draw(Canvas(expected))
        assertSimilar(expected, render(BrandLaunchDrawable().apply { progress = 1f }),
            "The assembled K, dot and rounded background must match the actual app logo")
        assertEquals(900L, BrandLaunchDurationMillis)
    }

    @Test fun yellowRisesAndWhiteGathersInPlace() {
        val first = render(BrandLaunchDrawable())
        val middle = render(BrandLaunchDrawable().apply { progress = 280f / BrandLaunchDurationMillis })
        val final = render(BrandLaunchDrawable().apply { progress = 545f / BrandLaunchDurationMillis })
        val yellow: (Int) -> Boolean = { Color.red(it) > 240 && Color.green(it) in 155..195 && Color.blue(it) < 20 }
        val white: (Int) -> Boolean = { Color.red(it) > 220 && Color.green(it) > 220 && Color.blue(it) > 220 }
        assertTrue(coloredBounds(first, yellow).top > coloredBounds(middle, yellow).top)
        assertTrue(coloredBounds(middle, yellow).top > coloredBounds(final, yellow).top)
        assertTrue("The white region must shrink rather than fly away", colorCount(first, white) > colorCount(middle, white))
        val dot = coloredBounds(final, white)
        assertEquals(Rect(236, 234, 272, 270), dot)
        assertTrue("The white band must continuously gather towards the bottom-right dot",
            coloredBounds(middle, white).centerX() < dot.centerX())
        assertSimilar(render(BrandLaunchDrawable().apply { progress = 750f / BrandLaunchDurationMillis }), final,
            "The completed logo must hold still before the splash layer fades")
    }

    @Test fun morphStaysInBoundsAndDoesNotFadeWholeIcons() {
        val drawable = BrandLaunchDrawable()
        for (elapsed in 0..BrandLaunchDurationMillis.toInt() step 10) {
            val frame = render(drawable.apply { progress = elapsed.toFloat() / BrandLaunchDurationMillis })
            val occupied = coloredBounds(frame) { true }
            assertTrue("No piece may clip at ${elapsed}ms: $occupied",
                occupied.left >= 108 && occupied.top >= 108 && occupied.right <= 324 && occupied.bottom <= 324)
            var opaque = 0
            var translucent = 0
            for (row in 0 until Size) for (column in 0 until Size) {
                val alpha = Color.alpha(frame.getPixel(column, row))
                if (alpha == 255) opaque++ else if (alpha > 0) translucent++
            }
            assertTrue("Shape interiors must remain opaque, not whole-icon crossfades at ${elapsed}ms",
                opaque > 500 && translucent < opaque / 4)
        }
    }

    @Test fun launchThemeDoesNotReplaceTheLauncherIcon() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(R.drawable.ic_kudo, info.applicationInfo.icon)
        assertEquals(R.style.Theme_Kudo_Starting, info.themeResource)
        val theme = ContextThemeWrapper(context, info.themeResource).theme
        val resolved = TypedValue()
        assertTrue(theme.resolveAttribute(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon, resolved, true))
        assertEquals(R.drawable.ic_launch_linux_do, resolved.resourceId)
        assertTrue(theme.resolveAttribute(androidx.core.splashscreen.R.attr.postSplashScreenTheme, resolved, true))
        assertEquals(R.style.Theme_Kudo, resolved.resourceId)
    }

    @Test fun completionReleasesTheLayerOnlyOnce() {
        val finished = CountDownLatch(1)
        val completions = AtomicInteger()
        lateinit var playback: BrandSplashPlayback
        instrumentation.runOnMainSync {
            playback = BrandSplashPlayback(FrameLayout(context), sourceIcon()) {
                completions.incrementAndGet()
                finished.countDown()
            }
            playback.start()
        }
        assertTrue("The 900ms animation must finish without waiting for network", finished.await(3, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { playback.finish(); assertFalse(playback.isRunning) }
        assertEquals(1, completions.get())
    }

    @Test fun cancellationCannotRestartOrRetainTheStartupLayer() {
        val completions = AtomicInteger()
        instrumentation.runOnMainSync {
            val playback = BrandSplashPlayback(FrameLayout(context), sourceIcon()) { completions.incrementAndGet() }
            playback.start()
            playback.finish()
            playback.finish()
            playback.start()
            assertFalse(playback.isRunning)
        }
        Thread.sleep(1000)
        assertEquals("Stop/destroy and a late animation callback must not finish twice", 1, completions.get())
    }

    @Test fun saveTheDesignedMotionFrames() {
        val moments = listOf(0, 100, 180, 260, 340, 420, 500, 545, 900)
        val sheet = Bitmap.createBitmap(Size * 3, Size * 3, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(16, 17, 19))
        moments.forEachIndexed { index, elapsed ->
            canvas.drawBitmap(render(BrandLaunchDrawable().apply { progress = elapsed.toFloat() / BrandLaunchDurationMillis }),
                (index % 3 * Size).toFloat(), (index / 3 * Size).toFloat(), null)
        }
        File(context.getExternalFilesDir(null), "brand-launch-morph.png").outputStream().use {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun sourceIcon() = ImageView(context).apply {
        setImageResource(R.drawable.ic_launch_linux_do)
        layout(0, 0, Size, Size)
    }

    private fun render(drawable: Drawable): Bitmap = Bitmap.createBitmap(Size, Size, Bitmap.Config.ARGB_8888).also {
        if (drawable is BrandLaunchDrawable) drawable.setBounds(108, 108, 324, 324)
        else drawable.setBounds(0, 0, Size, Size)
        drawable.draw(Canvas(it))
    }

    private fun assertSimilar(expected: Bitmap, actual: Bitmap, message: String) {
        var different = 0
        for (row in 0 until Size) for (column in 0 until Size) {
            if (expected.getPixel(column, row) != actual.getPixel(column, row)) different++
        }
        assertTrue("$message; different pixels=$different", different < Size * Size / 200)
    }

    private fun coloredBounds(bitmap: Bitmap, matches: (Int) -> Boolean): Rect {
        val bounds = Rect(Size, Size, 0, 0)
        for (row in 0 until Size) for (column in 0 until Size) {
            val color = bitmap.getPixel(column, row)
            if (Color.alpha(color) > 240 && matches(color)) {
                bounds.left = minOf(bounds.left, column)
                bounds.top = minOf(bounds.top, row)
                bounds.right = maxOf(bounds.right, column + 1)
                bounds.bottom = maxOf(bounds.bottom, row + 1)
            }
        }
        assertTrue("The requested piece must remain visible", !bounds.isEmpty)
        return bounds
    }

    private fun colorCount(bitmap: Bitmap, matches: (Int) -> Boolean): Int {
        val pixels = IntArray(Size * Size)
        bitmap.getPixels(pixels, 0, Size, 0, 0, Size, Size)
        return pixels.count { Color.alpha(it) > 240 && matches(it) }
    }

    private companion object { const val Size = 432 }
}
