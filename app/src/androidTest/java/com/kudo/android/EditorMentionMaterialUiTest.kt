package com.kudo.android

import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

class EditorMentionMaterialUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val users = (1..6).map { Member(it, "member$it", "用户$it", "") }

    @Before fun window() { compose.runOnUiThread {
        compose.activity.enableEdgeToEdge()
        compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    } }

    @Composable private fun Field() {
        var value by remember { mutableStateOf(TextFieldValue()) }
        Column(Modifier.fillMaxSize().imePadding().padding(20.dp)) {
            Spacer(Modifier.weight(1f))
            EditorMentionField(value, { value = it }, { users },
                Modifier.fillMaxWidth().height(80.dp).testTag("material-mention-input"))
        }
    }

    private fun open() {
        compose.onNodeWithTag("material-mention-input").performClick().performTextReplacement("")
        compose.onNodeWithTag("material-mention-input").performTextReplacement("@me")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("mention-user-member1").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String? = null): Bitmap {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        if (name != null) File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    private fun sample(): Color {
        val panel = compose.onNodeWithTag("mention-suggestions").fetchSemanticsNode()
        val bitmap = screenshot()
        return try { Color(bitmap.getPixel((panel.positionOnScreen.x + 10).toInt(),
            (panel.positionOnScreen.y + panel.size.height / 2f).toInt())) }
        finally { bitmap.recycle() }
    }

    @Test fun candidatesSampleLiveBackgroundInBothThemesAndAllMaterials() {
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        var source by mutableStateOf(Color(0xFFB6314D))
        compose.setContent { KudoTheme(settings) { EditorToolBackdropHost {
            Box(Modifier.fillMaxSize().background(source)) { Field() }
        } } }
        open()
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (mode in GlassMode.entries) {
            compose.runOnIdle { settings = settings.copy(theme = theme, glass = mode); source = Color(0xFFB6314D) }
            val red = sample()
            compose.runOnIdle { source = Color(0xFF2564C3) }
            val blue = sample()
            val difference = abs(red.red - blue.red) + abs(red.blue - blue.blue)
            if (mode == GlassMode.TRANSLUCENT || Build.VERSION.SDK_INT < 31)
                assertTrue("$theme/$mode translucent background difference: $difference", difference in .02f.. .15f)
            else assertTrue("$theme/$mode must sample the background without strong colour bleed: $difference", difference in .04f.. .2f)
            compose.onNodeWithTag("material-mention-input").assertIsFocused().assertTextEquals("@me")
            assertTrue(ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            screenshot("mention-material-${theme.name.lowercase()}-${mode.name.lowercase()}.png").recycle()
        }
        compose.onNodeWithTag("mention-user-member1").performClick()
        compose.onNodeWithTag("material-mention-input").assertIsFocused().assertTextEquals("@member1 ")
    }

    @Test fun blurKeepsWindowStripeCoordinatesAndSoftensTheirBoundaries() {
        if (Build.VERSION.SDK_INT < 31) return
        var stripe = 0f
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT, glass = GlassMode.BLUR)) { EditorToolBackdropHost {
            stripe = with(LocalDensity.current) { 64.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                repeat((size.height / stripe).toInt() + 1) { index ->
                    drawRect(if (index % 2 == 0) Color(0xFFB6314D) else Color(0xFF2564C3),
                        Offset(0f, index * stripe), Size(size.width, stripe))
                }
            }
            Field()
        } } }
        open()
        val panel = compose.onNodeWithTag("mention-suggestions").fetchSemanticsNode()
        val top = panel.positionOnScreen.y
        val bottom = top + panel.size.height
        val sampleX = (panel.positionOnScreen.x + panel.size.width - 30).toInt()
        val bitmap = screenshot("mention-material-stripes.png")
        var centers = 0
        var boundaries = 0
        try {
            for (index in 0..(bitmap.height / stripe).toInt()) {
                val y = ((index + .5f) * stripe).toInt()
                if (y <= top + 30 || y >= bottom - 30) continue
                val color = Color(bitmap.getPixel(sampleX, y))
                if (index % 2 == 0) assertTrue("red stripe at $y: $color", color.red - color.blue > .025f)
                else assertTrue("blue stripe at $y: $color", color.blue - color.red > .025f)
                centers++
            }
            for (index in 1..(bitmap.height / stripe).toInt()) {
                val y = (index * stripe).toInt()
                if (y <= top + 30 || y >= bottom - 30) continue
                val above = Color(bitmap.getPixel(sampleX, y - 2))
                val below = Color(bitmap.getPixel(sampleX, y + 2))
                val difference = abs(above.red - below.red) + abs(above.blue - below.blue)
                assertTrue("Stripe edge must be blurred: $difference", difference < .035f)
                boundaries++
            }
            assertTrue("Multiple window stripes must be visible", centers >= 2)
            assertTrue("A stripe boundary must be checked", boundaries > 0)
        } finally { bitmap.recycle() }
    }

    @Test fun liquidSettingUsesTheSameQuietFrostedPanelAsBlurWithoutARefractedRim() {
        if (Build.VERSION.SDK_INT < 31) return
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT, glass = GlassMode.BLUR))
        compose.setContent { KudoTheme(settings) { EditorToolBackdropHost {
            val stripe = with(LocalDensity.current) { 48.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                repeat((size.height / stripe).toInt() + 1) { index ->
                    drawRect(if (index % 2 == 0) Color(0xFFB6314D) else Color(0xFF2564C3),
                        Offset(0f, index * stripe), Size(size.width, stripe))
                }
            }
            Field()
        } } }
        open()
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { settings = settings.copy(theme = theme, glass = GlassMode.BLUR) }
            val panel = compose.onNodeWithTag("mention-suggestions").fetchSemanticsNode()
            val plain = screenshot()
            compose.runOnIdle { settings = settings.copy(glass = GlassMode.LIQUID) }
            val liquid = screenshot("mention-frosted-${theme.name.lowercase()}.png")
            var maximumDifference = 0f
            try {
                val x = (panel.positionOnScreen.x + panel.size.width - 12).toInt()
                val top = (panel.positionOnScreen.y + 60).toInt()
                val bottom = (panel.positionOnScreen.y + panel.size.height - 60).toInt()
                for (y in top until bottom) {
                    val a = Color(plain.getPixel(x, y))
                    val b = Color(liquid.getPixel(x, y))
                    maximumDifference = maxOf(maximumDifference, abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue))
                }
                assertTrue("$theme candidate rim must not gain a liquid highlight or refraction: $maximumDifference", maximumDifference < .025f)
            } finally { plain.recycle(); liquid.recycle() }
        }
    }

    @Test fun aUniformBackdropHasNoGreyInsetFrameInsideTheCandidatePanel() {
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        compose.setContent { KudoTheme(settings) { EditorToolBackdropHost {
            Box(Modifier.fillMaxSize().background(Color(0xFF2564C3))) { Field() }
        } } }
        open()
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (mode in GlassMode.entries) {
            compose.runOnIdle { settings = settings.copy(theme = theme, glass = mode) }
            val panel = compose.onNodeWithTag("mention-suggestions").fetchSemanticsNode()
            val bitmap = screenshot("mention-no-inset-${theme.name.lowercase()}-${mode.name.lowercase()}.png")
            try {
                val edgeX = (panel.positionOnScreen.x + panel.size.width - 12).toInt()
                val innerX = edgeX - 90
                for (fraction in listOf(.3f, .5f, .7f)) {
                    val y = (panel.positionOnScreen.y + panel.size.height * fraction).toInt()
                    val edge = Color(bitmap.getPixel(edgeX, y))
                    val inner = Color(bitmap.getPixel(innerX, y))
                    val difference = abs(edge.red - inner.red) + abs(edge.green - inner.green) + abs(edge.blue - inner.blue)
                    assertTrue("$theme/$mode must not draw an inset grey frame at $fraction: $difference", difference < .035f)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun sourceExcludesCandidatesAndReleasesOnOutsideClickOrOwnerDisposal() {
        var owner by mutableStateOf(true)
        var clicks = 0
        compose.setContent { KudoTheme(DisplaySettings()) { EditorToolBackdropHost {
            Box(Modifier.fillMaxSize().background(Color(0xFF2564C3))) {
                if (owner) Field()
                Text("外部按钮", Modifier.padding(top = 50.dp).clickable { clicks++ }.padding(20.dp).testTag("outside-mention"))
            }
        } } }
        compose.onNodeWithTag("editor-tool-backdrop-source").assertDoesNotExist()
        open()
        val source = compose.onNodeWithTag("editor-tool-backdrop-source").fetchSemanticsNode().layoutInfo
        var parent = compose.onNodeWithTag("mention-suggestions").fetchSemanticsNode().layoutInfo.parentInfo
        while (parent != null) {
            assertNotSame("The source must not contain candidates", source, parent)
            parent = parent.parentInfo
        }
        compose.onNodeWithTag("material-mention-input").assertIsFocused()
        compose.onNodeWithTag("outside-mention").performClick()
        assertEquals(1, clicks)
        compose.onNodeWithTag("mention-suggestions").assertDoesNotExist()
        compose.onNodeWithTag("editor-tool-backdrop-source").assertDoesNotExist()
        open()
        compose.runOnIdle { owner = false }
        compose.onNodeWithTag("mention-suggestions").assertDoesNotExist()
        compose.onNodeWithTag("editor-tool-backdrop-source").assertDoesNotExist()
    }
}
