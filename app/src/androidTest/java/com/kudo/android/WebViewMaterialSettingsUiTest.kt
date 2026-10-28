package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.AppSettings
import com.kudo.android.data.DisplaySettings
import com.kudo.android.data.GlassMode
import com.kudo.android.data.ThemeMode
import com.kudo.android.ui.account.SettingsScreen
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WebViewMaterialSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun obsoleteWebViewMaterialSettingIsAbsent() {
        val settings = DisplaySettings()
        compose.setContent { KudoTheme(settings) {
            SettingsScreen(settings, {}, null, {}, {}, {})
        } }
        compose.onNodeWithText("网页高级材质").assertDoesNotExist()
        compose.onNodeWithTag("advanced-webview-material").assertDoesNotExist()
        compose.onNodeWithText("界面材质效果").assertIsDisplayed()
    }

    @Test fun legacyEnabledPreferenceIsIgnoredWithoutResettingOtherSettings() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "webview_material_fixture_${System.nanoTime()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(preferenceName, mode)
        }
        try {
            context.getSharedPreferences("kudo_preferences", Context.MODE_PRIVATE).edit()
                .putBoolean("advanced_webview_material", true)
                .putString("theme", ThemeMode.DARK.name)
                .putString("glass", GlassMode.BLUR.name)
                .putBoolean("show_scroll_to_top", false)
                .putBoolean("blur_nsfw_images", false)
                .commit()
            val expected = DisplaySettings(theme = ThemeMode.DARK, glass = GlassMode.BLUR,
                showScrollToTop = false, blurNsfwImages = false)
            var store by mutableStateOf(AppSettings(context))
            assertEquals(expected, store.display.value)
            compose.setContent {
                val settings by store.display.collectAsState()
                KudoTheme(settings) { SettingsScreen(settings, store::update, null, {}, {}, {}) }
            }
            compose.onNodeWithText("网页高级材质").assertDoesNotExist()
            compose.onNodeWithTag("advanced-webview-material").assertDoesNotExist()
            compose.runOnIdle {
                store.update(store.display.value.copy(theme = ThemeMode.LIGHT))
                store = AppSettings(context)
                assertEquals(expected.copy(theme = ThemeMode.LIGHT), store.display.value)
            }
        } finally {
            target.deleteSharedPreferences(preferenceName)
        }
    }
}
