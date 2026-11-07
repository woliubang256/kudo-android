package com.kudo.android

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.reporting.*
import com.kudo.android.ui.account.ReportingConsentPrompt
import com.kudo.android.ui.account.SettingsScreen
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.web.FloatingMaterialKey
import com.kudo.android.ui.web.FloatingSampleKey
import io.sentry.Sentry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ReportingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Store(var value: ReportingConsent = ReportingConsent.UNKNOWN, var writable: Boolean = true) : ReportingConsentStore {
        override fun read() = value
        override fun write(value: ReportingConsent): Boolean { if (!writable) return false; this.value = value; return true }
    }

    @Test fun firstDecisionOnlyShowsOneDialogAndDeclineNeverInitializes() {
        var starts = 0
        val store = Store()
        val controller = ReportingController(store) { starts++ }
        compose.setContent { KudoTheme(DisplaySettings()) {
            Text("论坛")
            ReportingConsentPrompt(controller)
            ReportingConsentPrompt(controller)
        } }
        compose.onAllNodesWithTag("reporting-consent-window").assertCountEquals(1)
        compose.onNodeWithText("不允许").performClick()
        compose.waitUntil { controller.consent.value == ReportingConsent.DENIED }
        compose.onNodeWithTag("reporting-consent-window").assertDoesNotExist()
        assertEquals(0, starts)
        assertFalse(Sentry.isEnabled())
    }

    @Test fun firstAllowanceOnlyInitializesTheIsolatedControllerOnce() {
        var starts = 0
        val store = Store()
        val controller = ReportingController(store) { assertEquals(ReportingConsent.ALLOWED, store.value); starts++ }
        compose.setContent { KudoTheme(DisplaySettings()) { Text("论坛"); ReportingConsentPrompt(controller) } }
        compose.onNodeWithText("允许", substring = false).performClick()
        compose.waitUntil { controller.active }
        compose.onNodeWithTag("reporting-consent-window").assertDoesNotExist()
        assertEquals(1, starts)
        assertFalse("Fake initializer must not enable the device's real SDK", Sentry.isEnabled())
    }

    @Test fun consentUsesRealSamplingInAllSixThemeMaterialCombinations() {
        var display by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        val controller = ReportingController(Store()) { }
        compose.setContent { KudoTheme(display) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF768D66)))
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFAE665B)))
            }
            ReportingConsentPrompt(controller)
        } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (mode in GlassMode.entries) {
            compose.runOnIdle { display = display.copy(theme = theme, glass = mode, fontScale = 1.3f) }
            compose.onNodeWithText("是否启用 Sentry 错误上报？").assertIsDisplayed()
            compose.onNodeWithText("不允许").assertIsDisplayed()
            compose.onNodeWithText("允许", substring = false).assertIsDisplayed()
            compose.onNodeWithTag("reporting-consent-window").assert(SemanticsMatcher.expectValue(FloatingMaterialKey, mode))
            if (mode != GlassMode.TRANSLUCENT) compose.waitUntil(5000) {
                compose.onAllNodes(SemanticsMatcher.expectValue(FloatingSampleKey, true)).fetchSemanticsNodes().isNotEmpty()
            }
            screenshot("sentry-consent-${theme.name}-${mode.name}.png")
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("reporting-consent-window").assertIsDisplayed()
        compose.onNodeWithText("不允许").performClick()
    }

    @Test fun settingsCancelAndBackDoNotPersistAndNextStartDefersBothDirections() {
        val store = Store(ReportingConsent.DENIED)
        var starts = 0; var restarts = 0
        val controller = ReportingController(store) { starts++ }.also { it.start() }
        settings(controller) { restarts++; true }
        openSwitch()
        compose.onNodeWithText("取消").performClick()
        assertEquals(ReportingConsent.DENIED, store.value)
        openSwitch()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertEquals(ReportingConsent.DENIED, store.value)
        openSwitch()
        compose.onNodeWithText("下次启动生效").performClick()
        compose.waitUntil { store.value == ReportingConsent.ALLOWED }
        compose.onNodeWithTag("sentry-reporting-switch").assertIsOn()
        assertFalse(controller.active); assertEquals(0, starts); assertEquals(0, restarts)
        openSwitch()
        compose.onNodeWithText("立即重启生效").performClick()
        compose.waitUntil { restarts == 1 }
        assertEquals(ReportingConsent.DENIED, store.value)
        assertFalse(controller.active)
    }

    @Test fun failedSaveLeavesOriginalSwitchAndDoesNotRestart() {
        val store = Store(ReportingConsent.DENIED, false)
        val controller = ReportingController(store) { fail("must not initialize") }
        settings(controller) { fail("must not restart"); false }
        openSwitch()
        compose.onNodeWithText("立即重启生效").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("liquid-dialog").assertIsDisplayed()
        assertEquals(ReportingConsent.DENIED, store.value)
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("sentry-reporting-switch").assertIsOff()
    }

    @Test fun isolatedPrivatePreferencesSurviveNewControllerWithoutTouchingRealConsent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val real = context.getSharedPreferences("error_reporting", Context.MODE_PRIVATE).all.toMap()
        val name = "sentry-fixture-${UUID.randomUUID()}"
        try {
            val store = PrivateReportingConsentStore(context, name)
            assertEquals(ReportingConsent.UNKNOWN, store.read())
            assertTrue(store.write(ReportingConsent.ALLOWED))
            var starts = 0
            ReportingController(PrivateReportingConsentStore(context, name)) { starts++ }.start()
            assertEquals(1, starts)
            assertEquals(real, context.getSharedPreferences("error_reporting", Context.MODE_PRIVATE).all)
            assertFalse(Sentry.isEnabled())
        } finally { context.deleteSharedPreferences(name) }
    }

    private fun settings(controller: ReportingController, restart: () -> Boolean) {
        compose.setContent { KudoTheme(DisplaySettings()) {
            SettingsScreen(DisplaySettings(), {}, null, {}, {}, {}, reporting = controller, restart = restart)
        } }
    }
    private fun openSwitch() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sentry 错误上报"))
        compose.onNodeWithTag("sentry-reporting-switch").assertContentDescriptionEquals("Sentry 错误上报").performClick()
        compose.onNodeWithTag("liquid-dialog").assertIsDisplayed()
    }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "sentry-verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
