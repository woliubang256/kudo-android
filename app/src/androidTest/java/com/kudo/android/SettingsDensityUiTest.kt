package com.kudo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.kudo.android.data.DisplaySettings
import com.kudo.android.ui.account.SettingsScreen
import com.kudo.android.ui.components.CommunitySwitch
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsDensityUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun singleLineSettingUsesCompactHeight() {
        var density = 1f
        compose.setContent { KudoTheme(DisplaySettings()) {
            density = LocalDensity.current.density
            SettingsScreen(DisplaySettings(), {}, null, {}, {}, {})
        } }
        val row = compose.onNode(hasText("主题风格") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertEquals(52f, row.height / density, 1f)
    }

    @Test fun switchThumbGrowsAndShrinksDuringTransitionAndKeepsTouchTarget() {
        var checked by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var density = 1f
        compose.setContent { KudoTheme(DisplaySettings()) {
            density = LocalDensity.current.density
            Box(Modifier.background(Color.Black)) {
                CommunitySwitch(checked, { checked = it }, Modifier.testTag("switch"), enabled)
            }
        } }
        val control = compose.onNodeWithTag("switch")
        assertTrue(control.fetchSemanticsNode().boundsInRoot.height / density >= 48f)
        control.assertIsOff()
        var snapshot = 0
        fun whitePixels(): Int {
            val image = control.captureToImage()
            val dir = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
            java.io.File(dir, "switch-${snapshot++}.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val pixels = image.toPixelMap()
            return (0 until pixels.width).sumOf { x -> (0 until pixels.height).count { y ->
                val color = pixels[x, y]
                // The pressed-state indication can dim white during a paused animation.
                color.red > .65f && kotlin.math.abs(color.red - color.green) < .04f && kotlin.math.abs(color.green - color.blue) < .04f
            } }
        }
        val small = whitePixels()
        compose.mainClock.autoAdvance = false
        control.performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(96)
        val growing = whitePixels()
        compose.mainClock.advanceTimeBy(240)
        val large = whitePixels()
        assertTrue("滑块应在动画中逐渐增大: $small/$growing/$large", small < growing && growing < large)
        control.assertIsOn().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(96)
        val shrinking = whitePixels()
        assertTrue(shrinking in (small + 1) until large)
        compose.mainClock.advanceTimeBy(240)
        assertEquals(small.toFloat(), whitePixels().toFloat(), small * .06f)
        compose.runOnIdle { enabled = false }
        compose.mainClock.advanceTimeByFrame()
        control.assertIsNotEnabled().performClick()
        compose.runOnIdle { assertFalse(checked) }
        compose.mainClock.autoAdvance = true
    }
}
