package com.kudo.android

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.kudo.android.data.DisplaySettings
import com.kudo.android.data.GlassMode
import com.kudo.android.data.ThemeMode
import com.kudo.android.ui.components.LocalToolbarBackdrop
import com.kudo.android.ui.components.LocalToolbarActions
import com.kudo.android.ui.components.WebPageScaffold
import com.kudo.android.ui.components.PageTopBar
import com.kudo.android.ui.theme.KudoTheme
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WebViewMaterialLayoutUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun webLayoutClearsInheritedSamplingAcrossNativeThemesAndMaterials() {
        var settings by mutableStateOf(DisplaySettings())
        var headerHasBackdrop = false
        var contentHasBackdrop = false
        var contentHasToolbarActions = false
        var topPadding = -1f
        compose.setContent { KudoTheme(settings) {
            val inherited = rememberLayerBackdrop()
            CompositionLocalProvider(LocalToolbarBackdrop provides inherited, LocalToolbarActions provides true) {
                WebPageScaffold(header = {
                    headerHasBackdrop = LocalToolbarBackdrop.current != null
                    PageTopBar("网页", {})
                }) { padding ->
                    contentHasBackdrop = LocalToolbarBackdrop.current != null
                    contentHasToolbarActions = LocalToolbarActions.current
                    topPadding = padding.calculateTopPadding().value
                    Text("网页内容")
                }
            }
        } }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            GlassMode.entries.forEach { glass ->
                compose.runOnIdle { settings = settings.copy(theme = theme, glass = glass) }
                compose.onNodeWithTag("material-page-content").assertDoesNotExist()
                compose.onNodeWithText("网页内容").assertIsDisplayed()
                val header = compose.onNodeWithTag("material-page-header").fetchSemanticsNode().boundsInRoot
                val content = compose.onNodeWithText("网页内容").fetchSemanticsNode().boundsInRoot
                assertTrue(content.top >= header.bottom)
                compose.runOnIdle {
                    assertFalse(headerHasBackdrop)
                    assertFalse(contentHasBackdrop)
                    assertFalse(contentHasToolbarActions)
                    assertEquals(0f, topPadding, 0f)
                }
            }
        }
    }
}
