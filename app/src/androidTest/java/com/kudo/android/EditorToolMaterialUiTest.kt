package com.kudo.android

import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.editor.*
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

class EditorToolMaterialUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun window() { compose.runOnUiThread {
        compose.activity.enableEdgeToEdge()
        compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    } }

    @Test fun toolSheetSamplesLivePageInBothThemesAndAllMaterials() {
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        var source by mutableStateOf(Color(0xFFB6314D))
        compose.setContent { KudoTheme(settings) { EditorToolBackdropHost {
            Box(Modifier.fillMaxSize().background(source)) {
                EditorToolSheet("工具材质", {}, showTitle = false) { Spacer(Modifier.fillMaxWidth().height(200.dp)) }
            }
        } } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            for (mode in GlassMode.entries) {
                compose.runOnIdle { settings = settings.copy(theme = theme, glass = mode); source = Color(0xFFB6314D) }
                val red = sampleSheet()
                compose.runOnIdle { source = Color(0xFF2564C3) }
                val blue = sampleSheet()
                val difference = abs(red.red - blue.red) + abs(red.blue - blue.blue)
                if (mode == GlassMode.TRANSLUCENT || Build.VERSION.SDK_INT < 31)
                    assertTrue("$theme/$mode must retain the translucent fallback: $difference", difference in .02f.. .15f)
                else assertTrue("$theme/$mode must sample the actual page: $difference", difference > .2f)
                screenshot("editor-tool-${theme.name.lowercase()}-${mode.name.lowercase()}.png").recycle()
            }
        }
    }

    @Test fun emojiHandleSearchUnselectedTabsAndGridShareOneSurface() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { KudoTheme(DisplaySettings(theme = theme, glass = GlassMode.BLUR)) { EditorToolBackdropHost {
            Box(Modifier.fillMaxSize().background(Color(0xFF6A3B8D))) {
                EditorToolSheet("表情", {}, showTitle = false) { EditorEmojis {} }
            }
        } } }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { theme = mode }
            compose.onNodeWithTag("editor-emoji-tab-0").performSemanticsAction(SemanticsActions.OnClick) { it() }
            val sheet = compose.onNodeWithTag("liquid-action-sheet").fetchSemanticsNode().boundsInRoot
            val search = compose.onNodeWithTag("editor-emoji-search").fetchSemanticsNode().boundsInRoot
            val handle = compose.onNodeWithTag("sheet-drag-handle").fetchSemanticsNode().boundsInRoot
            val tab = compose.onNodeWithTag("editor-emoji-tab-recent").fetchSemanticsNode().boundsInRoot
            val cell = compose.onNodeWithContentDescription(":smile:").fetchSemanticsNode().boundsInRoot
            val bitmap = screenshot("editor-emoji-unified-${mode.name.lowercase()}.png")
            try {
                val expected = Color(bitmap.getPixel((sheet.left + 12).toInt(), search.center.y.toInt()))
                val samples = listOf(handle.center.copy(x = handle.center.x + 100),
                    tab.topCenter.copy(y = tab.top + 3), cell.topCenter.copy(y = cell.top + 3))
                for (point in samples) {
                    val actual = Color(bitmap.getPixel(point.x.toInt(), point.y.toInt()))
                    assertEquals("$mode red at $point", expected.red, actual.red, .015f)
                    assertEquals("$mode green at $point", expected.green, actual.green, .015f)
                    assertEquals("$mode blue at $point", expected.blue, actual.blue, .015f)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun toolSheetKeepsFullWindowPageCoordinatesAndBlursStripeBoundaries() {
        var stripeHeight = 0f
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT, glass = GlassMode.BLUR)) { EditorToolBackdropHost {
            stripeHeight = with(LocalDensity.current) { 64.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                repeat((size.height / stripeHeight).toInt() + 1) { index ->
                    drawRect(if (index % 2 == 0) Color(0xFFB6314D) else Color(0xFF2564C3),
                        Offset(0f, index * stripeHeight), Size(size.width, stripeHeight))
                }
            }
            EditorToolSheet("坐标检查", {}, showTitle = false) { Spacer(Modifier.fillMaxWidth().height(240.dp)) }
        } } }
        val sheet = compose.onNodeWithTag("liquid-action-sheet").fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("sheet-drag-handle").fetchSemanticsNode().boundsInRoot
        val bitmap = screenshot("editor-tool-window-coordinates.png")
        var samples = 0
        try {
            for (index in 0..(bitmap.height / stripeHeight).toInt()) {
                val sampleY = ((index + .5f) * stripeHeight).toInt()
                if (sampleY <= handle.bottom + 20 || sampleY >= sheet.bottom - 30) continue
                val color = Color(bitmap.getPixel(sheet.center.x.toInt(), sampleY))
                if (index % 2 == 0) assertTrue("red stripe at y=$sampleY: $color", color.red - color.blue > .08f)
                else assertTrue("blue stripe at y=$sampleY: $color", color.blue - color.red > .08f)
                samples++
            }
            assertTrue("must check more than one page stripe", samples >= 2)
            var boundaries = 0
            for (index in 1..(bitmap.height / stripeHeight).toInt()) {
                val sampleY = (index * stripeHeight).toInt()
                if (sampleY <= handle.bottom + 20 || sampleY >= sheet.bottom - 30) continue
                val above = Color(bitmap.getPixel(sheet.center.x.toInt(), sampleY - 2))
                val below = Color(bitmap.getPixel(sheet.center.x.toInt(), sampleY + 2))
                val difference = abs(above.red - below.red) + abs(above.blue - below.blue)
                assertTrue("stripe boundary must be blurred, not window transparency: $difference", difference < .06f)
                boundaries++
            }
            assertTrue("must check a blurred page boundary", boundaries > 0)
        } finally { bitmap.recycle() }
    }

    @Test fun sourceExistsOnlyWhileToolsAreOpenAndExcludesThePanel() {
        var showing by mutableStateOf(false)
        compose.setContent { KudoTheme(DisplaySettings()) { EditorToolBackdropHost {
            Box(Modifier.fillMaxSize().background(Color(0xFF2564C3)))
            if (showing) EditorToolSheet("开关检查", { showing = false }) { Spacer(Modifier.fillMaxWidth().height(160.dp)) }
        } } }
        compose.onNodeWithTag("editor-tool-backdrop-source").assertDoesNotExist()
        repeat(4) { index ->
            compose.runOnIdle { showing = true }
            compose.onNodeWithTag("liquid-action-sheet").assertIsDisplayed()
            val source = compose.onNodeWithTag("editor-tool-backdrop-source").fetchSemanticsNode().layoutInfo
            var parent = compose.onNodeWithTag("liquid-action-sheet").fetchSemanticsNode().layoutInfo.parentInfo
            while (parent != null) {
                assertNotSame("the sampled layer must never contain the panel", source, parent)
                parent = parent.parentInfo
            }
            if (index % 2 == 0) compose.onNodeWithTag("sheet-drag-handle").performTouchInput {
                swipe(center, center + Offset(0f, 650f), 600)
            } else compose.onNodeWithTag("modal-scrim").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("editor-tool-backdrop-source").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        }
    }

    @Test fun toolKeepsCallerLocalsAndDisposesWithItsOwner() {
        val local = staticCompositionLocalOf { "root" }
        var label by mutableStateOf("caller")
        var owner by mutableStateOf(true)
        compose.setContent { KudoTheme(DisplaySettings()) { EditorToolBackdropHost {
            CompositionLocalProvider(local provides label) {
                if (owner) EditorToolSheet("上下文检查", {}) { Text(local.current) }
            }
        } } }
        compose.onNodeWithText("caller").assertIsDisplayed()
        compose.runOnIdle { label = "updated caller" }
        compose.onNodeWithText("updated caller").assertIsDisplayed()
        compose.runOnIdle { owner = false }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithTag("editor-tool-backdrop-source").assertDoesNotExist()
    }

    private fun sampleSheet(): Color {
        val bounds = compose.onNodeWithTag("liquid-action-sheet").fetchSemanticsNode().boundsInRoot
        val bitmap = screenshot()
        return try { Color(bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())) }
        finally { bitmap.recycle() }
    }

    private fun screenshot(name: String? = null): Bitmap {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        if (name != null) File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }
}
