package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
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

class AppSquareFavoritesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var state by mutableStateOf(AppSquareState(loading = false))

    @Test fun favoritesOnlyAppearsForLoggedInAccountsWithoutAnAllOption() {
        var loggedIn by mutableStateOf(false)
        compose.setContent { KudoTheme(DisplaySettings()) {
            AppSquareScreen(state, {}, {}, {}, {}, {}, {}, loggedIn = loggedIn)
        } }
        compose.onNodeWithContentDescription("筛选应用").performClick()
        for (option in listOf("最热", "最新上线", "评分最高", "使用最多")) {
            compose.onNodeWithText(option).assertIsDisplayed()
        }
        compose.onNodeWithText("已收藏").assertDoesNotExist()
        compose.onNodeWithText("全部").assertDoesNotExist()
        compose.runOnIdle { loggedIn = true }
        compose.onNodeWithText("已收藏").assertIsDisplayed()
        compose.onNodeWithText("全部").assertDoesNotExist()
        compose.runOnIdle { loggedIn = false }
        compose.onNodeWithText("已收藏").assertDoesNotExist()
        compose.onNodeWithTag("apps-sort-latest").assertIsSelected()
    }

    @Test fun choosingAnyStandardSortExitsFavoritesAndKeepsTheQueryAndLayout() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            AppSquareScreen(state, { state = state.copy(query = it) }, {}, {}, {}, {}, {},
                initialGrid = false, sort = { state = state.copy(sort = it) }, loggedIn = true)
        } }
        compose.onNodeWithContentDescription("搜索应用").performClick()
        compose.onNodeWithTag("apps-search-field").performTextInput("2048")
        compose.onNodeWithTag("apps-back").performClick()
        for (option in listOf("hot", "latest", "rating", "used")) {
            compose.onNodeWithContentDescription("筛选应用").performClick()
            compose.onNodeWithTag("apps-sort-favorites").performClick()
            compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
            compose.onNodeWithTag("apps-list").assertIsDisplayed()
            compose.onNodeWithContentDescription("筛选应用").performClick()
            compose.onNodeWithTag("apps-sort-favorites").assertIsSelected()
            compose.onNodeWithTag("apps-sort-$option").assertIsNotSelected().performClick()
            compose.onNodeWithContentDescription("筛选应用").performClick()
            compose.onNodeWithTag("apps-sort-$option").assertIsSelected()
            compose.onNodeWithTag("apps-sort-favorites").assertIsNotSelected()
            compose.runOnIdle { assertEquals("2048", state.query); assertFalse(state.sort.favorite) }
            dismissSheet()
        }
        compose.onNodeWithContentDescription("搜索应用").performClick()
        compose.onNodeWithTag("apps-search-field").assertTextEquals("2048")
    }

    @Test fun emptyFavoritesAndSelectedChoiceStayReadableAcrossThemesAndMaterials() {
        var display by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT, fontScale = 1.3f))
        state = state.copy(sort = MiniAppSort.FAVORITES)
        compose.setContent { KudoTheme(display) { Box(Modifier.width(320.dp)) {
            AppSquareScreen(state, {}, {}, {}, {}, {}, {}, loggedIn = true)
        } } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (glass in GlassMode.entries) {
            compose.runOnIdle { display = display.copy(theme = theme, glass = glass) }
            compose.onNodeWithText("暂无收藏的应用").assertIsDisplayed()
            compose.onNodeWithContentDescription("筛选应用").performClick()
            compose.onNodeWithTag("apps-sort-favorites").assertIsDisplayed().assertIsSelected()
            compose.onNodeWithTag("apps-sort-latest").assertIsNotSelected()
            capture("apps-favorites-$theme-$glass.png")
            dismissSheet()
        }
        compose.runOnIdle { state = state.copy(query = "2048") }
        compose.onNodeWithText("没有找到应用").assertIsDisplayed()
        compose.onNodeWithText("暂无收藏的应用").assertDoesNotExist()
    }

    private fun dismissSheet() {
        compose.onNodeWithTag("modal-scrim").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }
}
