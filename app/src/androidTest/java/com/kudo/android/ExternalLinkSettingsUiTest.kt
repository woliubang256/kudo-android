package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.AppSettings
import com.kudo.android.data.ExternalLinkMode
import com.kudo.android.ui.account.SettingsScreen
import com.kudo.android.ui.theme.KudoTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ExternalLinkSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    // Never edit the installed app's real settings or login state.
    private val context = object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("external_link_mode_test_$name", mode)
    }
    private val prefs get() = context.getSharedPreferences("kudo_preferences", Context.MODE_PRIVATE)

    @After fun cleanFixture() { prefs.edit().clear().commit() }

    @Test fun defaultSelectionAndChangedChoiceSurviveReopeningTheStore() {
        prefs.edit().clear().commit()
        var store by mutableStateOf(AppSettings(context))
        compose.setContent {
            val settings by store.display.collectAsState()
            KudoTheme(settings) { SettingsScreen(settings, store::update, null, {}, {}, {}) }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("外部链接打开方式"))
        compose.onNodeWithText("外部链接打开方式").performClick()
        compose.onNodeWithTag("modal-choice-0").assertIsSelected()
        compose.onNodeWithTag("modal-choice-1").assertIsNotSelected()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(target.getExternalFilesDir(null), "external-link-mode-settings.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        compose.onNodeWithText("应用内打开").performClick()
        compose.runOnIdle {
            assertEquals(ExternalLinkMode.IN_APP, store.display.value.externalLinkMode)
            store = AppSettings(context)
            assertEquals(ExternalLinkMode.IN_APP, store.display.value.externalLinkMode)
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("外部链接打开方式"))
        compose.onNodeWithText("外部链接打开方式").performClick()
        compose.onNodeWithTag("modal-choice-1").assertIsSelected()
        compose.onNodeWithText("系统打开").performClick()
        compose.runOnIdle { assertEquals(ExternalLinkMode.SYSTEM, AppSettings(context).display.value.externalLinkMode) }
    }

    @Test fun missingAndUnknownPreferencesUseSystemWithoutChangingOtherSettings() {
        prefs.edit().clear().putString("theme", "DARK").commit()
        assertEquals(ExternalLinkMode.SYSTEM, AppSettings(context).display.value.externalLinkMode)
        prefs.edit().putString("external_link_mode", "unknown_future_value").commit()
        val store = AppSettings(context)
        assertEquals(ExternalLinkMode.SYSTEM, store.display.value.externalLinkMode)
        store.update(store.display.value.copy(externalLinkMode = ExternalLinkMode.IN_APP))
        val restored = AppSettings(context).display.value
        assertEquals(ExternalLinkMode.IN_APP, restored.externalLinkMode)
        assertEquals(com.kudo.android.data.ThemeMode.DARK, restored.theme)
    }
}
