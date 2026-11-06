package com.kudo.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kudo.android.data.*
import com.kudo.android.ui.apps.*
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppSquareAboutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = MiniAppEntry("01a1966a-f4a0-7b43-ad9c-c46b7f0e0281", "2048", null)
    private val info = MiniAppDetails(app.id, app.name, "2048 小游戏", null, "1.0.0",
        Member(1, "neo", "Neo", ""), 4.35, 7L)
    private var state by mutableStateOf(AppSquareState(apps = listOf(app), loading = false))

    @Test fun bothLayoutsLongPressAboutWhileNormalTapStillOpensTheApp() {
        var opened = ""
        var selected = ""
        compose.setContent { KudoTheme(DisplaySettings()) {
            AppSquareScreen(state, {}, {}, {}, {}, {}, { opened = it },
                showAbout = { selected = it.id; state = state.copy(about = AppSquareAboutState(it, info, false)) },
                dismissAbout = { state = state.copy(about = null) })
        } }
        repeat(2) { layout ->
            compose.onNodeWithTag("app-${app.id}").performTouchInput { longClick() }
            compose.onNodeWithText("2048 小游戏").assertIsDisplayed()
            compose.onNodeWithContentDescription("评分 4.35 分，满分 5 分").assertIsDisplayed()
            compose.onNodeWithText("7 人评分").assertIsDisplayed()
            compose.onNodeWithContentDescription("Neo的头像").assertIsDisplayed()
            compose.onNodeWithText("使用次数", substring = true).assertDoesNotExist()
            compose.runOnIdle { assertEquals(app.id, selected); assertEquals("", opened) }
            compose.onNodeWithText("关闭", substring = false).performClick()
            compose.onNodeWithTag("app-${app.id}").performClick()
            compose.runOnIdle { assertEquals(app.url, opened); opened = "" }
            if (layout == 0) compose.onNodeWithContentDescription("切换列表布局").performClick()
        }
    }

    @Test fun missingRatingsAreOmittedAndFailedDetailsCanRetry() {
        var retried = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            AppSquareScreen(state, {}, {}, {}, {}, {}, {}, retryAbout = {
                retried++; state = state.copy(about = AppSquareAboutState(app, info.copy(ratingAverage = null, ratingCount = null), false))
            })
        } }
        compose.runOnIdle { state = state.copy(about = AppSquareAboutState(app, loading = false, error = IllegalStateException("离线"))) }
        compose.onNodeWithText("应用信息加载失败").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("2048 小游戏").assertIsDisplayed()
        compose.onNodeWithTag("mini-app-rating").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, retried)
            state = state.copy(about = AppSquareAboutState(app, info.copy(ratingAverage = 0.0, ratingCount = 0), false))
        }
        compose.onNodeWithText("暂无评分").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(about = AppSquareAboutState(app, info.copy(ratingAverage = null), false)) }
        compose.onNodeWithText("7 人评分").assertIsDisplayed()
        compose.onNodeWithContentDescription("评分 4.35 分，满分 5 分").assertDoesNotExist()
    }

    @Test fun longPressFromSearchHidesTheKeyboardBeforeShowingAbout() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            AppSquareScreen(state, { state = state.copy(query = it) }, {}, {}, {}, {}, {},
                showAbout = { state = state.copy(about = AppSquareAboutState(it, info, false)) })
        } }
        fun keyboardVisible(): Boolean {
            var visible = false
            compose.runOnUiThread { visible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            return visible
        }
        compose.onNodeWithContentDescription("搜索应用").performClick()
        compose.onNodeWithTag("apps-search-field").performTextInput("2048")
        compose.waitUntil(5000) { keyboardVisible() }
        compose.onNodeWithTag("app-${app.id}").performTouchInput { longClick() }
        compose.onNodeWithText("2048 小游戏").assertIsDisplayed()
        compose.waitUntil(5000) { !keyboardVisible() }
    }

    @Test fun sortSheetKeepsTheQueryAndLayoutAndMarksCurrentChoice() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            AppSquareScreen(state, { state = state.copy(query = it) }, {}, {}, {}, {}, {}, sort = {
                state = state.copy(sort = it)
            }, loggedIn = true)
        } }
        compose.onNodeWithContentDescription("切换列表布局").performClick()
        compose.onNodeWithContentDescription("搜索应用").performClick()
        compose.onNodeWithTag("apps-search-field").performTextInput("2048")
        compose.onNodeWithTag("apps-back").performClick()
        for (sort in MiniAppSort.entries) {
            compose.onNodeWithContentDescription("筛选应用").performClick()
            compose.onNodeWithTag("apps-sort-${state.sort.key}").assertIsSelected()
            compose.onNodeWithTag("apps-sort-${sort.key}").performClick()
            compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
            compose.onNodeWithTag("apps-list").assertIsDisplayed()
            compose.runOnIdle { assertEquals(sort, state.sort); assertEquals("2048", state.query) }
        }
        compose.onNodeWithContentDescription("筛选应用").performClick()
        compose.onNodeWithTag("modal-scrim").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索应用").performClick()
        compose.onNodeWithTag("apps-search-field").assertTextEquals("2048")
    }
}
