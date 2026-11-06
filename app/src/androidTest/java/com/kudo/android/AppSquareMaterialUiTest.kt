package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.apps.*
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppSquareMaterialUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val apps = (0..80).map {
        MiniAppEntry("01a1966a-f4a0-7b43-ad9c-${it.toString().padStart(12, '0')}", "应用 $it", null)
    }

    @Test fun aboutSamplesDirectorySurfaceAcrossThemesAndMaterials() = checkMaterial(about = true)
    @Test fun filterSamplesDirectorySurfaceAcrossThemesAndMaterials() = checkMaterial(about = false)

    private fun checkMaterial(about: Boolean) {
        var display by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        var source by mutableStateOf(Color.Blue)
        var state by mutableStateOf(AppSquareState(apps = apps, loading = false))
        compose.setContent { KudoTheme(display) {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = source)) {
                AppSquareScreen(state, {}, {}, {}, {}, {}, {}, showAbout = {
                    state = state.copy(about = AppSquareAboutState(it,
                        MiniAppDetails(it.id, "2048", "小游戏简介", null, "1.0.0", Member(1, "neo", "Neo", ""), 4.35, 7), false))
                }, dismissAbout = { state = state.copy(about = null) }, loggedIn = true)
            }
        } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (glass in GlassMode.entries) {
            compose.runOnIdle { display = display.copy(theme = theme, glass = glass); source = Color.Blue }
            if (about) compose.onNodeWithTag("app-${apps.first().id}").performTouchInput { longClick() }
            else compose.onNodeWithContentDescription("筛选应用").performClick()
            if (!about) compose.onNodeWithText("已收藏").assertIsDisplayed()
            val sheetNode = compose.onNodeWithTag("liquid-action-sheet").assertIsDisplayed()
            val layer = compose.onNodeWithTag("editor-tool-backdrop-source").fetchSemanticsNode().layoutInfo
            var parent = sheetNode.fetchSemanticsNode().layoutInfo.parentInfo
            while (parent != null) {
                assertNotSame("采样源不能包含弹窗", layer, parent)
                parent = parent.parentInfo
            }
            val sheet = sheetNode.fetchSemanticsNode().boundsInRoot
            val firstContent = compose.onNodeWithTag(if (about) "mini-app-about" else "apps-sort-hot").fetchSemanticsNode().boundsInRoot
            val sample = sheet.copy(bottom = firstContent.top - 3f)
            val blue = strongestChannel(sample, blue = true)
            compose.runOnIdle { source = Color.Red }
            val red = strongestChannel(sample, blue = false)
            println("AppSquare ${if (about) "about" else "filter"} $theme/$glass blue=$blue red=$red")
            val minimum = if (glass == GlassMode.TRANSLUCENT) .015f else .15f
            assertTrue("必须采样蓝色目录 $theme/$glass ($blue)", blue > minimum)
            assertTrue("必须刷新为红色目录 $theme/$glass ($red)", red > minimum)
            if (glass == GlassMode.TRANSLUCENT) {
                assertTrue("普通材质保持高遮盖率", blue < .15f && red < .15f)
            }
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(context.getExternalFilesDir(null), "apps-${if (about) "about" else "filter"}-$theme-$glass.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("modal-scrim").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        }
    }

    private fun strongestChannel(bounds: Rect, blue: Boolean): Float {
        compose.waitForIdle()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        var strongest = -1f
        for (y in bounds.top.toInt() + 6 until bounds.bottom.toInt() - 6 step 3)
            // Stay inside the rounded panel, excluding the uncovered scrim at its corners.
            for (x in (bounds.left + bounds.width * .25f).toInt() until (bounds.right - bounds.width * .25f).toInt() step 3) {
                val color = pixels[x, y]
                strongest = maxOf(strongest, if (blue) color.blue - color.red else color.red - color.blue)
            }
        return strongest
    }
}
