package com.kudo.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.apps.*
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppSquareUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val apps = (0 until 90).map {
        MiniAppEntry("01a1966a-f4a0-7b43-ad9c-${it.toString().padStart(12, '0')}", if (it == 0) "2048" else "应用 $it", null)
    }
    private var display by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
    private var state by mutableStateOf(AppSquareState(apps = apps, loading = false))

    @Test fun rightmostSearchExpandsOverFilterAndLayoutAcrossMaterials() {
        compose.setContent { KudoTheme(display) { Box(Modifier.width(320.dp)) {
            AppSquareScreen(state, { state = state.copy(query = it) }, {}, {}, {}, {}, {})
        } } }
        capture("apps-grid-light.png")
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (glass in GlassMode.entries) {
            compose.runOnIdle { display = DisplaySettings(theme = theme, glass = glass, fontScale = 1.3f) }
            val back = compose.onNodeWithTag("apps-back").fetchSemanticsNode().boundsInRoot
            val layout = compose.onNodeWithTag("apps-layout").fetchSemanticsNode().boundsInRoot
            val filter = compose.onNodeWithTag("apps-filter").fetchSemanticsNode().boundsInRoot
            val search = compose.onNodeWithTag("apps-search").fetchSemanticsNode().boundsInRoot
            assertTrue(filter.right <= layout.left && layout.right <= search.left)
            compose.onNodeWithContentDescription("搜索应用").performClick()
            compose.onNodeWithTag("apps-search-field").performTextInput("砖块")
            compose.onNodeWithTag("apps-search-field").assertTextEquals("砖块").assertIsFocused()
            assertEquals(back, compose.onNodeWithTag("apps-back").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("apps-layout").assertDoesNotExist()
            compose.onNodeWithTag("apps-filter").assertDoesNotExist()
            val surface = compose.onNodeWithTag("apps-search-surface").fetchSemanticsNode().boundsInRoot
            assertTrue(surface.left >= back.right && surface.left < filter.left)
            assertEquals(search.right, surface.right, 1f)
            compose.onNodeWithTag("apps-grid").performScrollToIndex(30)
            capture("apps-search-${theme.name}-${glass.name}.png")
            compose.onNodeWithContentDescription("清空搜索").performClick()
            compose.onNodeWithTag("apps-search-field").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithTag("apps-back").performClick()
            compose.onNodeWithTag("apps-search-field").assertDoesNotExist()
            assertEquals(layout, compose.onNodeWithTag("apps-layout").fetchSemanticsNode().boundsInRoot)
            assertEquals(filter, compose.onNodeWithTag("apps-filter").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun layoutsSaveLocallyAndOpeningAnAppKeepsQueryAndScrolledPosition() {
        var savedGrid = true
        var opened = ""
        compose.setContent { KudoTheme(display) {
            val nav = rememberNavController()
            NavHost(nav, "directory", Modifier.fillMaxSize()) {
                composable("directory") { AppSquareScreen(state,
                    { state = state.copy(query = it, revision = state.revision + 1) }, {}, {}, {}, {},
                    { opened = it; nav.navigate("running") }, saveGrid = { savedGrid = it }) }
                composable("running") { Text("运行中") }
            }
        } }
        compose.onNodeWithContentDescription("切换列表布局").performClick()
        compose.runOnIdle { assertFalse(savedGrid) }
        compose.onNodeWithContentDescription("搜索应用").performClick()
        compose.onNodeWithTag("apps-search-field").performTextInput("应用")
        compose.onNodeWithTag("apps-back").performClick()
        compose.onNodeWithTag("apps-list").performScrollToIndex(45)
        capture("apps-list-light.png")
        val app = compose.onNodeWithTag("app-${apps[45].id}")
        val before = app.fetchSemanticsNode().boundsInRoot
        app.performClick()
        compose.onNodeWithText("运行中").assertIsDisplayed()
        compose.runOnIdle { assertEquals(apps[45].url, opened) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("apps-list").assertIsDisplayed()
        app.assertIsDisplayed()
        assertEquals(before.top, app.fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithContentDescription("搜索应用").performClick()
        compose.onNodeWithTag("apps-search-field").assertTextEquals("应用")
    }

    @Test fun loadingEmptyFailureAndPaginationRetryRemainReachable() {
        var retries = 0
        var more = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            AppSquareScreen(state, {}, { retries++ }, { more++ }, {}, {}, {})
        } }
        compose.runOnIdle { state = AppSquareState(loading = false, error = IllegalStateException("离线")) }
        compose.onNodeWithText("重新加载").performClick()
        compose.runOnIdle { assertEquals(1, retries); state = AppSquareState(query = "不存在", loading = false) }
        compose.onNodeWithText("没有找到应用").assertIsDisplayed()
        compose.runOnIdle { state = AppSquareState(apps = apps.take(1), loading = false, hasMore = true, moreError = IllegalStateException("离线")) }
        compose.onNodeWithText("2048").assertIsDisplayed()
        compose.onNodeWithText("重新加载").performClick()
        compose.runOnIdle { assertEquals(1, more) }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
