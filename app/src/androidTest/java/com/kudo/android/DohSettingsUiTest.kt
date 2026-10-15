package com.kudo.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.network.*
import com.kudo.android.ui.account.DohSettingsScreen
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test

class DohSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val settings = DohSettings(instrumentation.context).apply { update(DohConfiguration()) }
    private val requests = RequestSettings(instrumentation.context)
    private var snapshot = NetworkSnapshot(1)
    private val network = DohNetwork(instrumentation.targetContext, settings) { snapshot }
    @After fun close() { network.close(); settings.update(DohConfiguration()) }
    private fun show() { compose.setContent { KudoTheme(DisplaySettings()) { DohSettingsScreen(network, requests) {} } } }

    @Test fun addValidateSelectEditAndDeleteCustomServer() {
        show()
        compose.onNodeWithTag("doh-enabled").assertIsOff()
        compose.onNodeWithTag("doh-add").performClick()
        compose.onNodeWithTag("doh-name").performTextInput("测试节点")
        compose.onNodeWithTag("doh-url").performTextInput("http://dns.example/query")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("请输入不含账号或片段的 HTTPS DoH 地址").assertIsDisplayed()
        compose.onNodeWithTag("doh-url").performTextReplacement("https://dns.example/query")
        compose.onNodeWithTag("doh-bootstrap").performTextInput("1.1.1.1")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("测试节点").performClick()
        compose.runOnIdle { assertEquals("测试节点", settings.configuration.value.selected.name) }
        compose.onNodeWithContentDescription("管理 测试节点").performClick()
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("doh-name").performTextReplacement("修改节点")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals("修改节点", settings.configuration.value.selected.name); assertEquals(1, settings.configuration.value.customServers.size) }
        compose.onNodeWithContentDescription("管理 修改节点").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertEquals(BuiltInDohServer, settings.configuration.value.selected); assertTrue(settings.configuration.value.customServers.isEmpty()) }
    }
    @Test fun vpnPauseIsVisibleAndClosingWhilePausedDoesNotRestore() {
        show()
        compose.onNodeWithTag("doh-enabled").performClick()
        compose.runOnIdle { snapshot = snapshot.copy(vpn = true); network.refresh() }
        compose.onNodeWithText("VPN 已开启，DoH 暂停").assertIsDisplayed()
        compose.onNodeWithTag("doh-enabled").assertIsOn().performClick()
        compose.runOnIdle { snapshot = snapshot.copy(vpn = false); network.refresh(); assertFalse(settings.configuration.value.enabled) }
        compose.onNodeWithTag("doh-enabled").assertIsOff()
    }

    @Test fun vpnAutoSwitchDefaultsOnCanBeDisabledAndPersistsAcrossTheScreen() {
        val visible = mutableStateOf(true)
        compose.setContent { KudoTheme(DisplaySettings()) { if (visible.value) DohSettingsScreen(network, requests) {} } }
        compose.onNodeWithText("VPN 自动切换").performScrollTo()
        compose.onNodeWithTag("doh-vpn-auto-switch").assertIsOn().performClick()
        compose.runOnIdle {
            assertFalse(settings.configuration.value.vpnAutoSwitch)
            network.refresh()
        }
        compose.onNodeWithTag("doh-vpn-auto-switch").assertIsOff()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertFalse(DohSettings(instrumentation.context).configuration.value.vpnAutoSwitch)
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("VPN 自动切换").performScrollTo()
        compose.onNodeWithTag("doh-vpn-auto-switch").assertIsOff()
        compose.onNodeWithText("检测到 VPN 或系统代理时自动暂停 DoH，断开后恢复").assertIsDisplayed()
    }
}
