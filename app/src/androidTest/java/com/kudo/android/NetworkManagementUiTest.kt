package com.kudo.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.network.*
import com.kudo.android.ui.account.DohSettingsScreen
import com.kudo.android.ui.theme.KudoTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NetworkManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val requests = RequestSettings(instrumentation.context).apply { update(RequestLimits.Balanced) }
    private val doh = DohSettings(instrumentation.context).apply { update(DohConfiguration().select(ShihuangDohServer.id)) }
    private val network = DohNetwork(instrumentation.targetContext, doh) { NetworkSnapshot(1) }
    @After fun cleanup() { network.close(); requests.update(RequestLimits.Balanced); doh.update(DohConfiguration()) }

    private fun show() { compose.setContent { KudoTheme(DisplaySettings()) { DohSettingsScreen(network, requests) {} } } }
    private fun preset(index: Int) = compose.onNodeWithTag("request-preset-$index").performScrollTo()
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("networkManagementScreenshots") != "true") return
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun presetsUpdateSummaryAndPersistWithoutChangingDoh() {
        show()
        compose.onNodeWithText("网络管理").assertIsDisplayed()
        preset(1).assertIsSelected()
        preset(0).assertTextContains("慢速").performClick().assertIsSelected()
        compose.onNodeWithTag("request-summary").assertTextEquals("每 5 秒内最多 6 个请求，同时进行 2 个")
        compose.runOnIdle { assertEquals(RequestLimits.Gentle, RequestSettings(instrumentation.context).configuration.value) }
        preset(2).performClick().assertIsSelected()
        compose.onNodeWithTag("request-summary").assertTextEquals("每 3 秒内最多 15 个请求，同时进行 6 个")
        compose.runOnIdle {
            assertEquals(RequestLimits.Turbo, RequestSettings(instrumentation.context).configuration.value)
            assertEquals(ShihuangDohServer, doh.configuration.value.selected)
            assertFalse(doh.configuration.value.enabled)
        }
        capture("network-management-presets.png")
    }

    @Test fun customSlidersPersistAcrossReopeningAndAdvancedControlsCanCollapse() {
        val visible = mutableStateOf(true)
        val restored = mutableStateOf(requests)
        compose.setContent { KudoTheme(DisplaySettings()) { if (visible.value) DohSettingsScreen(network, restored.value) {} } }
        preset(3).performClick().assertIsSelected()
        fun set(tag: String, value: Float) {
            compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        }
        set("request-concurrent", 5f); set("request-per-window", 22f); set("request-window", 7f)
        compose.onNodeWithTag("request-summary").performScrollTo().assertTextEquals("每 7 秒内最多 22 个请求，同时进行 5 个")
        compose.onNodeWithTag("request-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("request-concurrent").assertDoesNotExist()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { restored.value = RequestSettings(instrumentation.context); visible.value = true }
        preset(3).assertIsSelected()
        compose.onNodeWithTag("request-window").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(RequestLimits(5, 22, 7), restored.value.configuration.value) }
        capture("network-management-custom.png")
    }

    @Test fun resetRequiresConfirmationAndOnlyResetsRequestLimits() {
        requests.update(RequestLimits.Turbo)
        show()
        compose.onNodeWithContentDescription("恢复默认请求设置").performScrollTo().performClick()
        compose.onNodeWithText("恢复默认请求设置？").assertIsDisplayed()
        capture("network-management-reset.png")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(RequestLimits.Turbo, requests.configuration.value) }
        compose.onNodeWithContentDescription("恢复默认请求设置").performClick()
        compose.onNodeWithText("恢复默认").performClick()
        preset(1).assertIsSelected()
        compose.runOnIdle {
            assertEquals(RequestLimits.Balanced, RequestSettings(instrumentation.context).configuration.value)
            assertEquals(ShihuangDohServer, doh.configuration.value.selected)
            assertFalse(doh.configuration.value.enabled)
        }
    }

    @Test fun lightLargeTextSupportsWrappedPresetsAndTouchAdjustment() {
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT, fontScale = 1.3f)) {
            DohSettingsScreen(network, requests) {}
        } }
        preset(3).performClick().assertIsSelected()
        val slider = compose.onNodeWithTag("request-concurrent").performScrollTo()
        slider.performTouchInput { click(Offset(width * .55f, centerY)) }
        compose.runOnIdle { assertEquals(6, requests.configuration.value.concurrent) }
        slider.performTouchInput {
            swipe(Offset(width * 5f / 9f, centerY), Offset(width * .9f, centerY), 300)
        }
        compose.runOnIdle {
            assertTrue(requests.configuration.value.concurrent >= 8)
            assertEquals(requests.configuration.value, RequestSettings(instrumentation.context).configuration.value)
        }
        capture("network-management-light-large.png")
        compose.onNodeWithContentDescription("恢复默认请求设置").performScrollTo().performClick()
        compose.onNodeWithText("恢复默认请求设置？").assertIsDisplayed()
        compose.onNodeWithText("恢复默认").assertIsDisplayed()
        capture("network-management-reset-light-large.png")
        compose.onNodeWithText("恢复默认").performClick()
        preset(1).assertIsSelected()
    }
}
