package com.kudo.android

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.kudo.android.data.DisplaySettings
import com.kudo.android.ui.components.LocalToolbarBackdrop
import com.kudo.android.ui.components.MaterialPageScaffold
import com.kudo.android.ui.components.PageTopBar
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WebViewMaterialLayoutUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ordinaryLayoutDoesNotCreateTheSamplingLayerAndAdvancedLayoutOptsIn() {
        var advanced by mutableStateOf(false)
        var hasBackdrop = false
        compose.setContent { KudoTheme(DisplaySettings()) {
            MaterialPageScaffold(advanced = advanced, header = {
                hasBackdrop = LocalToolbarBackdrop.current != null
                PageTopBar("网页", {})
            }) { Text("网页内容") }
        } }
        compose.onNodeWithTag("material-page-content").assertDoesNotExist()
        compose.onNodeWithText("网页内容").assertIsDisplayed()
        compose.runOnIdle { assertFalse(hasBackdrop); advanced = true }
        compose.onNodeWithTag("material-page-content").assertExists()
        compose.runOnIdle { assertTrue(hasBackdrop); advanced = false }
        compose.onNodeWithTag("material-page-content").assertDoesNotExist()
        compose.runOnIdle { assertFalse(hasBackdrop) }
    }
}
