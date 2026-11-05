package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.apps.*
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppSquareListUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = MiniAppEntry("01a1966a-f4a0-7b43-ad9c-c46b7f0e0281", "超级马里奥兄弟", null, 4.35, 123, 56789)
    private var display by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))

    @Test fun statisticsStayAtTheRightInTwoRowsAcrossThemesAndLargeFonts() {
        compose.setContent { KudoTheme(display) { Box(Modifier.width(320.dp)) {
            AppSquareScreen(AppSquareState(apps = listOf(app), loading = false), {}, {}, {}, {}, {}, {}, initialGrid = false)
        } } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (scale in listOf(1f, 1.3f)) {
            compose.runOnIdle { display = display.copy(theme = theme, fontScale = scale) }
            val title = compose.onNodeWithText(app.name, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val statistics = compose.onNodeWithTag("app-statistics-${app.id}", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val rating = compose.onNodeWithTag("app-rating-${app.id}", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val usage = compose.onNodeWithTag("app-usage-${app.id}", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(title.right < statistics.left)
            assertTrue(rating.bottom < usage.top)
            assertEquals(rating.right, usage.right, 1f)
            compose.onNodeWithContentDescription("评分 4.35 分，满分 5 分", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("123 人评分", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("使用 56789 人次", useUnmergedTree = true).assertIsDisplayed()
            capture("apps-list-statistics-$theme-$scale.png")
        }
    }

    @Test fun missingRatingIsOmittedRealZeroUsageRemainsAndGridHasNoStatistics() {
        val zero = app.copy(name = "没有评分", ratingAverage = 0.0, ratingCount = 0, usageCount = 0)
        compose.setContent { KudoTheme(display) {
            AppSquareScreen(AppSquareState(apps = listOf(zero), loading = false), {}, {}, {}, {}, {}, {}, initialGrid = false)
        } }
        compose.onNodeWithTag("app-rating-${app.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("使用 0 人次", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("切换宫格布局").performClick()
        compose.onNodeWithTag("app-statistics-${app.id}", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun absentStatisticsDoNotCreateAPlaceholderAndCountOnlyIsSupported() {
        var listed by mutableStateOf(app.copy(ratingAverage = null, ratingCount = null, usageCount = null))
        compose.setContent { KudoTheme(display) {
            AppSquareScreen(AppSquareState(apps = listOf(listed), loading = false), {}, {}, {}, {}, {}, {}, initialGrid = false)
        } }
        compose.onNodeWithTag("app-statistics-${app.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { listed = listed.copy(ratingCount = 3_000_000_000, usageCount = Long.MAX_VALUE) }
        compose.onNodeWithContentDescription("3000000000 人评分", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("使用 ${Long.MAX_VALUE} 人次", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
