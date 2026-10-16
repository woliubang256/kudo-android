package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.AppSettings
import com.kudo.android.data.DisplaySettings
import com.kudo.android.data.ThemeMode
import com.kudo.android.ui.account.SettingsScreen
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WebViewMaterialSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultsOffAndReopeningTheStoreRetainsBothToggleChoices() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "webview_material_fixture_${System.nanoTime()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(preferenceName, mode)
        }
        try {
            assertFalse(DisplaySettings().advancedWebViewMaterial)
            var store by mutableStateOf(AppSettings(context))
            assertFalse(store.display.value.advancedWebViewMaterial)
            store.update(store.display.value.copy(theme = ThemeMode.DARK))
            compose.setContent {
                val settings by store.display.collectAsState()
                KudoTheme(settings) { SettingsScreen(settings, store::update, null, {}, {}, {}) }
            }
            compose.onNodeWithTag("advanced-webview-material").performScrollTo().assertIsOff().performClick().assertIsOn()
            compose.runOnIdle { store = AppSettings(context) }
            compose.onNodeWithTag("advanced-webview-material").assertIsOn().performClick().assertIsOff()
            compose.runOnIdle {
                store = AppSettings(context)
                assertFalse(store.display.value.advancedWebViewMaterial)
                assertEquals(ThemeMode.DARK, store.display.value.theme)
            }
        } finally {
            target.deleteSharedPreferences(preferenceName)
        }
    }
}
