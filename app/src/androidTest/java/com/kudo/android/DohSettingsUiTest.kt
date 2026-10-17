package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.network.*
import com.kudo.android.ui.account.DohSettingsScreen
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.theme.LogoYellow
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class DohSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val settings = DohSettings(instrumentation.context).apply { update(DohConfiguration()) }
    private val requests = RequestSettings(instrumentation.context)
    private var snapshot = NetworkSnapshot(1)
    private val network = DohNetwork(instrumentation.targetContext, settings) { snapshot }
    @After fun close() { network.close(); settings.update(DohConfiguration()) }
    private fun show() { compose.setContent { KudoTheme(DisplaySettings()) { DohSettingsScreen(network, requests) {} } } }

    @Test fun addServerActionsStayFlatAcrossThemesAndTextSizes() {
        var display by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        val indication = Color(0xFF38BD9F)
        compose.setContent { KudoTheme(display) {
            CompositionLocalProvider(LocalIndication provides SolidIndication(indication)) {
                DohSettingsScreen(network, requests) {}
            }
        } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (scale in listOf(1f, 1.3f)) {
            compose.runOnIdle { display = display.copy(theme = theme, fontScale = scale) }
            compose.onNodeWithTag("doh-add").assertIsEnabled().performClick()
            val save = compose.onNodeWithText("保存").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
            assertFlatCorners(save, LogoYellow)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val output = File(instrumentation.targetContext.getExternalFilesDir(null),
                "ui-tests/doh-add-flat-${theme.name.lowercase()}-$scale.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            val cancel = compose.onNodeWithText("取消").assertHeightIsAtLeast(56.dp)
            cancel.performTouchInput { down(center) }
            assertFlatCorners(cancel, indication)
            cancel.performTouchInput { up() }
            compose.onNodeWithTag("doh-add").assertIsEnabled()
            compose.runOnIdle { assertTrue(settings.configuration.value.customServers.isEmpty()) }
        }
    }

    @Test fun editingServerKeepsItsCapsuleSaveAction() {
        settings.update(DohConfiguration().saveServer(validateDohServer("测试节点", "https://dns.example/query", "")))
        show()
        compose.onNodeWithContentDescription("管理 测试节点").performClick()
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithText("编辑服务器").assertIsDisplayed()
        val pixels = buttonPixels(compose.onNodeWithText("保存").assertHeightIsAtLeast(56.dp))
        val inset = (pixels.height / 20).coerceAtLeast(4)
        for (horizontal in listOf(inset, pixels.width - inset - 1)) {
            assertColor(LogoYellow, pixels[horizontal, pixels.height / 2])
            val corner = pixels[horizontal, pixels.height / 4]
            assertTrue("编辑服务器仍需胶囊轮廓", kotlin.math.abs(corner.red - LogoYellow.red) > .05f ||
                kotlin.math.abs(corner.green - LogoYellow.green) > .05f || kotlin.math.abs(corner.blue - LogoYellow.blue) > .05f)
        }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("doh-add").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, settings.configuration.value.customServers.size) }
    }

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

    private fun assertFlatCorners(button: SemanticsNodeInteraction, fill: Color) {
        val pixels = buttonPixels(button)
        val inset = (pixels.height / 20).coerceAtLeast(4)
        for (horizontal in listOf(inset, pixels.width - inset - 1)) {
            for (vertical in listOf(pixels.height / 4, pixels.height * 3 / 4)) assertColor(fill, pixels[horizontal, vertical])
        }
    }

    private fun buttonPixels(button: SemanticsNodeInteraction): PixelMap {
        val bounds = button.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().captureToImage().asAndroidBitmap()
        val image = Bitmap.createBitmap(root, bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.width.roundToInt(), bounds.height.roundToInt())
        val pixels = image.asImageBitmap().toPixelMap()
        image.recycle()
        root.recycle()
        return pixels
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals("red", expected.red, actual.red, .01f)
        assertEquals("green", expected.green, actual.green, .01f)
        assertEquals("blue", expected.blue, actual.blue, .01f)
    }

    private data class SolidIndication(val color: Color) : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() { drawRect(color); drawContent() }
        }
    }
}
